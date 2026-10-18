package com.bee.chorus.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import com.bee.chorus.R
import com.bee.chorus.playback.PlayerHolder

/**
 * Single-row mini player. Same contract as [PlayerPanel] — a pure view over the shared
 * [PlayerHolder] controller, so it stays in sync with the full-screen player for free.
 *
 * Auto-hides when the queue is empty. If the host wants to control visibility explicitly,
 * [setVisible] pins it and automatic hiding stops applying.
 *
 * The top-edge progress hairline follows the same tick discipline as the full player's
 * seek bar (Media3 has no position callback): one ~500 ms main-thread tick that runs only
 * while audio is playing *and* this view is attached, and cancels itself otherwise.
 */
class MiniPlayer @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    private val artView: ImageView
    private val titleView: TextView
    private val artistView: TextView
    private val playPauseButton: TextView
    private val dividerView: View
    private val progressView: View

    private var attached = false
    private var lastArtKey: String? = null
    private var visibilityOverride: Boolean? = null
    private var lastPlayer: Player? = null
    private var tickScheduled = false

    private val handler = Handler(Looper.getMainLooper())

    // Same instance is removed later — PlayerHolder removes listeners by identity.
    private val playerListener: (Player) -> Unit = { player -> render(player) }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_mini_player, this, true)

        artView = findViewById(R.id.mini_art)
        titleView = findViewById(R.id.mini_title)
        artistView = findViewById(R.id.mini_artist)
        playPauseButton = findViewById(R.id.mini_play_pause)
        dividerView = findViewById(R.id.mini_divider)
        progressView = findViewById(R.id.mini_progress)

        styleBar()

        playPauseButton.setOnClickListener {
            PlayerHolder.whenReady { player ->
                if (player.isPlaying) player.pause() else player.play()
            }
        }

        visibility = View.GONE
    }

    // ---------------------------------------------------------------- styling

    /**
     * The bar is a raised surface, not the window background: a fill a hair off it plus a
     * hairline along the top edge, so the list behind it reads as "behind". Both colours are
     * derived from the theme in code, so the Cycle light theme follows for free.
     */
    private fun styleBar() {
        background = ColorDrawable(Ui.surfaceFill(context, BAR_BLEND))
        dividerView.setBackgroundColor(
            Ui.faintColor(context, android.R.attr.textColorSecondary, HAIRLINE_ALPHA)
        )
        // Rounded thumbnail: the same shape doubles as the outline clipToOutline needs.
        artView.background = Ui.roundedSurface(context, ART_CORNER_DP)
        artView.clipToOutline = true
        playPauseButton.background = Ui.circularTapTarget(context)
    }

    // ---------------------------------------------------------------- public

    /** Start observing the shared player. Idempotent. */
    fun show() {
        if (attached) return
        attached = true
        PlayerHolder.addPlayerListener(playerListener)
        if (lastPlayer?.isPlaying == true) scheduleTick()
    }

    /** Stop observing. Safe to call when not attached. */
    fun hide() {
        if (attached) {
            attached = false
            PlayerHolder.removePlayerListener(playerListener)
        }
        stopTick()
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
        lastPlayer = player

        val metadata = player.currentMediaItem?.mediaMetadata

        val title = metadata?.title?.toString().orEmpty()
        titleView.text = title.ifEmpty { NOTHING_PLAYING }

        // Second line under the title: the artist, falling back to the album, so the bar
        // says what it is playing without another row of controls.
        val secondary = metadata?.artist?.toString()?.takeIf { it.isNotBlank() }
            ?: metadata?.albumTitle?.toString()?.takeIf { it.isNotBlank() }
        artistView.text = secondary.orEmpty()
        artistView.visibility = if (secondary == null) View.INVISIBLE else View.VISIBLE

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

        renderProgress(player)

        // Auto-visibility only applies while the host has not pinned us with setVisible().
        if (visibilityOverride == null) {
            visibility = if (hasQueue) View.VISIBLE else View.GONE
        }

        // Keep the hairline advancing while audio actually is; it stops itself otherwise.
        if (player.isPlaying) scheduleTick() else stopTick()
    }

    /** Width fraction of the top hairline: current position over duration, clamped. */
    private fun renderProgress(player: Player) {
        val duration = player.duration
        val fraction = if (duration != C.TIME_UNSET && duration > 0L) {
            (player.currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        progressView.scaleX = fraction
    }

    // ---------------------------------------------------------------- ticker

    /**
     * Mirror of [PlayerPanel]'s tick: one short main-thread runnable, only while playing
     * and attached, self-cancelling on pause or detach. No work when idle.
     */
    private val tick = object : Runnable {
        override fun run() {
            tickScheduled = false
            val player = lastPlayer ?: return
            renderProgress(player)
            if (player.isPlaying && isAttachedToWindow) scheduleTick()
        }
    }

    private fun scheduleTick() {
        if (tickScheduled) return
        tickScheduled = true
        handler.postDelayed(tick, TICK_MS)
    }

    private fun stopTick() {
        handler.removeCallbacks(tick)
        tickScheduled = false
    }

    private companion object {
        const val ART_TARGET_PX = 128
        const val ART_CORNER_DP = 8f
        const val DISABLED_ALPHA = 0.35f
        const val GLYPH_PLAY = "▶"
        const val GLYPH_PAUSE = "⏸"
        const val NOTHING_PLAYING = "Nothing playing"
        const val TICK_MS = 500L

        /** How far the bar's surface is pushed off the window background. */
        const val BAR_BLEND = 0.07f

        /** Alpha of the top hairline, over the theme's secondary text colour. */
        const val HAIRLINE_ALPHA = 0x24
    }
}