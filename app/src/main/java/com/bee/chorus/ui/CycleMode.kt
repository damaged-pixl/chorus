package com.bee.chorus.ui

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bee.chorus.R
import java.util.WeakHashMap

/**
 * Cycle mode: bigger touch targets and larger text for use with gloved hands, without
 * editing every layout. Applied at runtime over an already-inflated hierarchy.
 *
 * Two halves:
 *  - [apply] walks a live view tree and enlarges touch targets / text in place.
 *  - [applyTheme] swaps the Activity theme for the high-contrast
 *    `Theme.Chorus.Cycle` (defined in `themes.xml` in both the light and -night sets:
 *    cycle mode is contrast and target size, never a light/dark preference).
 *
 * Because the theme must be chosen before the views exist, the usual host flow is:
 * call [applyTheme] early in `onCreate`, `setContentView`, then call [apply] on the
 * content root. Turning the setting *off* is best handled by `Activity.recreate()` so
 * the normal layout is re-inflated at its original sizes — [apply] also accepts
 * `enabled = false` and restores what it changed, for a same-instance fallback.
 *
 * All functions are UI-thread only and hold no references to views that outlive them
 * (see [originals]).
 */
object CycleMode {

    /** Minimum width/height, in dp, for any interactive view in cycle mode. Normal is ~48dp. */
    const val BIG_BUTTON_DP = 72

    /** Text multiplier for clickable/focusable controls — the gloved-finger targets. */
    private const val TEXT_SCALE = 1.5f

    /** Text multiplier for everything else (list titles, durations, captions). */
    private const val ROOT_TEXT_SCALE = 1.25f

    /**
     * Pre-cycle-mode state per view, so a repeated [apply] does not compound the scaling and
     * `enabled = false` restores exactly. Weak keys and key-free values keep this leak-free.
     * Main thread only.
     */
    private val originals = WeakHashMap<View, Original>()

    private class Original(
        val minWidth: Int,
        val minHeight: Int,
        val textSizePx: Float,
    )

    // ------------------------------------------------------------------ touch targets

    /**
     * Walk [root]'s hierarchy and, when [enabled], give every interactive view a
     * [BIG_BUTTON_DP] touch target and larger text; non-interactive text gets a modest bump.
     *
     * Views that are already larger than the target are left alone, so an intentionally
     * oversized primary control is never shrunk.
     *
     * Pass `enabled = false` to undo a previous [apply] on the same instance. The host
     * normally recreates the Activity instead, which re-inflates everything at normal sizes.
     *
     * Limitation: only views that exist at call time are reached. For scrolling content, call
     * this from the adapter's bind (on `holder.itemView`) so newly inflated rows match —
     * rows recycled from the pool are already resized from when they were first bound.
     */
    fun apply(root: View, enabled: Boolean) {
        val bigPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            BIG_BUTTON_DP.toFloat(),
            root.resources.displayMetrics,
        )
        walk(root, enabled, bigPx)
    }

    private fun walk(view: View, enabled: Boolean, bigPx: Float) {
        // The tab strip is already sized for gloves; scaling its labels only truncates
        // them ("TRAC..."), so bump the targets but leave that text alone.
        val inTabStrip = isInTabStrip(view)

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walk(view.getChildAt(i), enabled, bigPx)
            }
        }

        // Buttons/rows you press with a glove: full size bump. Everything else: a modest bump.
        val interactive = view.isClickable || view.isFocusable || view is ImageButton

        if (enabled) {
            val before = remember(view)
            if (interactive) {
                if (view.minimumWidth < bigPx) view.minimumWidth = bigPx.toInt()
                if (view.minimumHeight < bigPx) view.minimumHeight = bigPx.toInt()
                if (!inTabStrip) scaleText(view, before, TEXT_SCALE)
            } else {
                if (!inTabStrip) scaleText(view, before, ROOT_TEXT_SCALE)
            }
        } else {
            val before = originals.remove(view) ?: return
            view.minimumWidth = before.minWidth
            view.minimumHeight = before.minHeight
            if (view is TextView) {
                // PX unit throughout so the view's own scaledDensity factor is never applied twice.
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, before.textSizePx)
            }
        }
    }

    private fun isInTabStrip(view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v.id == R.id.tab_strip) return true
            v = v.parent as? View
        }
        return false
    }

    private fun scaleText(view: View, before: Original?, scale: Float) {
        if (view !is TextView) return
        val basePx = before?.textSizePx ?: view.textSize
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, basePx * scale)
    }

    /** Capture the pre-cycle-mode state once; repeat calls return the same original. */
    private fun remember(view: View): Original? = originals[view] ?: Original(
        view.minimumWidth,
        view.minimumHeight,
        if (view is TextView) view.textSize else 0f,
    ).also { originals[view] = it }

    // ------------------------------------------------------------------ theme

    /**
     * Point [activity] at the cycle theme when [enabled], otherwise back to the normal one,
     * then fix the system-bar icon contrast for whichever of the two is now active.
     *
     * **Call order matters:** `setTheme` must run before the Activity inflates anything —
     * before `super.onCreate()` ideally, and certainly before `setContentView()`. Calling it
     * afterwards leaves already-inflated views on the old theme. Toggling therefore requires
     * `Activity.recreate()` to take visible effect.
     *
     * Both activities go through here, so the full-screen player picks up the cycle theme
     * exactly as MainActivity does (including the system-bar icons, which
     * [Theming.applySystemBars] sets from whichever palette is now active).
     */
    fun applyTheme(activity: AppCompatActivity, enabled: Boolean) {
        activity.setTheme(if (enabled) R.style.Theme_Chorus_Cycle else R.style.Theme_Chorus)

        // Not conditional on `enabled` any more: the normal theme is DayNight, so the
        // system-bar icons have to follow whichever side of it we are on. See
        // Theming.applySystemBars for why this cannot be a theme attribute at targetSdk 35+.
        Theming.applySystemBars(activity)
    }
}
