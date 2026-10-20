package com.bee.chorus.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.TaskStackBuilder
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.bee.chorus.PlayerActivity
import com.bee.chorus.data.PlaylistStore
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
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /** Play-stat writes: one daemon thread, so a transition never blocks the playback looper. */
    private val statsIo = Executors.newSingleThreadExecutor(DaemonThreadFactory)

    /** Own store instance over the app context; the service outlives any Activity. */
    private val statsStore by lazy { PlaylistStore(applicationContext) }

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)   // pause when headphones unplug
            .build()

        // Play counts. onMediaItemTransition fires only when the *current item* changes —
        // pausing/resuming and seeking within a track never trigger it — so every reason
        // (auto-advance, user skip, new queue) counts as one genuine play of the new track.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId?.toLongOrNull() ?: return
                statsIo.execute { statsStore.recordPlay(id) }
            }

            // Home-screen widget. onEvents already covers every state the widget draws
            // (item, isPlaying, queue position), so this one hook replaces a receiver and
            // avoids polling; the existing code above is untouched.
            override fun onEvents(player: Player, events: Player.Events) {
                ChorusWidget.push(this@PlaybackService, player)
            }
        })

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent())
            .build()
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
        // Player first: releasing it is what stops media-item transitions, so no new
        // stats writes can race the executor shutdown below. Doing it the other way round
        // left a transition firing onMediaItemTransition into a shut-down executor.
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        // shutdownNow, not shutdown: a write already queued must not outlive the store
        // it is about to use.
        statsIo.shutdownNow()
        // Only reachable from a service that recorded at least one play, but close() on a
        // never-opened SQLiteOpenHelper is a no-op, so this needs no guard.
        statsStore.close()
        super.onDestroy()
    }

    private object DaemonThreadFactory : ThreadFactory {
        override fun newThread(r: Runnable): Thread =
            Thread(r, "chorus-stats").apply { isDaemon = true }
    }
}
