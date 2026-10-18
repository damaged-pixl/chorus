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
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.media3.common.C
import androidx.media3.common.Player
import com.bee.chorus.R
import com.bee.chorus.data.Favourites
import com.bee.chorus.data.Settings
import com.bee.chorus.playback.PlayerHolder
import com.bee.chorus.playback.SleepTimer
import java.util.Locale

/**
 * Full-screen player panel. Pure view over the shared [PlayerHolder] controller — it owns no
 * playback state of its own, so the mini player and this panel can never disagree.
 *
 * Lifecycle: call [show] to start observing and [hide] (or let the view detach) to stop.
 * The [PlayerHolder] listener is removed on detach so a finished screen leaves no callbacks.
 *
 * Everything visual here is drawn from code: no image assets, no fonts, no Material. Colours
 * come from the *theme*'s player tokens (see [Theming]), never from a `@color` resource, so
 * the screen follows the light, dark and Cycle palettes on its own. The one deliberate
 * constant left is [ART_EDGE], the hairline around the artwork card — the card's own fill is
 * the chorusPlayerArtCard token.
 *
 * The artwork itself is decoded twice by [Art] — once at display size for the square, once at
 * 64 px for the stretched backdrop — which is what keeps the screen cheap in RAM.
 */
class PlayerPanel @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    private val backdropView: ImageView
    private val scrimView: View
    private val contentColumn: LinearLayout
    private val artCard: View
    private val artView: ImageView
    private val titleView: TextView
    private val artistView: TextView
    private val seekBar: SeekBar
    private val previewView: TextView
    private val elapsedView: TextView
    private val totalView: TextView
    private val cycleButton: TextView
    private val sleepButton: TextView
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
    // owned by exactly one view — a Drawable must never be shared between two views, and each
    // pill therefore gets its own idle/on pair even where the two look identical.
    // Assigned in init (not as field initialisers) because they need the Activity theme,
    // which is only final once onCreate has chosen it.
    private val shuffleIdleBg: Drawable
    private val shuffleOnBg: Drawable
    private val repeatIdleBg: Drawable
    private val repeatAllBg: Drawable
    private val repeatOneBg: Drawable
    private val starIdleBg: Drawable
    private val starOnBg: Drawable
    private val sleepIdleBg: Drawable
    private val sleepOnBg: Drawable
    private val cycleIdleBg: Drawable
    private val cycleOnBg: Drawable

    // Design tokens, resolved once per inflation. A theme change always arrives as an
    // Activity recreation (system uiMode, or recreate() from the cycle toggle), so these
    // never need re-resolving — see Theming for why they come from the theme, not a colour.
    private val accent: Int
    private val accentText: Int
    private val mutedText: Int
    private val onStrongPillText: Int

    private var attached = false
    private var scrubbing = false
    private var previewShown = false
    private var lastArtKey: String? = null
    private var lastPlayer: Player? = null
    private var tickScheduled = false
    private var lastShuffleOn: Boolean? = null
    private var lastRepeatMode = -1
    private var lastStarId: Long? = null
    private var lastStarOn: Boolean? = null
    private var lastCycleOn: Boolean? = null
    private var lastSleepArmed = false
    private var measuredSide = -1

    // Held as a property so the *same* instance can be handed back to removePlayerListener,
    // which removes by identity.
    private val playerListener: (Player) -> Unit = { player -> render(player) }

    // Same identity trick for the Favourites listener: re-render only the star, since a
    // favourite can change (from the track list) without any player event at all.
    private val favouritesListener: (Long, Boolean) -> Unit = { _, _ ->
        lastPlayer?.let { renderStar(it) }
    }

    // While this screen observes, SleepTimer refreshes its countdown once a second and
    // this redraws only the label. With no listener registered there is no periodic tick
    // at all, which is why register/unregister are part of show()/hide().
    private val sleepListener: (SleepTimer) -> Unit = { renderSleep() }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_player, this, true)

        backdropView = findViewById(R.id.player_backdrop)
        scrimView = findViewById(R.id.player_scrim)
        contentColumn = findViewById(R.id.player_content)
        artCard = findViewById(R.id.player_art_card)
        artView = findViewById(R.id.player_art)
        titleView = findViewById(R.id.player_title)
        artistView = findViewById(R.id.player_artist)
        seekBar = findViewById(R.id.player_seek)
        previewView = findViewById(R.id.player_seek_preview)
        elapsedView = findViewById(R.id.player_elapsed)
        totalView = findViewById(R.id.player_total)
        cycleButton = findViewById(R.id.player_cycle)
        sleepButton = findViewById(R.id.player_sleep)
        prevButton = findViewById(R.id.player_prev)
        rewindButton = findViewById(R.id.player_rewind)
        playPauseButton = findViewById(R.id.player_play_pause)
        forwardButton = findViewById(R.id.player_forward)
        nextButton = findViewById(R.id.player_next)
        shuffleButton = findViewById(R.id.player_shuffle)
        repeatButton = findViewById(R.id.player_repeat)
        starButton = findViewById(R.id.player_star)

        // Tokens first: the styling pass below builds everything from them.
        accent = Theming.color(context, R.attr.chorusAccent)
        accentText = Theming.color(context, R.attr.chorusPlayerOnPillText)
        mutedText = Theming.color(context, R.attr.chorusPlayerTextMuted)
        onStrongPillText = Theming.color(context, R.attr.chorusPlayerOnStrongPillText)

        val pillIdle = Theming.color(context, R.attr.chorusPlayerPill)
        val pillBorder = Theming.color(context, R.attr.chorusPlayerPillBorder)
        val pillOn = Theming.color(context, R.attr.chorusPlayerPillOn)
        val pillStrong = Theming.color(context, R.attr.chorusPlayerPillStrong)
        shuffleIdleBg = pill(pillIdle, pillBorder)
        shuffleOnBg = pill(pillOn, 0)
        repeatIdleBg = pill(pillIdle, pillBorder)
        repeatAllBg = pill(pillOn, 0)
        repeatOneBg = pill(pillStrong, 0)
        starIdleBg = pill(pillIdle, pillBorder)
        starOnBg = pill(pillOn, 0)
        sleepIdleBg = pill(pillIdle, pillBorder)
        sleepOnBg = pill(pillOn, 0)
        cycleIdleBg = pill(pillIdle, pillBorder)
        cycleOnBg = pill(pillStrong, 0)

        styleBackdrop()
        styleArtCard()
        styleSeekBar()
        styleTransport()
        styleModes()
        wireSeekBar()
        wireButtons()
        setMarquee(titleView)
        renderCycle()
    }

    // ---------------------------------------------------------------- public

    /** Start observing the shared player. Idempotent. */
    fun show() {
        if (attached) return
        attached = true
        PlayerHolder.addPlayerListener(playerListener)
        Favourites.addListener(favouritesListener)
        SleepTimer.addListener(sleepListener)
        renderSleep()   // pick up a timer armed before this screen existed
        if (lastPlayer?.isPlaying == true) scheduleTick()
    }

    /** Stop observing. Safe to call when not attached. */
    fun hide() {
        if (attached) {
            attached = false
            PlayerHolder.removePlayerListener(playerListener)
            Favourites.removeListener(favouritesListener)
            SleepTimer.removeListener(sleepListener)
        }
        stopTick()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A fade interrupted by a detach can leave the art part-way down.
        if (artView.alpha < 1f) artView.alpha = 1f
        if (attached && lastPlayer?.isPlaying == true) scheduleTick()
    }

    override fun onDetachedFromWindow() {
        hide()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- measure

    /**
     * Forces the artwork card to a square so album art is never squashed and almost never
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
            if (child === artCard) continue
            // Margins matter here — the art card is giving up its space to its siblings.
            measureChildWithMargins(
                child,
                widthSpec,
                contentColumn.paddingLeft + contentColumn.paddingRight,
                heightSpec,
                0
            )
            usedElsewhere += child.measuredHeight
        }
        // No art at all beats a 20dp sliver: when the fixed rows take the whole height (a
        // landscape phone) the art column collapses and the controls stay on screen.
        val spare = minOf(innerWidth, availableHeight - usedElsewhere)
        val side = if (spare >= dp(MIN_ART_DP)) spare else 0
        if (side == measuredSide) return
        measuredSide = side
        // Mutated in place, deliberately: assigning back to layoutParams would requestLayout()
        // from inside measure. LinearLayout reads these same fields moments later.
        val lp = artCard.layoutParams as LinearLayout.LayoutParams
        lp.height = side
        lp.weight = 0f
    }

    // ---------------------------------------------------------------- styling

    /**
     * The page: a soft wash of the current artwork, faded back hard under a bottom-heavy
     * gradient so text and controls stay legible over a bright sleeve. The ramp is a
     * *themed* one (chorusPlayerScrim*), not a fixed black: light and Cycle fade to
     * near-white so the dark player text reads, dark keeps the fade to near-black — which is
     * what lets one layout read in all three palettes instead of a scrim tuned for one.
     */
    private fun styleBackdrop() {
        backdropView.alpha = BACKDROP_ALPHA
        scrimView.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.TRANSPARENT,
                Theming.color(context, R.attr.chorusPlayerScrimMid),
                Theming.color(context, R.attr.chorusPlayerScrimLow),
                Theming.color(context, R.attr.chorusPlayerScrimEnd),
            )
        )
    }

    /**
     * The hero: a mat with rounded corners and a real drop shadow, under the artwork. The mat
     * is what stops a pale sleeve dissolving into a pale page, and the shadow is what makes
     * the card read as an object rather than a rectangle of colour. The outline is recomputed
     * by the framework on every resize, so nothing is allocated per frame.
     *
     * In the light and Cycle themes the token is a near-black card, because a pale sleeve on
     * a pale page is otherwise unreadable; in the dark theme it is transparent and nothing is
     * filled, which keeps the art floating over the backdrop.
     */
    private fun styleArtCard() {
        val radius = Ui.dp(context, ART_CORNER_DP)
        artCard.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Theming.color(context, R.attr.chorusPlayerArtCard))
            // A hairline of light, so the card's edge reads on a dark page too. Still a
            // literal — see ART_EDGE for why there is no token for it yet.
            setStroke(dp(1), ART_EDGE)
        }
        artCard.elevation = dp(16).toFloat()
        artView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width <= 0 || view.height <= 0) return
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        artView.clipToOutline = true
    }

    /**
     * Custom track, buffered band and thumb. Colours are read off the theme and baked into
     * the layers — the same route the progress fill already took — so the framework's
     * secondary progress slot (which nothing used to set) now draws the buffer as well.
     */
    private fun styleSeekBar() {
        val corner = Ui.dp(context, TRACK_CORNER_DP)
        // The buffered band sits between the track and the fill, so it reads as a softer
        // version of the secondary ink rather than as a third full-strength colour.
        val secondary = Theming.color(context, R.attr.chorusPlayerTextSecondary)

        fun layer(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = corner
            setColor(color)
        }
        seekBar.progressDrawable = LayerDrawable(
            arrayOf(
                layer(Theming.color(context, R.attr.chorusPlayerSeekTrack)),
                ClipDrawable(
                    layer(ColorUtils.setAlphaComponent(secondary, BUFFER_ALPHA)),
                    Gravity.START,
                    ClipDrawable.HORIZONTAL
                ),
                ClipDrawable(layer(accent), Gravity.START, ClipDrawable.HORIZONTAL),
            )
        )

        val halo = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Theming.color(context, R.attr.chorusPlayerSeekHalo))
        }
        val core = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Theming.color(context, R.attr.chorusPlayerSeekThumb))
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

        // The scrub preview: solid accent pill, so the position reads over any artwork. Its glyph
        // is inked off the accent's own luminance, because the accent stays amber in every
        // palette (unlike the "on" pill fills) and neither a fixed white nor a fixed black is
        // legible on it everywhere.
        previewView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = Ui.dp(context, PREVIEW_CORNER_DP)
            setColor(accent)
        }
        previewView.setTextColor(inkOn(accent))
        previewView.elevation = dp(6).toFloat()
    }

    /**
     * The transport row: the one filled control on the screen is play/pause, in the middle,
     * with framework ripple targets either side of it. The disc inverts with the theme —
     * white with a dark glyph in dark mode, near-black with a white glyph in light and Cycle —
     * so it stays the highest-contrast target on the page either way, and in sunlight the
     * Cycle theme's black disc is the one that reads.
     */
    private fun styleTransport() {
        val disc = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Theming.color(context, R.attr.chorusPlayerSurface))
        }
        playPauseButton.background = RippleDrawable(
            ColorStateList.valueOf(Theming.color(context, R.attr.chorusPlayerRipple)),
            disc,
            GradientDrawable().apply { shape = GradientDrawable.OVAL }
        )
        playPauseButton.setTextColor(Theming.color(context, R.attr.chorusPlayerOnSurface))
        playPauseButton.elevation = dp(6).toFloat()
    }

    /**
     * The four mode pills, in their idle weight before anything is playing: a faintly filled
     * pill with a hairline border, so the target is visible on the page rather than an
     * invisible patch of glyph, and muted labels. The "on" weights are swapped in by
     * [renderModes], [renderStar] and [renderSleep], one at a time.
     */
    private fun styleModes() {
        shuffleButton.background = shuffleIdleBg
        repeatButton.background = repeatIdleBg
        starButton.background = starIdleBg
        sleepButton.background = sleepIdleBg
        for (view in listOf(shuffleButton, repeatButton, starButton, sleepButton)) {
            view.setTextColor(mutedText)
        }
    }

    /**
     * Rounded pill used by the mode buttons and the cycle control, in three visual weights:
     * idle (fill + border), on (accent tint) and strong (solid, for repeat-one and cycle mode
     * — amber on amber is unreadable in sun, so those go black there). The mask keeps the
     * ripple inside the rounded shape.
     */
    private fun pill(fill: Int, border: Int): Drawable {
        val corner = Ui.dp(context, PILL_CORNER_DP)
        val body = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = corner
            setColor(fill)
            if (border != 0) setStroke(dp(1), border)
        }
        return RippleDrawable(
            ColorStateList.valueOf(Theming.color(context, R.attr.chorusPlayerRipple)),
            body,
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = corner
            }
        )
    }

    /**
     * Ink or paper for a glyph sitting on [fill], decided by the fill's own luminance. The one
     * use is the scrub preview, which is a solid accent in every palette: neither a fixed
     * white nor a fixed black is legible on amber everywhere.
     */
    private fun inkOn(fill: Int): Int =
        if (ColorUtils.calculateLuminance(fill) > LUMINANCE_PIVOT) INK else Color.WHITE

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
            fadeInArt()
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

        // Never disable the bar mid-drag: that would cancel tracking and commit the seek to
        // whatever the thumb happened to be over.
        seekBar.isEnabled = known
        if (known) {
            val max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (seekBar.max != max) seekBar.max = max
            val buffered = player.bufferedPosition.coerceIn(0L, max.toLong()).toInt()
            if (seekBar.secondaryProgress != buffered) seekBar.secondaryProgress = buffered
            if (!scrubbing) {
                seekBar.progress = player.currentPosition.coerceIn(0L, max.toLong()).toInt()
                elapsedView.text = formatTime(player.currentPosition)
            }
            // The duration only changes on a track change; not every tick's business.
            val total = formatTime(duration)
            if (totalView.text != total) totalView.text = total
        } else {
            if (seekBar.max != 0) seekBar.max = 0
            if (seekBar.secondaryProgress != 0) seekBar.secondaryProgress = 0
            if (!scrubbing) {
                seekBar.progress = 0
                elapsedView.text = ZERO_TIME
            }
            if (totalView.text != ZERO_TIME) totalView.text = ZERO_TIME
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
     * Shuffle and repeat live on the engine but had no control here. The pill weight and the
     * label colour carry the whole signal — an accent pill for "on", a stronger one for
     * repeat-one, muted ink for off — so the mode is readable at a glance, on a bike you do
     * not get to squint at a grey icon.
     *
     * Updates are guarded on the previous values because the listener fires for every player
     * event, and re-setting state would be wasted work (and a redundant invalidate) during the
     * 500 ms tick's event storm.
     */
    private fun renderModes(player: Player) {
        val shuffleOn = player.shuffleModeEnabled
        if (shuffleOn != lastShuffleOn) {
            lastShuffleOn = shuffleOn
            // Same glyph both ways: the filled pill is what says "on", so it reads at 20 m
            // (amber on the normal themes, solid black in cycle mode — see the tokens).
            shuffleButton.setBackground(if (shuffleOn) shuffleOnBg else shuffleIdleBg)
            shuffleButton.setTextColor(if (shuffleOn) accentText else mutedText)
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
                    repeatButton.setTextColor(accentText)
                    repeatButton.contentDescription = "Repeating all, tap to repeat one track"
                }
                Player.REPEAT_MODE_ONE -> {
                    repeatButton.text = GLYPH_REPEAT_ONE
                    // Stronger than "all": one track repeating is the mode worth noticing.
                    repeatButton.setBackground(repeatOneBg)
                    repeatButton.setTextColor(onStrongPillText)
                    repeatButton.contentDescription = "Repeating one track, tap to turn off"
                }
                else -> {
                    repeatButton.text = GLYPH_REPEAT_OFF
                    repeatButton.setBackground(repeatIdleBg)
                    repeatButton.setTextColor(mutedText)
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
     * Favourite star for the current track. Guarded on (track, liked) like the mode pills, so
     * the 500 ms tick's event storm never re-sets text or state needlessly.
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
                starButton.setTextColor(mutedText)
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
            starButton.setTextColor(if (liked) accentText else mutedText)
            starButton.contentDescription =
                if (liked) "Favourite, tap to remove" else "Not a favourite, tap to add"
        }
    }

    /**
     * Sleep-timer pill: a bare clock glyph in the idle weight when off, the live countdown (or
     * "⏱ End of track") in the accent weight when armed. Only the armed/off transition touches
     * the pill and the label ink; the countdown text itself is rewritten every tick. Called
     * once in [show] and then once per SleepTimer tick/change via [sleepListener].
     */
    private fun renderSleep() {
        val mode = SleepTimer.mode()
        val armed = mode != SleepTimer.Mode.OFF
        if (armed != lastSleepArmed) {
            lastSleepArmed = armed
            sleepButton.setBackground(if (armed) sleepOnBg else sleepIdleBg)
            sleepButton.setTextColor(if (armed) accentText else mutedText)
            sleepButton.contentDescription = when (mode) {
                SleepTimer.Mode.OFF -> "Sleep timer off, tap to set"
                SleepTimer.Mode.COUNTDOWN ->
                    "Sleep timer running, tap to change or cancel"
                SleepTimer.Mode.END_OF_TRACK ->
                    "Sleeping at end of track, tap to change or cancel"
            }
        }
        // The countdown text is rewritten every tick — only its state is worth guarding.
        sleepButton.text = when (mode) {
            SleepTimer.Mode.OFF -> GLYPH_SLEEP
            SleepTimer.Mode.COUNTDOWN -> GLYPH_SLEEP + " " + formatTime(SleepTimer.remainingMs())
            SleepTimer.Mode.END_OF_TRACK -> GLYPH_SLEEP + " " + END_OF_TRACK_LABEL
        }
    }

    /**
     * Cycle-mode sun in the centre of the time row — the slot the sleep control left when it
     * moved into the modes row. Cycle mode is the one manual appearance override left in the
     * app (the rest is the system UI mode), and it is reachable here as well as on the Setup
     * tab.
     *
     * Tapping only persists: the host Activity listens for the change and calls
     * `recreate()`, which re-inflates this panel under the new theme. So there is nothing
     * to update in place — the guard just avoids re-styling on every render.
     */
    private fun renderCycle() {
        val on = Settings.isCycleMode(context)
        if (on == lastCycleOn) return
        lastCycleOn = on
        cycleButton.setBackground(if (on) cycleOnBg else cycleIdleBg)
        cycleButton.setTextColor(if (on) onStrongPillText else mutedText)
        cycleButton.contentDescription =
            if (on) "Cycle mode on, tap to turn off" else "Cycle mode off, tap to turn on"
    }

    /** Options sheet: cancel when running, then the four timed presets and end-of-track. */
    private fun sleepDialog() {
        val running = SleepTimer.mode() != SleepTimer.Mode.OFF
        val options = ArrayList<String>(6)
        if (running) options.add("Cancel sleep timer")
        options.add("15 minutes")
        options.add("30 minutes")
        options.add("45 minutes")
        options.add("60 minutes")
        options.add("End of track")

        val title = if (SleepTimer.mode() == SleepTimer.Mode.COUNTDOWN) {
            "Sleep timer — ${formatTime(SleepTimer.remainingMs())} left"
        } else {
            "Sleep timer"
        }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(options.toTypedArray()) { _, which ->
                val picked = if (running) which - 1 else which
                when (picked) {
                    -1 -> SleepTimer.cancel()
                    0 -> SleepTimer.startCountdown(15)
                    1 -> SleepTimer.startCountdown(30)
                    2 -> SleepTimer.startCountdown(45)
                    3 -> SleepTimer.startCountdown(60)
                    4 -> SleepTimer.startEndOfTrack()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------------------------------------------------------------- wiring

    /**
     * Drag handling. While the finger is down the 500 ms tick is stood down ([scrubbing]), so
     * the bar tracks the thumb exactly; on release the position is committed to the shared
     * player and the tick picks the bar up again.
     */
    private fun wireSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                elapsedView.text = formatTime(progress.toLong())
                showPreview(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                scrubbing = true
                showPreview(sb?.progress ?: 0)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                scrubbing = false
                hidePreview()
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

        cycleButton.setOnClickListener {
            // Persist only. Settings listeners run synchronously, and PlayerActivity's
            // listener calls recreate(), which re-inflates this panel on the new theme.
            Settings.setCycleMode(context, !Settings.isCycleMode(context))
        }

        sleepButton.setOnClickListener { sleepDialog() }
    }

    // ---------------------------------------------------------------- scrub preview

    /**
     * The timestamp the drag would land on, floating above the thumb. The view is always laid
     * out (alpha 0 rather than GONE) so its width is known before the first drag and the pill
     * lands in the right place on the first frame instead of the second.
     */
    private fun showPreview(progress: Int) {
        previewView.text = formatTime(progress.toLong())
        movePreview(progress)
        if (!previewShown) {
            previewShown = true
            previewView.animate().alpha(1f).setDuration(FADE_MS).start()
        }
    }

    private fun hidePreview() {
        if (!previewShown) return
        previewShown = false
        previewView.animate().alpha(0f).setDuration(FADE_MS).start()
    }

    /**
     * Follows the thumb. A SeekBar does not expose its thumb position, so the bar's own box is
     * the honest approximation of its travel; the pill is clamped inside the row either way.
     */
    private fun movePreview(progress: Int) {
        val max = seekBar.max
        val bar = seekBar.width
        if (max <= 0 || bar <= 0) return
        val travel = seekBar.left + progress.toFloat() / max * bar
        val host = (previewView.parent as? View)?.width ?: 0
        val slack = (host - previewView.width).toFloat().coerceAtLeast(0f)
        previewView.translationX = (travel - previewView.width / 2f).coerceIn(0f, slack)
    }

    /** A short alpha fade on each artwork change — cheap, and it hides the decode pop. */
    private fun fadeInArt() {
        artView.animate().cancel()
        artView.alpha = 0f
        artView.animate().alpha(1f).setDuration(FADE_MS).start()
    }

    // ---------------------------------------------------------------- ticker

    /**
     * Player.Listener has no position callback, so an advancing seek bar needs one short
     * main-thread tick. It only runs while audio is playing and this view is attached, skips
     * the position entirely while a drag is in progress, and stops itself on pause/detach — no
     * background work, no work when idle.
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

        /**
         * The hairline around the artwork card, so its edge reads on a dark page too. Still a
         * literal: the card's *fill* is the `chorusPlayerArtCard` token, but there is no token
         * for this edge yet — one is needed before it can follow the palette. (Phase C's other
         * literal, ART_MAT, is gone: that fill is the token now.)
         */
        const val ART_EDGE = 0x1AFFFFFF
        const val ART_CORNER_DP = 22f
        const val MIN_ART_DP = 96

        /** How far the artwork wash is faded back — the scrim supplies the legibility. */
        const val BACKDROP_ALPHA = 0.35f

        const val TRACK_CORNER_DP = 2f
        /** How much of the secondary ink the buffered band is drawn at. */
        const val BUFFER_ALPHA = 0x73

        const val PILL_CORNER_DP = 18f
        const val PREVIEW_CORNER_DP = 14f

        const val LUMINANCE_PIVOT = 0.5f
        const val INK = 0xFF101014.toInt()

        const val SKIP_MS = 10_000L
        const val TICK_MS = 500L
        const val FADE_MS = 180L
        const val DISABLED_ALPHA = 0.35f

        const val GLYPH_PLAY = "▶"
        const val GLYPH_PAUSE = "⏸"
        const val GLYPH_REPEAT_OFF = "⇄"
        const val GLYPH_REPEAT_ALL = "🔁"
        const val GLYPH_REPEAT_ONE = "🔂"
        const val GLYPH_STAR_ON = "★"
        const val GLYPH_STAR_OFF = "☆"
        const val GLYPH_SLEEP = "⏱"
        const val END_OF_TRACK_LABEL = "End of track"

        const val ZERO_TIME = "0:00"
        const val NOTHING_PLAYING = "Nothing playing"
    }
}