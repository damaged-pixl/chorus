package com.bee.chorus.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.core.app.TaskStackBuilder
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.bee.chorus.PlayerActivity
import com.bee.chorus.data.Library
import com.bee.chorus.data.PlaylistStore
import com.bee.chorus.data.Settings
import com.bee.chorus.data.Track
import com.bee.chorus.widget.ChorusWidget
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Background playback host. MediaSessionService gives us, for free:
 *   - a MediaSession (lock-screen / Bluetooth / headset controls)
 *   - the media notification (with transport controls)
 *   - foreground-service compliance for media playback
 * Shuffle/repeat live on the ExoPlayer itself, so the notification reflects them.
 *
 * The notification's content intent (what a tap on it fires) is the session's
 * `sessionActivity`. Media3's default notification reads it and calls `setContentIntent`,
 * so with no session activity set the tap does nothing at all. It is wired to
 * [PlayerActivity] via a [TaskStackBuilder] so a tap from the launcher stack still lands
 * somewhere sensible, and it must be `FLAG_IMMUTABLE` for targetSdk 31+.
 *
 * Gapless playback: nothing here opts *out* of it. ExoPlayer applies encoded gapless
 * metadata (MP3 LAME/Xing gapless info, FLAC SEEKTABLE+padding trimming) in
 * DefaultAudioSink by default, and the sink chain ([ChorusRenderersFactory]) preserves
 * that. There is deliberately no crossfade.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /**
     * Service IO: play-stat and queue-snapshot writes, one daemon thread, so a transition
     * never blocks the playback looper.
     */
    private val statsIo = Executors.newSingleThreadExecutor(DaemonThreadFactory)

    /** Own store instance over the app context; the service outlives any Activity. */
    private val statsStore by lazy { PlaylistStore(applicationContext) }

    /**
     * ReplayGain factor handed to the audio sink's GainProcessor. Written from the IO
     * thread at each media-item transition (tag parse happens off the playback looper),
     * read on the audio thread.
     */
    private val gainProvider = ReplayGainProvider()

    /**
     * The system equaliser, attached to whatever audio session the player's sink ends up
     * with — see [ChorusEqualiser] for why that arrives as a callback rather than a value.
     */
    private val equaliser = ChorusEqualiser(this)

    /** A Setup-tab equaliser change re-applies to the session that is playing. */
    private val equaliserListener: () -> Unit = { equaliser.apply() }

    /**
     * Coalesces queue-persist requests: onEvents fires several times in a burst (pause,
     * transition, mode flip), and each one wants the *latest* position saved, not one
     * write per event. 500 ms of quiet → one write.
     */
    private val persistHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val persistQueue = Runnable { persist(captureQueue()) }

    /** Empty ids means "the queue was emptied" (a clear); null means "no player" (nothing). */
    private fun persist(snap: PlaylistStore.QueueSnapshot?) {
        when {
            snap == null -> Unit
            snap.mediaIds.isEmpty() -> statsIo.execute { statsStore.clearQueue() }
            else -> statsIo.execute { statsStore.saveQueue(snap) }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(ChorusRenderersFactory(this, gainProvider))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)   // pause when headphones unplug
            .build()

        // Pick the audio session id here, rather than waiting to be told one. Media3 never
        // *reports* the sink's own session id — Player.Listener.onAudioSessionIdChanged only
        // echoes an app's own setAudioSessionId() call — so a service that waits for it
        // waits forever and the equaliser never attaches. One id, chosen before prepare():
        // the sink is built on it from the first frame, nothing has to be torn down, and
        // the equaliser can attach straight away instead of racing the first buffer.
        val sessionId = Util.generateAudioSessionId(this)
        player.setAudioSessionId(sessionId)
        equaliser.attach(sessionId)

        // The queue the process had when it died: restored paused (never autoplay), so
        // reopening the app lands on the same track/position/shuffle/repeat as before.
        restoreQueue(player)
        // Persisted speed: one read at construction covers the restored queue and any
        // playback started later in this process (PlayerHolder.setSpeed updates it live).
        player.playbackParameters =
            androidx.media3.common.PlaybackParameters(Settings.speed(this))
        // Re-arm a sleep timer that outlived the process (no-op when nothing was armed).
        SleepTimer.restore(this)
        // Shuffle by default: applied before the queue is restored, so a restored queue
        // (which carries the shuffle state the user actually left it in) still wins.
        if (Settings.defaultShuffle(this)) player.shuffleModeEnabled = true

        // Play counts. onMediaItemTransition fires only when the *current item* changes —
        // pausing/resuming and seeking within a track never trigger it — so every reason
        // (auto-advance, user skip, new queue) counts as one genuine play of the new track.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId?.toLongOrNull() ?: return
                statsIo.execute { statsStore.recordPlay(id) }
                // ReplayGain. The URI lives on the item's local configuration (built by
                // PlayerHolder/restore); no URI, no gain. Parsed on the IO thread and
                // cached per id, so only the first play of an untagged track costs a read.
                applyGain(mediaItem, Settings.replayGainMode(this@PlaybackService))
            }

            // A session id change (our own setAudioSessionId, or a future one) re-hangs the
            // equaliser: the old effect belongs to the session that has gone away.
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                equaliser.attach(audioSessionId)
            }

            // Home-screen widget. onEvents already covers every state the widget draws
            // (item, isPlaying, queue position), so this one hook replaces a receiver and
            // avoids polling; the existing code above is untouched.
            override fun onEvents(player: Player, events: Player.Events) {
                ChorusWidget.push(this@PlaybackService, player)
                // Queue persistence: every meaningful change arrives here (transition,
                // queue edit, shuffle/repeat flip, play/pause = a fresh position to save).
                persistHandler.removeCallbacks(persistQueue)
                persistHandler.postDelayed(persistQueue, 500L)
            }
        })

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent())
            .build()

        // A ReplayGain mode change is applied to the current track straight away.
        Settings.addReplayGainListener(gainModeListener)
        // Same for the equaliser, which is otherwise only applied on a new session.
        Settings.addEqualiserListener(equaliserListener)

        // Play on connect. Seeded from the outputs already present, so the first callback
        // restating them cannot be read as a fresh plug-in. A null handler means "on the
        // calling thread's looper", which in onCreate is the main one.
        listenConnected = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { isListenDevice(it) }
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
    }

    /**
     * Apply a ReplayGain [mode] to [item]: the factor the audio sink's GainProcessor uses.
     *
     * OFF is applied immediately (the file is never opened). Otherwise the tags are read on
     * the IO thread — the file open must not happen on the playback looper — and the
     * factor is written to the provider, which the audio thread reads.
     */
    private fun applyGain(item: MediaItem?, mode: Settings.ReplayGainMode) {
        val id = item?.mediaId?.toLongOrNull() ?: return
        if (mode == Settings.ReplayGainMode.OFF) {
            gainProvider.setFactor(1f)
            return
        }
        val uri = item.localConfiguration?.uri ?: return
        statsIo.execute {
            gainProvider.setFactor(ReplayGain.factorFor(applicationContext, id, uri, mode))
        }
    }

    /**
     * ReplayGain mode changed in Setup: re-apply it to whatever is playing, so the change
     * is audible at once rather than at the next track. The listener is removed in
     * onDestroy — Settings holds listeners strongly, and one capturing the service would
     * outlive it.
     */
    private val gainModeListener: (Settings.ReplayGainMode) -> Unit = { mode ->
        applyGain(mediaSession?.player?.currentMediaItem, mode)
    }

    /**
     * Capture the player's current queue as a [PlaylistStore.QueueSnapshot], or null when
     * there is nothing to save. Player state may only be read on the main thread, so the
     * snapshot is taken here and the write runs on [statsIo].
     */
    private fun captureQueue(): PlaylistStore.QueueSnapshot? {
        val player = mediaSession?.player ?: return null
        if (player.mediaItemCount == 0) {
            return PlaylistStore.QueueSnapshot(emptyList(), 0, 0L, false, Player.REPEAT_MODE_OFF)
        }
        val ids = ArrayList<Long>(player.mediaItemCount)
        for (i in 0 until player.mediaItemCount) {
            player.getMediaItemAt(i).mediaId.toLongOrNull()?.let { ids.add(it) }
        }
        if (ids.isEmpty()) return null
        return PlaylistStore.QueueSnapshot(
            mediaIds = ids,
            currentIndex = player.currentMediaItemIndex.coerceIn(0, ids.size - 1),
            positionMs = player.currentPosition,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
        )
    }

    /**
     * Rebuild the saved queue from MediaStore. The snapshot stores only MediaStore ids, so
     * each id is resolved to its row (title/artist/album/art) and handed back as a
     * [com.bee.chorus.playback.asMediaItem] — the same shape [com.bee.chorus.playback.PlayerHolder]
     * builds, so the notification and queue panel cannot tell a restore from a fresh play.
     * Ids whose files have since been deleted drop out; the current index follows its id,
     * or clamps when that id is gone.
     */
    private fun restoreQueue(player: Player) {
        val snap = statsStore.savedQueue() ?: return
        if (snap.mediaIds.isEmpty()) return

        val projection = arrayOf(
            android.provider.MediaStore.Audio.Media._ID,
            android.provider.MediaStore.Audio.Media.TITLE,
            android.provider.MediaStore.Audio.Media.ARTIST,
            android.provider.MediaStore.Audio.Media.ALBUM,
            android.provider.MediaStore.Audio.Media.ALBUM_ID,
        )
        val selection = "_id IN (${snap.mediaIds.joinToString(",") { "?" }})"
        val byId = HashMap<Long, Track>(snap.mediaIds.size)
        contentResolver.query(
            Library.TRACKS_URI, projection, selection,
            snap.mediaIds.map { it.toString() }.toTypedArray(), null
        )?.use { c ->
            val idC = c.getColumnIndexOrThrow("_id")
            val titleC = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.TITLE)
            val artistC = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ARTIST)
            val albumC = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ALBUM)
            val albumIdC = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ALBUM_ID)
            while (c.moveToNext()) {
                // MediaStore columns are nullable in theory; blank the strings rather than
                // crash a restore over a malformed row.
                val id = c.getLong(idC)
                byId[id] = Track(
                    id = id,
                    title = c.getString(titleC) ?: "",
                    artist = c.getString(artistC) ?: "",
                    album = c.getString(albumC) ?: "",
                    albumId = c.getLong(albumIdC),
                    durationMs = 0, trackNo = 0, year = 0,
                    path = "",
                )
            }
        }

        // Keep the saved order; drop ids whose files are gone.
        val items = ArrayList<MediaItem>(snap.mediaIds.size)
        var newIndex = -1
        snap.mediaIds.forEachIndexed { position, id ->
            val track = byId[id] ?: return@forEachIndexed
            if (position == snap.currentIndex) newIndex = items.size
            items.add(track.asMediaItem())
        }
        if (items.isEmpty()) {
            statsStore.clearQueue()
            return
        }
        player.setMediaItems(
            items,
            (if (newIndex >= 0) newIndex else snap.currentIndex).coerceIn(0, items.size - 1),
            // "Resume where you left off" is a setting: with it off, a restored queue
            // starts the current track from the beginning instead of the saved position.
            if (Settings.resumePosition(this)) snap.positionMs.coerceAtLeast(0L) else 0L,
        )
        player.shuffleModeEnabled = snap.shuffle
        player.repeatMode = snap.repeatMode
        // prepare() but never playWhenReady: a restored queue is always paused.
        player.prepare()
    }

    // ------------------------------------------------------------------ play on connect

    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /**
     * True when something you would put on your ears is the current output.
     *
     * This is a *history* flag, not a fact: seeded from what is already connected when the
     * service starts and kept in step on every device callback, so play-on-connect can be
     * decided from a transition (nothing -> something) instead of from a device merely
     * being present. Without that, the first callback after registration — which restates
     * the current set — would count as a plug-in, and a Bluetooth source that never
     * disconnected would re-trigger on every unrelated output change.
     */
    private var listenConnected = false

    /**
     * Play on connect, via [AudioDeviceCallback] rather than a broadcast.
     *
     * A callback needs no permission and covers wired and Bluetooth outputs alike, which
     * ACTION_HEADSET_PLUG (wired only, and never deliverable to a manifest receiver) does
     * not. The honest limit: this only hears a connection while the service is alive — a
     * cold process cannot notice a headset, and waking a dead app into a notification
     * just because headphones appeared would be a bigger surprise than the setting removes.
     */
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = devicesChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = devicesChanged()
    }

    private fun devicesChanged() {
        val connected = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { isListenDevice(it) }
        val rose = connected && !listenConnected
        listenConnected = connected
        if (rose) maybePlayOnConnect()
    }

    /** A device you would put on your ears — the outputs the setting is about. */
    private fun isListenDevice(info: AudioDeviceInfo): Boolean = when (info.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET -> true
        else -> false
    }

    /**
     * Start playback when a listen device appears — the mirror of the becoming-noisy pause
     * that stops it when one goes away (ExoPlayer's `handleAudioBecomingNoisy`), and the
     * whole of what "play on connect" means.
     *
     * Guarded three ways: the setting itself, something actually loaded (an empty player is
     * not an invitation to try), and not already playing (switching output mid-track must
     * not restart anything). A deliberately paused queue *is* started — that is the feature.
     */
    private fun maybePlayOnConnect() {
        if (!Settings.playOnConnect(this)) return
        val player = mediaSession?.player ?: return
        if (player.mediaItemCount == 0 || player.isPlaying) return
        player.play()
    }

    /**
     * The boot receiver starts us with [ACTION_RESTORE_ON_BOOT] so that a restore with
     * nothing saved can stop itself straight away: `startForegroundService` expects a
     * foreground service within a few seconds, and a service whose queue came back empty
     * has no notification to show (Media3 shows one only for a non-empty timeline). Stopping
     * first is the documented way out of that deadline. Every other start — media button,
     * controller connection, the app itself — is Media3's to handle as usual.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTORE_ON_BOOT &&
            mediaSession?.player?.mediaItemCount == 0
        ) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * PendingIntent for the media notification's tap target ([PlayerActivity]).
     *
     * The TaskStackBuilder gives the tap a parent stack, so opening the player from the
     * notification's back-stack-less state (app swiped away, phone locked) still lets Back
     * walk up to [com.bee.chorus.MainActivity] rather than dead-ending. `FLAG_IMMUTABLE`
     * is mandatory for a PendingIntent used under targetSdk 31+.
     */
    private fun sessionActivityIntent(): PendingIntent =
        TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(Intent(this@PlaybackService, PlayerActivity::class.java))
            // PendingIntent? on the platform type; it cannot be null for an explicit
            // component, so `!!` is safe and keeps the return type non-null.
            getPendingIntent(
                0,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )!!
        }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // Stop the service if it isn't playing and the task was swiped away.
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Settings holds listeners strongly: unhook before the service goes away.
        Settings.removeReplayGainListener(gainModeListener)
        Settings.removeEqualiserListener(equaliserListener)
        // The effect is attached to the player's session, so it goes first: releasing the
        // player underneath a live effect leaves the platform holding a dead session.
        equaliser.release()
        // Same reasoning, and the same pairing: one registration in onCreate, one removal here.
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        // Final queue snapshot first, while the player still holds real state: a debounced
        // save pending when the service dies would otherwise be lost with the Handler.
        persistHandler.removeCallbacks(persistQueue)
        val finalSnapshot = captureQueue()
        // Player first: releasing it is what stops media-item transitions, so no new
        // stats writes can race the executor shutdown below. Doing it the other way round
        // left a transition firing onMediaItemTransition into a shut-down executor.
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        // The last snapshot is written synchronously: shutdownNow() would drop a queued
        // one, and the store is about to close under it. It is one small transaction.
        if (finalSnapshot != null) {
            if (finalSnapshot.mediaIds.isEmpty()) statsStore.clearQueue()
            else statsStore.saveQueue(finalSnapshot)
        }
        // shutdownNow, not shutdown: a write already queued must not outlive the store
        // it is about to use.
        statsIo.shutdownNow()
        // Only reachable from a service that recorded at least one play, but close() on a
        // never-opened SQLiteOpenHelper is a no-op, so this needs no guard.
        statsStore.close()
        super.onDestroy()
    }

    companion object {
        /**
         * Action the boot receiver starts the service with, so [onStartCommand] can tell a
         * queue restore from every other start. Not a media action: Media3 ignores it.
         */
        const val ACTION_RESTORE_ON_BOOT = "com.bee.chorus.action.RESTORE_ON_BOOT"
    }

    private object DaemonThreadFactory : ThreadFactory {
        override fun newThread(r: Runnable): Thread =
            Thread(r, "chorus-stats").apply { isDaemon = true }
    }
}
