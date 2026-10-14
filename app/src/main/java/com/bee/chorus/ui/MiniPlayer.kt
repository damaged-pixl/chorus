package com.bee.chorus.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.Player
import com.bee.chorus.R
import com.bee.chorus.playback.PlayerHolder

/**
 * Single-row mini player. Same contract as [PlayerPanel] — a pure view over the shared
 * [PlayerHolder] controller, so it stays in sync with the full-screen player for free.
 *
 * Auto-hides when the queue is empty. If the host wants to control visibility explicitly,
 * [setVisible] pins it and automatic hiding stops applying.
 */
class MiniPlayer @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    private val artView: ImageView
    private val titleView: TextView
    private val playPauseButton: TextView

    private var attached = false
    private var lastArtKey: String? = null
    private var visibilityOverride: Boolean? = null

    // Same instance is removed later — PlayerHolder removes listeners by identity.
    private val playerListener: (Player) -> Unit = { player -> render(player) }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_mini_player, this, true)

        artView = findViewById(R.id.mini_art)
        titleView = findViewById(R.id.mini_title)
        playPauseButton = findViewById(R.id.mini_play_pause)

        playPauseButton.setOnClickListener {
            PlayerHolder.whenReady { player ->
                if (player.isPlaying) player.pause() else player.play()
            }
        }

        visibility = View.GONE
    }

    // ---------------------------------------------------------------- public

    /** Start observing the shared player. Idempotent. */
    fun show() {
        if (attached) return
        attached = true
        PlayerHolder.addPlayerListener(playerListener)
    }

    /** Stop observing. Safe to call when not attached. */
    fun hide() {
        if (attached) {
            attached = false
            PlayerHolder.removePlayerListener(playerListener)
        }
    }

    /**
     * Pin this bar's visibility. After any call here the view no longer auto-hides itself —
     * the host owns it. Pass no further calls to hand control back to "loaded" detection.
     */
    fun setVisible(visible: Boolean) {
        visibilityOverride = visible
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!attached) show()
    }

    override fun onDetachedFromWindow() {
        hide()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- render

    private fun render(player: Player) {
        val metadata = player.currentMediaItem?.mediaMetadata

        val title = metadata?.title?.toString().orEmpty()
        titleView.text = title.ifEmpty { NOTHING_PLAYING }

        val artUri = metadata?.artworkUri
        val artKey = artUri?.toString()
        if (artKey != lastArtKey) {
            lastArtKey = artKey
            if (artUri != null) {
                Art.load(context, artView, artUri, ART_TARGET_PX)
            } else {
                artView.setImageDrawable(null)
            }
        }

        playPauseButton.text = if (player.isPlaying) GLYPH_PAUSE else GLYPH_PLAY
        playPauseButton.contentDescription = if (player.isPlaying) "Pause" else "Play"

        val hasQueue = player.mediaItemCount > 0
        playPauseButton.isEnabled = hasQueue
        playPauseButton.alpha = if (hasQueue) 1f else DISABLED_ALPHA

        // Auto-visibility only applies while the host has not pinned us with setVisible().
        if (visibilityOverride == null) {
            visibility = if (hasQueue) View.VISIBLE else View.GONE
        }
    }

    private companion object {
        const val ART_TARGET_PX = 128
        const val DISABLED_ALPHA = 0.35f
        const val GLYPH_PLAY = "▶"
        const val GLYPH_PAUSE = "⏸"
        const val NOTHING_PLAYING = "Nothing playing"
    }
}