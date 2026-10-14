package com.bee.chorus.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
import com.bee.chorus.R
import com.bee.chorus.data.Settings

/**
 * Settings row that flips [Settings.isCycleMode].
 *
 * Inflated from `R.layout.view_cycle_toggle`; declare it straight into a layout with
 * `<com.bee.chorus.ui.CycleToggle ... />` or add it programmatically.
 *
 * Turning cycle mode on changes the Activity *theme* as well as view sizes, which can only be
 * done before the views exist. So this view does **not** re-theme in place: it persists the
 * value (firing any `Settings` listeners) and then calls [onChanged]. The host is expected to
 * `recreate()` there, which re-runs `onCreate` with [CycleMode.applyTheme] picking the new theme.
 *
 * ```kotlin
 * cycleToggle.onChanged = { recreate() }
 * ```
 */
class CycleToggle @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(ctx, attrs) {

    /** Called after the new value is persisted. Wire this to `activity.recreate()`. */
    var onChanged: (() -> Unit)? = null

    private val switch: SwitchCompat

    /** Current value, without reaching for the switch. */
    val isCycleMode: Boolean get() = switch.isChecked

    init {
        LayoutInflater.from(context).inflate(R.layout.view_cycle_toggle, this, true)

        switch = findViewById(R.id.cycle_switch)

        // Seed first, attach the listener second, so restoring the stored value on inflate
        // is not mistaken for the user toggling it (which would cause a pointless recreate).
        switch.isChecked = Settings.isCycleMode(context)

        switch.setOnCheckedChangeListener { _, isChecked ->
            Settings.setCycleMode(context, isChecked)   // persists + fires Settings listeners
            onChanged?.invoke()                        // then let the host re-theme and recreate
        }
    }
}
