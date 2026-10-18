package com.bee.chorus.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.bee.chorus.R

/**
 * Shared drawing helpers for the list rows, the tab strip and the mini player.
 *
 * The app ships no image assets and no Material Components, so anything that looks like a
 * pill, a ripple, a disc or a surface is a [GradientDrawable]/[RippleDrawable] built here —
 * once per view or once per recycled ViewHolder, never on a bind path.
 *
 * Colours resolve from theme attributes first (so both the dark theme and Cycle's light one
 * are followed automatically) and fall back to a literal only where no attribute exists.
 */
internal object Ui {

    /** Tint alpha for the selected tab's pill: a hint of accent, never a solid block. */
    private const val TINT_ALPHA = 0x26

    /** How far a "surface" is pushed away from the window background. */
    private const val DEFAULT_BLEND = 0.10f

    /** Corner radius of the tab and toggle pills, in dp. */
    private const val PILL_CORNER_DP = 18f

    // ---------------------------------------------------------------- units and colours

    /** dp -> px, fractional, from this context's display metrics. */
    fun dp(ctx: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics)

    /** dp -> px as a whole number, for sizes the framework wants as ints. */
    fun dpInt(ctx: Context, value: Int): Int = dp(ctx, value.toFloat()).toInt()

    /**
     * The app's accent, shared by the tab strip and the now-playing cue so they read as the
     * same system as the amber star and the player's seek fill.
     */
    fun accent(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.ui_accent)

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

    /** Circular [surfaceFill] — the playlist row's badge. */
    fun ovalSurface(ctx: Context, blend: Float = DEFAULT_BLEND): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(surfaceFill(ctx, blend))
        }

    /**
     * A ripple over a filled disc, for the mini player's play/pause target. The mask is a
     * second oval so the touch feedback stays circular instead of square.
     */
    fun circularTapTarget(ctx: Context): Drawable = RippleDrawable(
        rippleTint(ctx),
        ovalSurface(ctx, 0.07f),
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
     * Background for a always-visible toggle (Shuffle, Repeat): the same pill as the tab
     * strip but keyed on the selected state, so turning it on is visible at a glance.
     */
    fun toggleBackground(ctx: Context): Drawable = tabBackground(ctx)

    private fun pill(ctx: Context, fill: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, PILL_CORNER_DP)
            if (fill != Color.TRANSPARENT) setColor(fill)
        }

    /** Tab label colours: accent while selected, secondary otherwise. */
    fun tabTextColors(ctx: Context): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(
            accent(ctx),
            themeColor(ctx, android.R.attr.textColorSecondary, 0x99FFFFFF.toInt())
        )
    )
}