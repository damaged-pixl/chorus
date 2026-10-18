package com.bee.chorus.playback

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
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
                playerListeners.forEach { it(player) }
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
            val items = tracks.map { track ->
                MediaItem.Builder()
                    .setMediaId(track.id.toString())
                    .setUri(track.uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(track.artist)
                            .setAlbumTitle(track.album)
                            .setArtworkUri(track.albumArtUri)
                            .setIsBrowsable(false)
                            .setIsPlayable(true)
                            .build()
                    )
                    .build()
            }
            c.setMediaItems(items, startIndex.coerceIn(0, items.size - 1), 0L)
            c.prepare()
            c.play()
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
}