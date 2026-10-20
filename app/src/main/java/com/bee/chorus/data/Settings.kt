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

    // Sleep-timer restore state: a wall-clock epoch-ms deadline (the only clock that
    // still means something after the process has been killed) or the end-of-track
    // flag, written as one atomic pair so a reader never sees a half-updated timer.
    private const val KEY_SLEEP_DEADLINE = "sleep_deadline_ms"
    private const val KEY_SLEEP_END_OF_TRACK = "sleep_end_of_track"

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

    /**
     * Wall-clock epoch-ms at which the sleep timer should pause playback, or 0 when no
     * countdown is armed. Wall-clock rather than `elapsedRealtime` because the value has
     * to mean something after process death (and, in principle, a reboot).
     */
    fun sleepDeadline(ctx: Context): Long = prefs(ctx).getLong(KEY_SLEEP_DEADLINE, 0L)

    /** True when the armed sleep timer is waiting for the current track to end. */
    fun sleepEndOfTrack(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SLEEP_END_OF_TRACK, false)

    /**
     * Store the armed timer as one atomic pair: either a countdown deadline (with
     * `endOfTrack` false) or the end-of-track flag (with `deadlineMs` 0). Passing
     * 0/false clears it. One editor write, so a reader never sees a half-updated pair.
     */
    fun setSleepTimer(ctx: Context, deadlineMs: Long, endOfTrack: Boolean) {
        prefs(ctx).edit()
            .putLong(KEY_SLEEP_DEADLINE, deadlineMs)
            .putBoolean(KEY_SLEEP_END_OF_TRACK, endOfTrack)
            .apply()
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
