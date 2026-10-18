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
 * (no service, no database, no persistence). While armed it owns at most one Handler
 * callback: a 1 Hz countdown for the timed modes, or one exactly-delayed "fire at
 * track end" runnable for [Mode.END_OF_TRACK] (re-armed from every player event, so
 * seeks, pauses and track changes stay correct). When it fires it pauses through the
 * shared [PlayerHolder] controller, so it works from any screen or with none open.
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

    /** Held strongly; every current registration is paired with a view lifecycle. */
    private val listeners = CopyOnWriteArrayList<(SleepTimer) -> Unit>()

    @Volatile private var mode = Mode.OFF

    /** Countdown target on the elapsed-realtime clock (immune to wall-clock changes). */
    private var endTimeMs = 0L

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
        main.post(tick)   // first tick refreshes the UI now and schedules the next
        notifyListeners()
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
    }

    /** Unregister [l]. Safe to call when it was never added. */
    fun removeListener(l: (SleepTimer) -> Unit) {
        listeners.remove(l)
    }

    // ---------------------------------------------------------------- internals

    /** Drop every pending callback and (if end-of-track) the player listener. */
    private fun disarm() {
        main.removeCallbacks(tick)
        main.removeCallbacks(fire)
        if (mode == Mode.END_OF_TRACK) PlayerHolder.removePlayerListener(playerListener)
        armedMediaId = null
        nextFireAt = 0L
    }

    private fun notifyListeners() {
        for (l in listeners) l(this)
    }

    // ---- countdown ----

    private val tick = object : Runnable {
        override fun run() {
            if (mode != Mode.COUNTDOWN) return
            if (remainingMs() <= 0L) {
                // Zero: pause playback and clear the timer. A paused or empty player
                // makes the pause a no-op, which is still the right outcome.
                PlayerHolder.whenReady { it.pause() }
                disarm()
                mode = Mode.OFF
                notifyListeners()
            } else {
                notifyListeners()   // one UI update per second, nothing between
                main.postDelayed(this, 1000L)
            }
        }
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
