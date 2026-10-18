package com.bee.chorus.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import com.bee.chorus.R
import com.bee.chorus.data.Favourites
import com.bee.chorus.playback.PlayerHolder
import java.util.Locale

/**
 * Full-screen player panel. Pure view over the shared [PlayerHolder] controller — it owns no
 * playback state of its own, so the mini player and this panel can never disagree.
 *
 * Lifecycle: call [show] to start observing and [hide] (or let the view detach) to stop.
 * The [PlayerHolder] listener is removed on detach so a finished screen leaves no callbacks.
 *
 * Everything visual here is drawn from code: no image assets, no fonts, no Material. The
 * artwork itself is decoded twice by [Art] — once at display size for the square, once at
 * 64 px for the stretched backdrop — which is what keeps the screen cheap in RAM.
 */
class PlayerPanel @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    private val backdropView: ImageView
    private val scrimView: View
    private val contentColumn: LinearLayout
    private val artFrame: View
    private val artView: ImageView
    private val titleView: TextView
    private val artistView: TextView
    private val seekBar: SeekBar
    private val elapsedView: TextView
    private val totalView: TextView
    private val prevButton: TextView
    private val rewindButton: TextView
    private val playPauseButton: TextView
    private val forwardButton: TextView
    private val nextButton: TextView
    private val shuffleButton: TextView
    private val repeatButton: TextView
    private val starButton: TextView

    private val handler = Handler(Looper.getMainLooper())

    // Drawables are built once and re-used, so state changes never allocate. Each instance is
    // owned by exactly one view — a Drawable must never be shared between two views.
    private val shuffleIdleBg = pill(0x1FFFFFFF, BORDER_IDLE)
    private val shuffleOnBg = pill(0x66FFC107, 0)
    private val repeatIdleBg = pill(0x1FFFFFFF, BORDER_IDLE)
    private val repeatAllBg = pill(0x66FFC107, 0)
    private val repeatOneBg = pill(ACCENT, 0)
    private val starIdleBg = pill(0x1FFFFFFF, BORDER_IDLE)
    private val starOnBg = pill(0x66FFC107, 0)

    private var attached = false
    private var scrubbing = false
    private var lastArtKey: String? = null
    private var lastPlayer: Player? = null
    private var tickScheduled = false
    private var lastShuffleOn: Boolean? = null
    private var lastRepeatMode = -1
    private var lastStarId: Long? = null
    private var lastStarOn: Boolean? = null
    private var measuredSide = -1

    // Held as a property so the *same* instance can be handed back to removePlayerListener,
    // which removes by identity.
    private val playerListener: (Player) -> Unit = { player -> render(player) }

    // Same identity trick for the Favourites listener: re-render only the star, since a
    // favourite can change (from the track list) without any player event at all.
    private val favouritesListener: (Long, Boolean) -> Unit = { _, _ ->
        lastPlayer?.let { renderStar(it) }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_player, this, true)

        backdropView = findViewById(R.id.player_backdrop)
        scrimView = findViewById(R.id.player_scrim)
        contentColumn = findViewById(R.id.player_content)
        artFrame = findViewById(R.id.player_art_frame)
        artView = findViewById(R.id.player_art)
        titleView = findViewById(R.id.player_title)
        artistView = findViewById(R.id.player_artist)
        seekBar = findViewById(R.id.player_seek)
        elapsedView = findViewById(R.id.player_elapsed)
        totalView = findViewById(R.id.player_total)
        prevButton = findViewById(R.id.player_prev)
        rewindButton = findViewById(R.id.player_rewind)
        playPauseButton = findViewById(R.id.player_play_pause)
        forwardButton = findViewById(R.id.player_forward)
        nextButton = findViewById(R.id.player_next)
        shuffleButton = findViewById(R.id.player_shuffle)
        repeatButton = findViewById(R.id.player_repeat)
        starButton = findViewById(R.id.player_star)

        styleScrim()
        styleArt()
        styleSeekBar()
        stylePlayPause()
        wireSeekBar()
        wireButtons()
        setMarquee(titleView)
    }

    // ---------------------------------------------------------------- public

    /** Start observing the shared player. Idempotent. */
    fun show() {
        if (attached) return
        attached = true
        PlayerHolder.addPlayerListener(playerListener)
        Favourites.addListener(favouritesListener)
        if (lastPlayer?.isPlaying == true) scheduleTick()
    }

    /** Stop observing. Safe to call when not attached. */
    fun hide() {
        if (attached) {
            attached = false
            PlayerHolder.removePlayerListener(playerListener)
            Favourites.removeListener(favouritesListener)
        }
        stopTick()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (attached && lastPlayer?.isPlaying == true) scheduleTick()
    }

    override fun onDetachedFromWindow() {
        hide()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- measure

    /**
     * Forces the artwork frame to a square so album art is never squashed and almost never
     * cropped. Whatever height is left over collapses and the content column re-centres
     * (gravity="center_vertical"), which keeps the screen honest in landscape and on short
     * displays. Only recomputed when the available box actually changes.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (availableWidth > 0 && availableHeight > 0) {
            squareArtTo(widthMeasureSpec, heightMeasureSpec, availableWidth, availableHeight)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun squareArtTo(
        widthSpec: Int,
        heightSpec: Int,
        availableWidth: Int,
        availableHeight: Int
    ) {
        val innerWidth = availableWidth - contentColumn.paddingLeft - contentColumn.paddingRight
        var usedElsewhere = contentColumn.paddingTop + contentColumn.paddingBottom
        for (i in 0 until contentColumn.childCount) {
            val child = contentColumn.getChildAt(i)
            if (child === artFrame) continue
            // Margins matter here — the art frame is giving up its space to its siblings.
            measureChildWithMargins(
                child,
                widthSpec,
                contentColumn.paddingLeft + contentColumn.paddingRight,
                heightSpec,
                0
            )
            usedElsewhere += child.measuredHeight
        }
        val side = minOf(innerWidth, availableHeight - usedElsewhere).coerceAtLeast(0)
        if (side == measuredSide) return
        measuredSide = side
        // Mutated in place, deliberately: assigning back to layoutParams would requestLayout()
        // from inside measure. LinearLayout reads these same fields moments later.
        val lp = artFrame.layoutParams as LinearLayout.LayoutParams
        lp.height = side
        lp.weight = 0f
    }

    // ---------------------------------------------------------------- styling

    /** Bottom-heavy scrim so text and controls stay legible over bright artwork. */
    private fun styleScrim() {
        scrimView.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x00000000, 0x33000000, 0xB3000000.toInt(), 0xF20A0A0E.toInt())
        )
    }

    /**
     * Rounded corners and a real drop shadow, without a nine-patch or an outline asset.
     * The outline is recomputed by the framework on every resize, so nothing is allocated
     * per frame.
     */
    private fun styleArt() {
        val radius = dp(ART_CORNER_DP).toFloat()
        artView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width <= 0 || view.height <= 0) return
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        artView.clipToOutline = true
        artView.elevation = dp(16).toFloat()
    }

    /** Custom track + thumb: rounded 4 dp track, accent fill, oversized grab handle. */
    private fun styleSeekBar() {
        val corner = dp(2).toFloat()

        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = corner
            setColor(0x33FFFFFF)
        }
        val progress = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = corner
            setColor(ACCENT)
        }
        seekBar.progressDrawable = LayerDrawable(arrayOf(
            background,
            ClipDrawable(progress, Gravity.START, ClipDrawable.HORIZONTAL)
        ))

        val halo = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x40FFFFFF)
        }
        val core = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        val thumb = LayerDrawable(arrayOf(halo, core)).apply {
            val outer = dp(24)
            val inner = dp(13)
            setLayerSize(0, outer, outer)
            setLayerSize(1, inner, inner)
            setLayerGravity(0, Gravity.CENTER)
            setLayerGravity(1, Gravity.CENTER)
            setBounds(0, 0, outer, outer)
        }
        seekBar.thumb = thumb
        seekBar.thumbOffset = 0
        seekBar.minimumHeight = dp(32)
    }

    /** The one filled control on the screen: white disc, dark glyph, masked ripple. */
    private fun stylePlayPause() {
        val disc = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        playPauseButton.background = RippleDrawable(
            ColorStateList.valueOf(0x33000000),
            disc,
            GradientDrawable().apply { shape = GradientDrawable.OVAL }
        )
        playPauseButton.elevation = dp(6).toFloat()
    }

    /** Rounded pill used by the mode buttons, in its three visual weights. */
    private fun pill(fill: Int, border: Int): Drawable {
        val body = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(26).toFloat()
            setColor(fill)
            if (border != 0) setStroke(dp(1), border)
        }
        return RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            body,
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
            }
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun setMarquee(view: TextView) {
        // marquee ellipsize only animates when the view is selected and focused.
        view.isSelected = true
        view.isFocusable = true
        view.isFocusableInTouchMode = true
    }

    // ---------------------------------------------------------------- render

    private fun render(player: Player) {
        lastPlayer = player

        val metadata = player.currentMediaItem?.mediaMetadata

        val title = metadata?.title?.toString().orEmpty()
        titleView.text = title.ifEmpty { NOTHING_PLAYING }

        val subtitle = listOfNotNull(
            metadata?.artist?.toString()?.takeIf { it.isNotBlank() },
            metadata?.albumTitle?.toString()?.takeIf { it.isNotBlank() }
        ).joinToString("  •  ")
        artistView.text = subtitle
        artistView.visibility = if (subtitle.isEmpty()) View.INVISIBLE else View.VISIBLE

        // Only reload art when the item actually changed — Art.load spawns a decode thread.
        val artUri = metadata?.artworkUri
        val artKey = artUri?.toString()
        if (artKey != lastArtKey) {
            lastArtKey = artKey
            if (artUri != null) {
                Art.load(context, artView, artUri, ART_TARGET_PX)
                // Second, deliberately tiny decode (~16 KB) stretched behind everything:
                // a low-res bitmap scaled up reads as a soft gradient for free.
                Art.load(context, backdropView, artUri, BACKDROP_TARGET_PX)
            } else {
                // No artwork URI on this item (e.g. media-provided artwork bytes only).
                artView.setImageDrawable(null)
                backdropView.setImageDrawable(null)
            }
        }

        renderProgress(player)
        renderTransport(player)
        renderModes(player)
        renderStar(player)

        // Keep the position fresh while audio is actually advancing.
        if (player.isPlaying) scheduleTick() else stopTick()
    }

    private fun renderProgress(player: Player) {
        val duration = player.duration
        val known = duration != C.TIME_UNSET && duration > 0L

        seekBar.isEnabled = known && !scrubbing
        if (known) {
            val max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (seekBar.max != max) seekBar.max = max
            if (!scrubbing) {
                seekBar.progress = player.currentPosition.coerceIn(0L, max.toLong()).toInt()
                elapsedView.text = formatTime(player.currentPosition)
            }
            totalView.text = formatTime(duration)
        } else {
            if (seekBar.max != 0) seekBar.max = 0
            if (!scrubbing) {
                seekBar.progress = 0
                elapsedView.text = ZERO_TIME
            }
            totalView.text = ZERO_TIME
        }
    }

    private fun renderTransport(player: Player) {
        val hasQueue = player.mediaItemCount > 0
        val playing = player.isPlaying

        playPauseButton.text = if (playing) GLYPH_PAUSE else GLYPH_PLAY
        playPauseButton.contentDescription = if (playing) "Pause" else "Play"

        setEnabled(prevButton, hasQueue && player.currentMediaItemIndex > 0)
        setEnabled(nextButton, hasQueue && player.currentMediaItemIndex < player.mediaItemCount - 1)
        setEnabled(rewindButton, hasQueue)
        setEnabled(forwardButton, hasQueue)
        setEnabled(playPauseButton, hasQueue)
    }

    /**
     * Shuffle and repeat live on the engine but had no control here. Each state gets its own
     * glyph, pill and colour so the mode is readable at a glance — on a bike you do not get
     * to squint at a grey icon.
     *
     * Updates are guarded on the previous values because the listener fires for every player
     * event, and re-setting a background/text would be wasted work (and a redundant
     * invalidate) during the 500 ms tick's event storm.
     */
    private fun renderModes(player: Player) {
        val shuffleOn = player.shuffleModeEnabled
        if (shuffleOn != lastShuffleOn) {
            lastShuffleOn = shuffleOn
            // Same glyph both ways: the amber pill is what says "on", so it reads at 20 m.
            shuffleButton.setBackground(if (shuffleOn) shuffleOnBg else shuffleIdleBg)
            shuffleButton.setTextColor(if (shuffleOn) ACCENT_TEXT else MUTED_TEXT)
            shuffleButton.contentDescription =
                if (shuffleOn) "Shuffle on, tap to turn off" else "Shuffle off, tap to turn on"
        }

        val mode = player.repeatMode
        if (mode != lastRepeatMode) {
            lastRepeatMode = mode
            when (mode) {
                Player.REPEAT_MODE_ALL -> {
                    repeatButton.text = GLYPH_REPEAT_ALL
                    repeatButton.setBackground(repeatAllBg)
                    repeatButton.setTextColor(ACCENT_TEXT)
                    repeatButton.contentDescription = "Repeating all, tap to repeat one track"
                }
                Player.REPEAT_MODE_ONE -> {
                    repeatButton.text = GLYPH_REPEAT_ONE
                    repeatButton.setBackground(repeatOneBg)
                    repeatButton.setTextColor(INK_TEXT)
                    repeatButton.contentDescription = "Repeating one track, tap to turn off"
                }
                else -> {
                    repeatButton.text = GLYPH_REPEAT_OFF
                    repeatButton.setBackground(repeatIdleBg)
                    repeatButton.setTextColor(MUTED_TEXT)
                    repeatButton.contentDescription = "Repeat off, tap to repeat all"
                }
            }
        }
    }

    private fun setEnabled(view: TextView, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    /**
     * Favourite star for the current track. Guarded on (track, liked) like the mode pills,
     * so the 500 ms tick's event storm never re-sets text or backgrounds needlessly.
     * The id doubles as the toggle target for the click handler.
     */
    private fun renderStar(player: Player) {
        val id = player.currentMediaItem?.mediaId?.toLongOrNull()
        if (id == null) {
            if (lastStarId != null || lastStarOn != null) {
                lastStarId = null
                lastStarOn = null
                starButton.text = GLYPH_STAR_OFF
                starButton.setBackground(starIdleBg)
                starButton.setTextColor(MUTED_TEXT)
                starButton.contentDescription = "No track to favourite"
            }
            return
        }
        val liked = Favourites.isFavourite(id)
        if (id != lastStarId || liked != lastStarOn) {
            lastStarId = id
            lastStarOn = liked
            starButton.text = if (liked) GLYPH_STAR_ON else GLYPH_STAR_OFF
            starButton.setBackground(if (liked) starOnBg else starIdleBg)
            starButton.setTextColor(if (liked) ACCENT_TEXT else MUTED_TEXT)
            starButton.contentDescription =
                if (liked) "Favourite, tap to remove" else "Not a favourite, tap to add"
        }
    }

    // ---------------------------------------------------------------- wiring

    private fun wireSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) elapsedView.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                scrubbing = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                scrubbing = false
                val target = (sb?.progress ?: 0).toLong()
                elapsedView.text = formatTime(target)
                PlayerHolder.whenReady { it.seekTo(target) }
            }
        })
    }

    private fun wireButtons() {
        playPauseButton.setOnClickListener {
            PlayerHolder.whenReady { player ->
                if (player.isPlaying) player.pause() else player.play()
            }
        }

        rewindButton.setOnClickListener {
            PlayerHolder.whenReady { player ->
                player.seekTo((player.currentPosition - SKIP_MS).coerceAtLeast(0L))
            }
        }

        forwardButton.setOnClickListener {
            PlayerHolder.whenReady { player ->
                val target = player.currentPosition + SKIP_MS
                val duration = player.duration
                val bounded = if (duration != C.TIME_UNSET && duration > 0L) {
                    target.coerceAtMost(duration)
                } else {
                    target
                }
                player.seekTo(bounded)
            }
        }

        prevButton.setOnClickListener {
            PlayerHolder.whenReady { player ->
                if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
            }
        }

        nextButton.setOnClickListener {
            PlayerHolder.whenReady { player ->
                if (player.hasNextMediaItem()) player.seekToNextMediaItem()
            }
        }

        shuffleButton.setOnClickListener { PlayerHolder.toggleShuffle() }

        repeatButton.setOnClickListener { PlayerHolder.cycleRepeat() }

        starButton.setOnClickListener {
            // Toggling notifies Favourites listeners, which comes straight back here
            // (favouritesListener) — so the pill flips without touching the player.
            val id = lastStarId ?: return@setOnClickListener
            Favourites.toggle(id)
        }
    }

    // ---------------------------------------------------------------- ticker

    /**
     * Player.Listener has no position callback, so an advancing seek bar needs one short
     * main-thread tick. It only runs while audio is playing and this view is attached, and
     * stops itself on pause/detach — no background work, no work when idle.
     */
    private val tick = object : Runnable {
        override fun run() {
            tickScheduled = false
            val player = lastPlayer ?: return
            if (!scrubbing) renderProgress(player)
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

    // ---------------------------------------------------------------- utils

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return ZERO_TIME
        val totalSeconds = ms / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    private companion object {
        // 512 for the square, 64 for the stretched backdrop (~16 KB). Both are subsampled by
        // MediaStore.loadThumbnail, so no full-resolution bitmap ever exists.
        const val ART_TARGET_PX = 512
        const val BACKDROP_TARGET_PX = 64
        const val ART_CORNER_DP = 20
        const val SKIP_MS = 10_000L
        const val TICK_MS = 500L
        const val DISABLED_ALPHA = 0.35f

        const val GLYPH_PLAY = "▶"
        const val GLYPH_PAUSE = "⏸"
        const val GLYPH_REPEAT_OFF = "⇄"
        const val GLYPH_REPEAT_ALL = "🔁"
        const val GLYPH_REPEAT_ONE = "🔂"
        const val GLYPH_STAR_ON = "★"
        const val GLYPH_STAR_OFF = "☆"

        // One accent, reused for the seek fill and the active mode pills, so the screen reads
        // as a single system rather than a bag of colours.
        const val ACCENT = 0xFFFFC107.toInt()
        const val ACCENT_TEXT = 0xFFFFD54F.toInt()
        const val INK_TEXT = 0xFF101014.toInt()
        const val MUTED_TEXT = 0x99FFFFFF.toInt()
        const val BORDER_IDLE = 0x1AFFFFFF

        const val ZERO_TIME = "0:00"
        const val NOTHING_PLAYING = "Nothing playing"
    }
}