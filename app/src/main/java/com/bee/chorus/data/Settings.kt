package com.bee.chorus.data

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tiny SharedPreferences wrapper holding the app's user settings and its two small pieces of
 * cross-process state (playback speed, armed sleep timer).
 *
 * The *user settings* are [KEY_CYCLE_MODE] (the big-target, high-contrast layout),
 * [KEY_REPLAYGAIN_MODE] (off / track / album), [KEY_RESUME_POSITION] (reopen where you
 * left off) and [KEY_DEFAULT_SHUFFLE] (start every session shuffled).
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

    private const val KEY_REPLAYGAIN_MODE = "replaygain_mode"

    /** Not a `const`: an enum cannot be a constant. The stored form is its ordinal. */
    private val DEFAULT_REPLAYGAIN_MODE = ReplayGainMode.TRACK

    private const val KEY_RESUME_POSITION = "resume_position"
    private const val DEFAULT_RESUME_POSITION = true

    private const val KEY_DEFAULT_SHUFFLE = "default_shuffle"
    private const val DEFAULT_DEFAULT_SHUFFLE = false

    private const val KEY_SPEED = "playback_speed"
    private const val DEFAULT_SPEED = 1f

    // Sleep-timer restore state: a wall-clock epoch-ms deadline (the only clock that
    // still means something after the process has been killed) or the end-of-track
    // flag, written as one atomic pair so a reader never sees a half-updated timer.
    private const val KEY_SLEEP_DEADLINE = "sleep_deadline_ms"
    private const val KEY_SLEEP_END_OF_TRACK = "sleep_end_of_track"

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /**
     * ReplayGain mode listeners. Separate from the cycle-mode list because the payloads
     * differ, and because the two have different hosts: the cycle-mode listeners are
     * Activities (re-themed on change), the gain listeners are the playback service.
     */
    private val gainListeners = CopyOnWriteArrayList<(ReplayGainMode) -> Unit>()

    /**
     * How ReplayGain picks the gain figure for a track. Ordinals are stored in the
     * preferences, so the order of these constants is part of the on-disk format —
     * append new ones at the end, never reorder.
     */
    enum class ReplayGainMode {
        /** No gain at all; the file's loudness tags are never read. */
        OFF,

        /** The track's own gain, falling back to the album's. */
        TRACK,

        /** The album's gain, falling back to the track's. */
        ALBUM,
    }

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

    // ------------------------------------------------------------------ ReplayGain

    /** The ReplayGain mode in force. Defaults to track gain. */
    fun replayGainMode(ctx: Context): ReplayGainMode {
        val stored = prefs(ctx).getInt(KEY_REPLAYGAIN_MODE, DEFAULT_REPLAYGAIN_MODE.ordinal)
        // Ordinals are stored as an int; an unknown value (a downgrade, a hand-edited
        // preferences file) falls back to the default rather than throwing.
        return ReplayGainMode.entries.getOrElse(stored) { DEFAULT_REPLAYGAIN_MODE }
    }

    /** Persist the ReplayGain mode. The service reads it at the next track transition. */
    fun setReplayGainMode(ctx: Context, mode: ReplayGainMode) {
        prefs(ctx).edit().putInt(KEY_REPLAYGAIN_MODE, mode.ordinal).apply()
        for (l in gainListeners) l(mode)
    }

    // ------------------------------------------------------------------ resume position

    /**
     * True when reopening the app should resume the track where it was left, rather than
     * starting it from the beginning.
     */
    fun resumePosition(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_RESUME_POSITION, DEFAULT_RESUME_POSITION)

    fun setResumePosition(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_RESUME_POSITION, on).apply()
    }

    // ------------------------------------------------------------------ default shuffle

    /** True when every session starts with shuffle on. */
    fun defaultShuffle(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_DEFAULT_SHUFFLE, DEFAULT_DEFAULT_SHUFFLE)

    fun setDefaultShuffle(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_DEFAULT_SHUFFLE, on).apply()
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

    // ------------------------------------------------------------------ ReplayGain listeners

    /** Register [l] for ReplayGain mode changes. Adding the same instance twice is a no-op. */
    fun addReplayGainListener(l: (ReplayGainMode) -> Unit) {
        gainListeners.addIfAbsent(l)
    }

    /** Unregister [l]. Safe to call when it was never added. */
    fun removeReplayGainListener(l: (ReplayGainMode) -> Unit) {
        gainListeners.remove(l)
    }

    private fun notifyCycleMode(value: Boolean) {
        // CopyOnWriteArrayList iteration tolerates add/remove from inside a callback.
        for (l in listeners) l(value)
    }
}
