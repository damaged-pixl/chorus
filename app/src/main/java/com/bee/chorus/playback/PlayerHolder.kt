package com.bee.chorus.playback

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bee.chorus.data.Settings
import com.bee.chorus.data.Track
import java.util.concurrent.Executor

/**
 * Single shared MediaController bound to [PlaybackService].
 * The whole UI talks to this one object, so the mini player and full-screen player
 * are always the same underlying playback.
 *
 * Binding is asynchronous (buildAsync); callers that need the controller before it is
 * ready register a pending action that fires as soon as it arrives.
 */
object PlayerHolder {

    private const val MAX_RETRIES = 5
    private const val RETRY_DELAY_MS = 400L

    @Volatile private var controller: MediaController? = null
    private var binding = false
    private var retries = 0

    /** App context for persistence (speed); set once in [bind]. */
    @Volatile private var appCtx: Context? = null

    private val playerListeners = mutableListOf<(Player) -> Unit>()
    private val readyActions = mutableListOf<(MediaController) -> Unit>()

    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }

    /** True once the controller exists. */
    val isReady: Boolean get() = controller != null

    /**
     * The live controller viewed as a read-only [Player], or null before binding
     * completes. For observers that need state without an action (the sleep timer's
     * countdown checks duration/position); mutators still go through [whenReady].
     */
    val player: Player? get() = controller

    /** Begin binding (idempotent). Safe to call from the main thread. */
    fun bind(context: Context) {
        if (binding || controller != null) return
        binding = true

        val app = context.applicationContext
        appCtx = app
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()

        future.addListener({
            val c = try { future.get() } catch (e: Exception) { null }
            if (c != null) onControllerReady(c) else retry(app)
        }, mainExecutor)
    }

    private fun retry(app: Context) {
        if (retries++ >= MAX_RETRIES) {
            binding = false
            return
        }
        main.postDelayed({
            binding = false
            bind(app)
        }, RETRY_DELAY_MS)
    }

    private fun onControllerReady(c: MediaController) {
        controller = c
        binding = false
        c.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                // Drain a copy: a listener may remove itself from inside its own callback
                // (SleepTimer.onEndOfTrack -> disarm -> removePlayerListener). ArrayList
                // remove doesn't bump modCount, so there is no ConcurrentModificationException
                // - but the in-flight iterator would skip the next listener.
                playerListeners.toList().forEach { it(player) }
            }
        })
        val pending = readyActions.toList()
        readyActions.clear()
        pending.forEach { it(c) }
    }

    fun addPlayerListener(l: (Player) -> Unit) {
        playerListeners.add(l)
        controller?.let { l(it) }
    }

    fun removePlayerListener(l: (Player) -> Unit) {
        playerListeners.remove(l)
    }

    /** Run [action] now if the controller is ready, otherwise once it is. */
    fun whenReady(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) action(c) else readyActions.add(action)
    }

    /** Replace the queue with [tracks] starting at [startIndex]. */
    fun play(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        whenReady { c ->
            val items = tracks.map { it.asMediaItem() }
            c.setMediaItems(items, startIndex.coerceIn(0, items.size - 1), 0L)
            c.prepare()
            c.play()
        }
    }

    /**
     * Play [tracks] *now* without discarding what is queued: the items are inserted
     * immediately after the current one and playback hops to them, so the queue the user
     * built (and the shuffle order behind it) carries on when these finish. Empty player:
     * there is no "after", so they simply become the queue.
     *
     * This is the share sheet's "Play now" — the polite counterpart of [play], which
     * replaces everything.
     */
    fun playNow(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        whenReady { c ->
            val items = tracks.map { it.asMediaItem() }
            val at = if (c.mediaItemCount == 0) 0 else c.currentMediaItemIndex + 1
            c.addMediaItems(at, items)
            c.seekTo(at, 0L)
            c.prepare()
            c.play()
        }
    }

    /**
     * Append [tracks] to the end of the queue without touching what is playing. An idle
     * player (nothing ever queued, or the queue was cleared) gets prepared but not
     * started: "add to queue" must never begin playback on its own, but the items must
     * be ready for the play button the moment it is pressed.
     */
    fun enqueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        whenReady { c ->
            c.addMediaItems(c.mediaItemCount, tracks.map { it.asMediaItem() })
            if (c.playbackState == Player.STATE_IDLE) {
                c.playWhenReady = false
                c.prepare()
            }
        }
    }

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    /**
     * Set playback speed, live and persisted. The write is two-way: the controller
     * (mirrored to the service's player) takes it immediately, and [Settings] keeps it so
     * the next process starts at the same speed — PlaybackService.onCreate re-applies it.
     */
    fun setSpeed(value: Float) {
        appCtx?.let { Settings.setSpeed(it, value) }
        controller?.playbackParameters = PlaybackParameters(value)
    }

    /** The player's current speed; 1f before the controller exists. */
    fun speed(): Float = controller?.playbackParameters?.speed ?: 1f
}

/**
 * One queue item for [this] track. Shared by [PlayerHolder.play] and PlaybackService's
 * restore, so a restored queue carries the same mediaId, content URI and metadata as a
 * freshly built one (the notification and the queue panel read those).
 */
internal fun Track.asMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(albumArtUri)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        )
        .build()