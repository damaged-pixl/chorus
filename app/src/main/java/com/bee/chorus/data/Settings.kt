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
 * left off), [KEY_DEFAULT_SHUFFLE] (start every session shuffled), [KEY_SKIP_SECONDS]
 * (how far the skip buttons jump), [KEY_PLAY_ON_CONNECT] (start on headphones/Bluetooth),
 * [KEY_RESTORE_ON_BOOT] (bring the queue back after a reboot) and the equaliser's
 * [KEY_EQ_ON] / [KEY_EQ_PRESET] / [KEY_EQ_BANDS] trio.
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

    // How far the player's own skip buttons jump. In seconds because that is the unit
    // the row says; clamped on read so a hand-edited value cannot walk it off the
    // screen's three choices (or make a tap jump past the end of a track).
    private const val KEY_SKIP_SECONDS = "skip_seconds"
    private const val DEFAULT_SKIP_SECONDS = 10
    private val SKIP_CHOICES = intArrayOf(5, 10, 15)

    private const val KEY_PLAY_ON_CONNECT = "play_on_connect"
    private const val DEFAULT_PLAY_ON_CONNECT = false

    private const val KEY_RESTORE_ON_BOOT = "restore_queue_on_boot"
    private const val DEFAULT_RESTORE_ON_BOOT = false

    // Equaliser. The preset index is a position in *this device's* preset list, which is
    // what android.media.audiofx hands out; a downgrade or a hand-edited file that lands
    // outside the list falls back to "Normal" rather than throwing. The custom bands are
    // millibels, one per band, and are only read when the preset is EQ_CUSTOM.
    private const val KEY_EQ_ON = "equaliser_on"
    private const val DEFAULT_EQ_ON = false
    private const val KEY_EQ_PRESET = "equaliser_preset"
    private const val DEFAULT_EQ_PRESET = 0
    private const val KEY_EQ_BANDS = "equaliser_bands"

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
     * Equaliser listeners. Same strong-listener rule as the two above: the only host is the
     * playback service, which removes its own in onDestroy. The callback carries nothing —
     * the whole state is three settings, and the service re-reads them.
     */
    private val equaliserListeners = CopyOnWriteArrayList<() -> Unit>()

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

    // ------------------------------------------------------------------ skip distance

    /**
     * How far the player's rewind/forward buttons jump, in seconds — 5, 10 or 15.
     *
     * Read per tap rather than cached with a listener: it is one SharedPreferences hit
     * on a path that is already talking to the player, and the alternative is a listener
     * the player panel would have to remove in its own lifecycle for a number this small.
     */
    fun skipSeconds(ctx: Context): Int {
        val stored = prefs(ctx).getInt(KEY_SKIP_SECONDS, DEFAULT_SKIP_SECONDS)
        return SKIP_CHOICES.firstOrNull { it == stored } ?: DEFAULT_SKIP_SECONDS
    }

    fun setSkipSeconds(ctx: Context, seconds: Int) {
        if (seconds !in SKIP_CHOICES) return
        prefs(ctx).edit().putInt(KEY_SKIP_SECONDS, seconds).apply()
    }

    // ------------------------------------------------------------------ play on connect

    /**
     * True when plugging in headphones or pairing a Bluetooth source should start playback
     * by itself, rather than waiting to be told. Off by default: an app that begins making
     * noise the moment something connects is exactly the kind of surprise a setting exists
     * to prevent, and the *headphone out* event already stops playback through Android's
     * own noisy-broadcast handling — this is the other half of that pairing.
     */
    fun playOnConnect(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_PLAY_ON_CONNECT, DEFAULT_PLAY_ON_CONNECT)

    fun setPlayOnConnect(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_PLAY_ON_CONNECT, on).apply()
    }

    // ------------------------------------------------------------------ restore on boot

    /**
     * True when a reboot should bring the saved queue back (paused) rather than leaving
     * the player empty until the app is next opened. Off by default: it starts the
     * playback service at every boot, which is a service someone may not want running
     * before they have touched the phone.
     */
    fun restoreOnBoot(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_RESTORE_ON_BOOT, DEFAULT_RESTORE_ON_BOOT)

    fun setRestoreOnBoot(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_RESTORE_ON_BOOT, on).apply()
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

    // ------------------------------------------------------------------ equaliser

    /**
     * Sentinel preset: the saved *bands*, not any of the device's own presets, are what is
     * applied. Device presets are indexed from 0, so -1 is the one value that cannot be one.
     */
    const val EQ_CUSTOM = -1

    /** True when the system equaliser should be doing anything at all. */
    fun isEqualiserOn(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_EQ_ON, DEFAULT_EQ_ON)

    /** Persist the on/off state; the playback service re-applies through a listener. */
    fun setEqualiserOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_EQ_ON, on).apply()
        notifyEqualiser()
    }

    /** The chosen device preset, or [EQ_CUSTOM] for hand-set bands. */
    fun equaliserPreset(ctx: Context): Int =
        prefs(ctx).getInt(KEY_EQ_PRESET, DEFAULT_EQ_PRESET)

    fun setEqualiserPreset(ctx: Context, index: Int) {
        prefs(ctx).edit().putInt(KEY_EQ_PRESET, index).apply()
        notifyEqualiser()
    }

    /**
     * Hand-set band levels in millibels — read only when the preset is [EQ_CUSTOM].
     *
     * A comma-joined string rather than a set: SharedPreferences has no int-array call, and
     * a StringSet would hand the bands back in whatever order it liked, which would quietly
     * swap a bass shelf onto the treble. Values are small signed integers, so a plain join
     * round-trips exactly.
     */
    fun equaliserBands(ctx: Context): IntArray =
        prefs(ctx).getString(KEY_EQ_BANDS, "").orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .toIntArray()

    fun setEqualiserBands(ctx: Context, levels: IntArray) {
        prefs(ctx).edit().putString(KEY_EQ_BANDS, levels.joinToString(",")).apply()
        notifyEqualiser()
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

    // ------------------------------------------------------------------ equaliser listeners

    /** Register [l] for any equaliser setting change. Adding the same instance twice is a no-op. */
    fun addEqualiserListener(l: () -> Unit) {
        equaliserListeners.addIfAbsent(l)
    }

    /** Unregister [l]. Safe to call when it was never added. */
    fun removeEqualiserListener(l: () -> Unit) {
        equaliserListeners.remove(l)
    }

    private fun notifyEqualiser() {
        for (l in equaliserListeners) l()
    }

    private fun notifyCycleMode(value: Boolean) {
        // CopyOnWriteArrayList iteration tolerates add/remove from inside a callback.
        for (l in listeners) l(value)
    }
}
