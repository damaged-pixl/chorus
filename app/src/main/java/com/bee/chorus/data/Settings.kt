package com.bee.chorus.data

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tiny SharedPreferences wrapper holding the app's user settings.
 *
 * Currently one flag: [KEY_CYCLE_MODE]. Defaults to OFF — the normal, compact layout.
 *
 * Listeners registered via [addListener] are process-wide and fire on every [setCycleMode]
 * call, so a host can react (re-theme, relayout) without polling. They are held strongly:
 * **remove any listener that captures an Activity in `onDestroy`**, otherwise that Activity
 * is leaked across a `recreate()`.
 *
 * All methods are cheap and main-thread safe. The listener callback list is copied on write,
 * so it is also safe to add/remove listeners from inside a callback.
 */
object Settings {

    private const val FILE = "chorus_settings"
    private const val KEY_CYCLE_MODE = "cycle_mode"
    private const val DEFAULT_CYCLE_MODE = false

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ cycle mode

    /** True when the rider has switched on the big-target, high-contrast layout. */
    fun isCycleMode(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_CYCLE_MODE, DEFAULT_CYCLE_MODE)

    /**
     * Persist cycle mode and notify every listener with the new value.
     *
     * The write is async ([SharedPreferences.Editor.apply]); listeners are invoked immediately
     * with the value that was requested, which is what the in-memory state already holds.
     */
    fun setCycleMode(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_CYCLE_MODE, on).apply()
        notifyCycleMode(on)
    }

    // ------------------------------------------------------------------ listeners

    /** Register [l] for cycle-mode changes. Adding the same instance twice is a no-op. */
    fun addListener(l: (Boolean) -> Unit) {
        listeners.addIfAbsent(l)
    }

    /** Unregister [l]. Safe to call when it was never added. */
    fun removeListener(l: (Boolean) -> Unit) {
        listeners.remove(l)
    }

    private fun notifyCycleMode(value: Boolean) {
        // CopyOnWriteArrayList iteration tolerates add/remove from inside a callback.
        for (l in listeners) l(value)
    }
}
