package com.bee.chorus.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat

/**
 * The one place Chorus reads a colour out of a theme.
 *
 * Design tokens are declared as theme attributes (`attrs.xml`) and resolved through the
 * **Activity's** theme, never through `Context.getColor()`. That distinction matters:
 * a `@color/` reference resolves by resource *configuration* (day/night), so under cycle
 * mode on a phone that happens to be in night mode it would hand back a dark value and
 * paint a bright theme dark. Going through the theme means `Theme.Chorus.Cycle`'s
 * overrides win, whatever the system UI mode is set to.
 *
 * A theme change always arrives as an Activity recreation (system uiMode, or
 * `Activity.recreate()` from the cycle toggle), so a token resolved once at inflation
 * time never needs re-resolving.
 */
object Theming {

    /**
     * [attr] resolved against [ctx]'s theme. Falls back to magenta, which is loud enough
     * that a missing token shows up immediately instead of reading as a real colour.
     */
    @ColorInt
    fun color(ctx: Context, @AttrRes attr: Int): Int {
        val out = TypedValue()
        val resolved = ctx.theme.resolveAttribute(attr, out, true) &&
            out.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT
        return if (resolved) out.data else Color.MAGENTA
    }

    /**
     * Point the status and navigation bar icons at [activity]'s window background —
     * dark icons on a light bar, light icons on a dark one.
     *
     * This has to happen in code, not in the theme: at `targetSdk 35`+ the platform
     * forces edge-to-edge and ignores `android:statusBarColor` /
     * `android:windowLightStatusBar` entirely, so a theme-only approach leaves black
     * icons on a black bar the moment the background is dark. Safe to call before or
     * after `setContentView`, as long as it is after the Activity's theme is set.
     */
    fun applySystemBars(activity: Activity) {
        val light = ColorUtils.calculateLuminance(
            color(activity, android.R.attr.colorBackground)
        ) > 0.5f
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}