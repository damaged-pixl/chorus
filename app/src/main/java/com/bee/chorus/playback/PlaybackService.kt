package com.bee.chorus.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.bee.chorus.data.PlaylistStore
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Background playback host. MediaSessionService gives us, for free:
 *   - a MediaSession (lock-screen / Bluetooth / headset controls)
 *   - the media notification (with transport controls)
 *   - foreground-service compliance for media playback
 * Shuffle/repeat live on the ExoPlayer itself, so the notification reflects them.
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
        })

        mediaSession = MediaSession.Builder(this, player).build()
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
