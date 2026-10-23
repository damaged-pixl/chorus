package com.bee.chorus.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.Equalizer
import android.util.Log
import com.bee.chorus.data.Settings

/**
 * What this device's effect chain offers — read once by a dialog that only needs the names
 * to show and the numbers to draw sliders. Null when the device will not let an app open an
 * equaliser at all (some OEMs reserve the chain), which is the UI's cue to say so plainly
 * rather than pretend the setting did something.
 */
data class EqualiserCaps(
    /** The device's own presets, in its own order and naming. */
    val presets: List<String>,
    /** One label per band, from its centre frequency — "60 Hz", "16 kHz". */
    val bandLabels: List<String>,
    /** Level range in millibels, as the device reports it. */
    val minMillibels: Int,
    val maxMillibels: Int,
) {
    val bandCount: Int get() = bandLabels.size

    companion object {
        const val TAG = "ChorusEq"

        /**
         * Caps for one open effect — the shared reading path for the probe and for a live
         * session, so the two can never disagree about what a band is.
         */
        internal fun of(eq: Equalizer): EqualiserCaps {
            val range = eq.bandLevelRange
            val labels = ArrayList<String>(eq.numberOfBands.toInt())
            for (band in 0 until eq.numberOfBands.toInt()) {
                // Centre frequency is milliHz; a band label wants the unit a human reads.
                val hz = eq.getCenterFreq(band.toShort()) / 1000
                labels += if (hz >= 1000) String.format(java.util.Locale.US, "%d kHz", hz / 1000)
                else "$hz Hz"
            }
            val presets = ArrayList<String>(eq.numberOfPresets.toInt())
            for (p in 0 until eq.numberOfPresets.toInt()) {
                // getPresetName answers a bogus index with an empty string, not a throw.
                // The platform punctuates its names ("Rock."), which reads as a typo in a
                // list of ours — so the trailing full stop is trimmed, nothing else.
                presets += eq.getPresetName(p.toShort())
                    .trim()
                    .removeSuffix(".")
                    .ifEmpty { "Preset ${p + 1}" }
            }
            return EqualiserCaps(
                presets = presets,
                bandLabels = labels,
                minMillibels = range[0].toInt(),
                maxMillibels = range[1].toInt(),
            )
        }

        /**
         * Read the device's capabilities for the settings screen.
         *
         * A *live* audio session is needed: an effect opened on session 0 (the output mix)
         * is refused by most phones — including one with a perfectly good equaliser, which
         * is exactly what the naive probe got wrong here and reported as "not available".
         * So this borrows a session from a silent AudioTrack: constructed, never started,
         * released in the same breath. Nothing is played and no player is needed, so the
         * Setup tab can list presets and label sliders before playback has ever begun.
         */
        fun probeCaps(): EqualiserCaps? {
            var eq: Equalizer? = null
            var track: AudioTrack? = null
            return try {
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(44_100)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(4096)
                    .build()
                eq = Equalizer(0, track.audioSessionId)
                of(eq)
            } catch (e: Exception) {
                Log.w(TAG, "no equaliser on this device", e)
                null
            } finally {
                try {
                    eq?.release()
                } catch (_: Exception) {
                    // A release that fails has already lost the effect; nothing to do.
                }
                try {
                    track?.release()
                } catch (_: Exception) {
                    // Same: the track was a means to a session id, nothing more.
                }
            }
        }
    }
}

/**
 * The system equaliser, hung off the player's audio session.
 *
 * `android.media.audiofx.Equalizer` is the platform's own effect chain — no library, no DSP
 * code, and the only equaliser an app can get for free. It attaches to an *audio session
 * id*, which ExoPlayer hands out when its sink is built (`onAudioSessionIdChanged`), so
 * [attach] runs again for every new session and the saved state is re-applied to it. That
 * also means a session that is never built (nothing has been played yet) has no equaliser,
 * which is exactly right: there is nothing to equalise.
 *
 * Not every device lets an app open one. Every call here is therefore wrapped: a device
 * that refuses leaves playback running un-equalised rather than crashing, and the caps
 * probe answers null so the settings screen can say the feature is unavailable.
 */
class ChorusEqualiser(private val context: Context) {

    private var equalizer: Equalizer? = null

    /**
     * Open the effect on [sessionId] and apply what is saved.
     *
     * The old one is released first — a session id arriving means the previous session is
     * gone, and holding an effect onto it would be holding a dead audio path.
     */
    fun attach(sessionId: Int) {
        release()
        if (sessionId <= 0) return // C.AUDIO_SESSION_ID_UNSET, or nothing to attach to.
        equalizer = try {
            Equalizer(0, sessionId)
        } catch (e: Exception) {
            Log.w(Companion.TAG, "cannot open equaliser on session $sessionId", e)
            null
        } ?: return
        // One line worth having: whether this phone lets an app equalise its own session at
        // all (the probe can pass where a live session is refused, and the reverse).
        Log.i(Companion.TAG, "equaliser attached to session $sessionId")
        apply()
    }

    /**
     * Re-read the settings and apply them to whatever session is attached (nothing at all
     * when there is none — the state is saved and will be applied by the next [attach]).
     */
    fun apply() {
        val eq = equalizer ?: return
        try {
            if (!Settings.isEqualiserOn(context)) {
                eq.enabled = false
                return
            }
            val preset = Settings.equaliserPreset(context)
            val bands = Settings.equaliserBands(context)
            if (preset == Settings.EQ_CUSTOM) {
                // Bands before enable: a freshly opened effect starts at the device's own
                // default, and one audible frame of that is one too many.
                if (bands.size == eq.numberOfBands.toInt()) {
                    for (i in bands.indices) {
                        eq.setBandLevel(i.toShort(), bands[i].toShort())
                    }
                }
            } else {
                // A preset index from another device (or a downgrade) is not worth a crash:
                // it falls back to the device's first preset, which is "Normal" everywhere.
                val count = eq.numberOfPresets.toInt()
                eq.usePreset(preset.coerceIn(0, (count - 1).coerceAtLeast(0)).toShort())
            }
            eq.enabled = true
        } catch (e: Exception) {
            Log.w(Companion.TAG, "equaliser refused to apply", e)
        }
    }

    /** Close the effect — its session is going away with the player that held it. */
    fun release() {
        try {
            equalizer?.release()
        } catch (_: Exception) {
            // Same reasoning as the probe: a failed release has already lost the effect.
        }
        equalizer = null
    }

    private companion object {
        const val TAG = EqualiserCaps.TAG
    }
}
