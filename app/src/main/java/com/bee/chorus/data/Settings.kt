package com.bee.chorus.data

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tiny SharedPreferences wrapper holding the app's user settings and its two small pieces of
 * cross-process state (playback speed, armed sleep timer).
 *
 * The one *user setting* is [KEY_CYCLE_MODE] — the normal, compact layout is the default.
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

    private const val KEY_SPEED = "playback_speed"
    private const val DEFAULT_SPEED = 1f

    // Sleep-timer restore state. The mode is a SleepTimer.Mode ordinal (0 = off,
    // 1 = countdown, 2 = end of track) and end_at is a wall-clock epoch-ms — the only
    // clock that still means something after the process has been killed.
    private const val KEY_SLEEP_MODE = "sleep_mode"
    private const val KEY_SLEEP_END_AT = "sleep_end_at"

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

    // ------------------------------------------------------------------ playback speed

    /** Playback speed multiplier; 1f is normal. Written by `PlayerHolder.setSpeed`. */
    fun speed(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_SPEED, DEFAULT_SPEED)

    fun setSpeed(ctx: Context, value: Float) {
        prefs(ctx).edit().putFloat(KEY_SPEED, value).apply()
    }

    // ------------------------------------------------------------------ sleep timer

    /** Persist an armed sleep timer: the SleepTimer mode ordinal + countdown end epoch-ms. */
    fun saveSleep(ctx: Context, modeOrdinal: Int, endAtMs: Long) {
        prefs(ctx).edit()
            .putInt(KEY_SLEEP_MODE, modeOrdinal)
            .putLong(KEY_SLEEP_END_AT, endAtMs)
            .apply()
    }

    /** Saved mode ordinal; 0 (off) when nothing is or was armed. */
    fun sleepMode(ctx: Context): Int =
        prefs(ctx).getInt(KEY_SLEEP_MODE, 0)

    /** Saved countdown end on the wall clock, 0 unless the saved mode is a countdown. */
    fun sleepEndAt(ctx: Context): Long =
        prefs(ctx).getLong(KEY_SLEEP_END_AT, 0L)

    /** Drop the saved timer (fired, cancelled, or expired while the process was dead). */
    fun clearSleep(ctx: Context) {
        prefs(ctx).edit().remove(KEY_SLEEP_MODE).remove(KEY_SLEEP_END_AT).apply()
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
