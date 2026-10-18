package com.bee.chorus.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Sleep timer: pause playback after N minutes, or when the current track ends.
 *
 * Pure in-memory state on purpose — it deliberately does not survive process death
 * (no service, no database, no persistence). While armed it owns at most two Handler
 * callbacks, and only ever one *periodic* one:
 *  - timed modes: a single [expire] runnable posted for the exact remaining time, plus a
 *    1 Hz [tick] refresh **only while a listener is registered** (i.e. while a screen is
 *    actually showing the countdown). Headless — screen closed, audio playing in the
 *    background — the timer costs the main looper zero wakeups per second instead of the
 *    ~3,600 a 60-minute countdown used to burn.
 *  - [Mode.END_OF_TRACK]: one exactly-delayed "fire at track end" runnable, re-armed from
 *    every player event, so seeks, pauses and track changes stay correct.
 * When it fires it pauses through the shared [PlayerHolder] controller, so it works from
 * any screen or with none open.
 *
 * Everything here runs on the main thread: start/cancel come from UI taps and the
 * player callbacks already arrive on the main looper. Listeners are held strongly —
 * [PlayerPanel] adds one in `show()` and removes it in `hide()`. Anyone registering
 * outside that pattern must remove in the Activity's onDestroy.
 */
object SleepTimer {

    enum class Mode { OFF, COUNTDOWN, END_OF_TRACK }

    /** How close to the scheduled fire time a track change is treated as "it just ended". */
    private const val FIRE_TOLERANCE_MS = 1_500L

    private val main = Handler(Looper.getMainLooper())

    /** Visible countdown refresh period. Only runs while a listener is registered. */
    private const val COUNTDOWN_TICK_MS = 1_000L

    /** Held strongly; every current registration is paired with a view lifecycle. */
    private val listeners = CopyOnWriteArrayList<(SleepTimer) -> Unit>()

    @Volatile private var mode = Mode.OFF

    /** Countdown target on the elapsed-realtime clock (immune to wall-clock changes). */
    private var endTimeMs = 0L

    /** True while a 1 Hz countdown refresh is in flight, so chains cannot stack up. */
    private var tickScheduled = false

    // ---- end-of-track bookkeeping ----

    /** mediaId the timer is waiting on; null when no fire is scheduled. */
    private var armedMediaId: String? = null

    /** uptimeMillis at which [fire] is due (only meaningful while armed). */
    private var nextFireAt = 0L

    // ---------------------------------------------------------------- public

    /** Pause playback in [minutes] minutes, replacing any current timer. */
    fun startCountdown(minutes: Int) {
        disarm()
        mode = Mode.COUNTDOWN
        endTimeMs = SystemClock.elapsedRealtime() + minutes * 60_000L
        // The one callback that matters, watched or not: pause exactly at zero.
        main.postDelayed(expire, remainingMs())
        // The 1 Hz refresh only exists to redraw a visible countdown.
        if (listeners.isNotEmpty()) ensureTick()
    }

    /** Let the current track finish, then pause. Replaces any current timer. */
    fun startEndOfTrack() {
        disarm()
        mode = Mode.END_OF_TRACK
        PlayerHolder.addPlayerListener(playerListener)
        rearmEndOfTrack()
        notifyListeners()
    }

    /** Stop the timer without touching playback. No-op when nothing is running. */
    fun cancel() {
        if (mode == Mode.OFF) return
        disarm()
        mode = Mode.OFF
        notifyListeners()
    }

    fun mode(): Mode = mode

    /** Milliseconds left on a countdown; 0 in every other mode. */
    fun remainingMs(): Long = when (mode) {
        Mode.COUNTDOWN -> (endTimeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        else -> 0L
    }

    /** Register for state/tick changes. Adding the same instance twice is a no-op. */
    fun addListener(l: (SleepTimer) -> Unit) {
        listeners.addIfAbsent(l)
        // A countdown armed before this screen existed has no periodic refresh running;
        // start one now, or the label would sit frozen until the next mode change.
        if (mode == Mode.COUNTDOWN) ensureTick()
    }

    /** Unregister [l]. Safe to call when it was never added. */
    fun removeListener(l: (SleepTimer) -> Unit) {
        listeners.remove(l)
    }

    // ---------------------------------------------------------------- internals

    /** Drop every pending callback and (if end-of-track) the player listener. */
    private fun disarm() {
        main.removeCallbacks(tick)
        main.removeCallbacks(expire)
        main.removeCallbacks(fire)
        tickScheduled = false
        if (mode == Mode.END_OF_TRACK) PlayerHolder.removePlayerListener(playerListener)
        armedMediaId = null
        nextFireAt = 0L
    }

    private fun notifyListeners() {
        for (l in listeners) l(this)
    }

    // ---- countdown ----

    /**
     * One UI refresh per second, and only while something is watching. If no listener is
     * registered the countdown keeps no periodic callback at all — [expire] is already
     * posted for the exact end, so a headless 60-minute timer wakes the looper once, not
     * 3,600 times. Firing is [expire]'s job, not this runnable's, so the two can overlap
     * harmlessly: whichever lands first pauses, and the other sees mode != COUNTDOWN.
     */
    private val tick = object : Runnable {
        override fun run() {
            tickScheduled = false
            if (mode != Mode.COUNTDOWN) return
            if (remainingMs() <= 0L) {
                expireCountdown()
                return
            }
            notifyListeners()   // one UI update per second, nothing between
            if (listeners.isNotEmpty()) {
                tickScheduled = true
                main.postDelayed(this, COUNTDOWN_TICK_MS)
            }
        }
    }

    /** Start the 1 Hz refresh if one is not already running (avoids stacking chains). */
    private fun ensureTick() {
        if (mode != Mode.COUNTDOWN || tickScheduled) return
        tickScheduled = true
        main.post(tick)
    }

    /** The countdown's single wakeup when headless: pause playback at zero. */
    // Explicit Runnable type: the lambda refers to itself (to re-post), which Kotlin's
    // type checker cannot infer.
    private val expire: Runnable = Runnable {
        if (mode != Mode.COUNTDOWN) return@Runnable
        val left = remainingMs()
        if (left > 0L) {
            // Woke early (only a stale post can do that): top the countdown up rather
            // than pausing before the timer asked for it.
            main.removeCallbacks(expire)
            main.postDelayed(expire, left)
            return@Runnable
        }
        expireCountdown()
    }

    /**
     * Zero on a countdown: pause and clear the timer. A paused or empty player makes the
     * pause a no-op, which is still the right outcome.
     */
    private fun expireCountdown() {
        PlayerHolder.whenReady { it.pause() }
        disarm()
        mode = Mode.OFF
        notifyListeners()
    }

    // ---- end of track ----

    private val fire = Runnable {
        if (mode != Mode.END_OF_TRACK) return@Runnable
        val p = PlayerHolder.player
        if (p == null) return@Runnable              // not bound; a later event re-arms
        if (!p.isPlaying) {
            // Paused before the track could finish (or playback ended at the queue's
            // end, which needs no timer): do not consume. A later player event
            // re-arms; a finished queue clears the timer outright.
            if (p.playbackState == Player.STATE_ENDED) {
                disarm()
                mode = Mode.OFF
                notifyListeners()
            }
            return@Runnable
        }
        onEndOfTrack()
    }

    /**
     * Fires from every player event while end-of-track is armed: one delayed runnable
     * sits exactly [duration - position] away, so seeks, pauses, resumes and skips all
     * re-target it. A track change is either the armed track just having ended (the
     * event beat the already-due runnable — stop now, the next track is milliseconds
     * in) or a user skip (re-target the new track); the scheduled due-time tells them
     * apart.
     */
    private val playerListener: (Player) -> Unit = { p ->
        if (mode == Mode.END_OF_TRACK) {
            val current = p.currentMediaItem?.mediaId
            if (armedMediaId != null && current != armedMediaId) {
                if (SystemClock.uptimeMillis() >= nextFireAt - FIRE_TOLERANCE_MS) {
                    onEndOfTrack()
                } else {
                    rearmEndOfTrack()
                }
            } else {
                rearmEndOfTrack()
            }
        }
    }

    private fun rearmEndOfTrack() {
        main.removeCallbacks(fire)
        armedMediaId = null
        nextFireAt = 0L
        if (mode != Mode.END_OF_TRACK) return
        val p = PlayerHolder.player ?: return
        if (!p.isPlaying) {
            // Paused/idle: nothing is counting down. The next player event re-arms;
            // a queue that has fully ended has nothing left to wait for.
            if (p.playbackState == Player.STATE_ENDED) {
                disarm()
                mode = Mode.OFF
                notifyListeners()
            }
            return
        }
        val duration = p.duration
        if (duration == C.TIME_UNSET || duration <= 0) return   // unknown yet; next event retries
        armedMediaId = p.currentMediaItem?.mediaId
        val remaining = (duration - p.currentPosition).coerceAtLeast(0L)
        nextFireAt = SystemClock.uptimeMillis() + remaining
        main.postDelayed(fire, remaining)
    }

    private fun onEndOfTrack() {
        PlayerHolder.whenReady { it.pause() }
        disarm()
        mode = Mode.OFF
        notifyListeners()
    }
}
