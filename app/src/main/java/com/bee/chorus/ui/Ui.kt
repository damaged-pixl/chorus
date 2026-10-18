package com.bee.chorus.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import androidx.core.graphics.ColorUtils
import com.bee.chorus.R

/**
 * Shared drawing helpers for the list rows, the tab strip and the mini player.
 *
 * The app ships no image assets and no Material Components, so anything that looks like a
 * pill, a ripple, a disc or a surface is a [GradientDrawable]/[RippleDrawable] built here —
 * once per view or once per recycled ViewHolder, never on a bind path.
 *
 * Colours all come from *theme attributes*, never from a `@color` resource: a colour
 * resource resolves by configuration (day/night) rather than by theme, so it would hand
 * back the wrong value the moment the Cycle theme is active on a phone in night mode.
 * Reading them off the theme means every palette — light, dark and Cycle — is followed
 * automatically.
 */
internal object Ui {

    /** Tint alpha for the selected tab's pill: a hint of accent, never a solid block. */
    private const val TINT_ALPHA = 0x26

    /** Tint alpha for the now-playing row's background. */
    private const val ROW_TINT_ALPHA = 0x1F

    /** How far a "surface" is pushed away from the window background. */
    private const val DEFAULT_BLEND = 0.10f

    /** Corner radius of the tab and toggle pills, in dp. */
    private const val PILL_CORNER_DP = 18f

    // ---------------------------------------------------------------- units and colours

    /** dp -> px, fractional, from this context's display metrics. */
    fun dp(ctx: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics)

    /**
     * The app's accent, for *fills* — the selected tab's pill, its underline, the
     * now-playing row. Read from `colorAccent`, which the app theme points at the accent
     * colour, so it tracks whichever palette is active.
     *
     * Not for text: the accent is a fill colour, and in the light palette it is far too
     * light to read as small type on a near-white page. Labels use text colours instead.
     */
    fun accent(ctx: Context): Int = themeColor(ctx, android.R.attr.colorAccent, FALLBACK_ACCENT)

    /** Resolve a theme colour attribute, falling back when the theme does not define it. */
    fun themeColor(ctx: Context, attr: Int, fallback: Int): Int {
        val out = TypedValue()
        val resolved = ctx.theme.resolveAttribute(attr, out, true) &&
            out.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT
        return if (resolved) out.data else fallback
    }

    /** A translucent version of a theme colour — hairlines and quiet separators. */
    fun faintColor(ctx: Context, attr: Int, alpha: Int): Int =
        ColorUtils.setAlphaComponent(themeColor(ctx, attr, Color.BLACK), alpha)

    /**
     * The framework's own translucent press tint for this theme (a low-alpha version of the
     * accent), so ripples never need a colour of their own.
     */
    fun rippleTint(ctx: Context): ColorStateList =
        ColorStateList.valueOf(themeColor(ctx, android.R.attr.colorControlHighlight, 0x33FFFFFF))

    // ---------------------------------------------------------------- surfaces

    /**
     * A flat surface a hair off the window background: lighter in the dark theme, darker in
     * Cycle's light one. Backdrop for artwork placeholders and the mini player bar.
     */
    fun surfaceFill(ctx: Context, blend: Float = DEFAULT_BLEND): Int {
        val base = themeColor(ctx, android.R.attr.colorBackground, Color.BLACK)
        return if (ColorUtils.calculateLuminance(base) > 0.5f) {
            ColorUtils.blendARGB(base, Color.BLACK, blend)
        } else {
            ColorUtils.blendARGB(base, Color.WHITE, blend)
        }
    }

    /** Rounded [surfaceFill] — artwork placeholders, which clip their bitmap to the outline. */
    fun roundedSurface(ctx: Context, cornerDp: Float, blend: Float = DEFAULT_BLEND): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, cornerDp)
            setColor(surfaceFill(ctx, blend))
        }

    /**
     * A ripple over a flat fill: the press feedback for a list row. With [cue] set the fill
     * is a faint accent tint, which is how the now-playing row is marked — two of these are
     * built once per recycled holder and swapped on bind, which is cheaper than mutating a
     * drawable and avoids a relayout on every state change.
     */
    fun rowBackground(ctx: Context, cue: Boolean): Drawable = RippleDrawable(
        rippleTint(ctx),
        ColorDrawable(
            if (cue) ColorUtils.setAlphaComponent(accent(ctx), ROW_TINT_ALPHA) else Color.TRANSPARENT
        ),
        null
    )

    /** A solid accent bar, for the now-playing cue down a row's leading edge. */
    fun accentBar(ctx: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(accent(ctx))
    }

    /**
     * A ripple over a filled disc, for the mini player's play/pause target. The mask is a
     * second oval so the touch feedback stays circular instead of square.
     */
    fun circularTapTarget(ctx: Context): Drawable = RippleDrawable(
        rippleTint(ctx),
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(surfaceFill(ctx, 0.07f))
        },
        GradientDrawable().apply { shape = GradientDrawable.OVAL }
    )

    // ---------------------------------------------------------------- tab strip

    /**
     * Tab background: a tinted accent pill while selected, clear otherwise, with the ripple
     * masked to the pill so the press feedback follows the rounded shape. Built once per tab;
     * the selection state then drives the drawable with no per-click work.
     */
    fun tabBackground(ctx: Context): Drawable {
        val content = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_selected),
                pill(ctx, ColorUtils.setAlphaComponent(accent(ctx), TINT_ALPHA))
            )
            addState(intArrayOf(), pill(ctx, Color.TRANSPARENT))
        }
        return RippleDrawable(rippleTint(ctx), content, pill(ctx, Color.TRANSPARENT))
    }

    /**
     * Background for an always-visible toggle (Shuffle, Repeat): the same pill as the tab
     * strip but keyed on the selected state, so turning it on is visible at a glance.
     */
    fun toggleBackground(ctx: Context): Drawable = tabBackground(ctx)

    private fun pill(ctx: Context, fill: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, PILL_CORNER_DP)
            if (fill != Color.TRANSPARENT) setColor(fill)
        }

    /**
     * Tab label colours: full-strength primary text when selected, secondary otherwise. The
     * pill and the underline carry the accent — the accent colour is too light to use as
     * small type on a light page, so the label never takes it.
     */
    fun tabTextColors(ctx: Context): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(
            themeColor(ctx, android.R.attr.textColorPrimary, Color.WHITE),
            themeColor(ctx, android.R.attr.textColorSecondary, 0x99FFFFFF.toInt())
        )
    )

    /**
     * Only ever seen by a build that has no app theme mapped onto `colorAccent`; the real
     * value always comes from the theme.
     */
    private const val FALLBACK_ACCENT = 0xFFFFC107.toInt()
}

