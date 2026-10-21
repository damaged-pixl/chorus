package com.bee.chorus.playback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.LruCache
import androidx.media3.common.C
import androidx.media3.common.audio.GainProcessor
import com.bee.chorus.data.Settings
import java.io.BufferedInputStream
import java.io.FileInputStream

/**
 * ReplayGain provider for [GainProcessor]. Holds one factor, applied to every sample of
 * whatever the sink is currently playing.
 *
 * The factor is written from the service side ([ReplayGain.factorFor] on the IO thread at
 * each media-item transition) and read on the audio thread, hence [@Volatile]. The value
 * is per *track*, constant for its duration: a transition therefore changes it a few
 * samples late at worst (the sink is flushed at item boundaries, which re-evaluates
 * [isUnityUntil]), which is inaudible for the small gains ReplayGain produces. This is the
 * documented approximation; sample-exact handover would need the gain on Media3's Format
 * (an upstream feature request, not worth forking the sink for).
 *
 * [isUnityUntil] is the switch that decides whether the processor is in the chain at all:
 * a unity factor answers TIME_END_OF_SOURCE, which marks gain as unchanged for the rest
 * of the stream, so a library without tags (or a track louder than the reference) plays
 * through untouched, with zero CPU cost.
 */
class ReplayGainProvider : GainProcessor.GainProvider {

    @Volatile private var factor = 1f

    /** Linear amplitude factor: 1f = no change, 0.5f = −6 dB. Written from any thread. */
    fun setFactor(value: Float) {
        factor = value
    }

    override fun getGainFactorAtSamplePosition(readFrames: Long, sampleRate: Int): Float = factor

    /**
     * Contract (from Media3's GainProcessor): exclusive end of the unity range starting at
     * [samplePosition]; TIME_UNSET when the position is *not* unity; TIME_END_OF_SOURCE
     * when unity runs to the end of the stream. Returning TIME_END_OF_SOURCE for a unity
     * factor is what drops the processor out of the chain entirely — an untagged library
     * plays through with zero gain cost.
     */
    override fun isUnityUntil(samplePosition: Long, sampleRate: Int): Long =
        if (factor == 1f) C.TIME_END_OF_SOURCE else C.TIME_UNSET
}

/**
 * Reads loudness tags off a track's file and resolves them to a linear gain factor.
 *
 * The *mode* is a user setting (see [com.bee.chorus.data.Settings.ReplayGainMode]):
 *  - [com.bee.chorus.data.Settings.ReplayGainMode.OFF] — no gain at all; the file is
 *    never even opened.
 *  - [com.bee.chorus.data.Settings.ReplayGainMode.TRACK] — the track's own gain, with the
 *    album gain as the fallback (the standard "track mode" choice: every song lands near
 *    the target loudness).
 *  - [com.bee.chorus.data.Settings.ReplayGainMode.ALBUM] — the album's gain, with the
 *    track gain as the fallback, so an album keeps its own level.
 *
 * Boosts above unity are never applied: the tags' peak handling would be needed to do
 * that safely, and quiet-by-design listening is the point of ReplayGain anyway.
 *
 * Formats handled, in this library's order of prevalence:
 *  - **FLAC** — native VORBIS_COMMENT metadata block (the phone's library is ~99% FLAC).
 *  - **Ogg Opus** — `OpusTags` comments; `R128_*_GAIN` is Q7.8 dB relative to EBU R128's
 *    −23 LUFS, and the +5 dB here converts that reference to ReplayGain's (the accepted
 *    practice; see Rockbox/GStreamer's handling of the same tag). The ID header's
 *    `output gain` is added per RFC 7845 (R128 tags are offsets from it).
 *  - **Ogg Vorbis** — `\x03vorbis` comment header.
 *  - **MP3** — ID3v2 TXXX frames (2.3 and 2.4; ISO-8859-1 and UTF-16 descriptions).
 *  - **MP4/M4A** — `moov/udta/meta/ilst/----` freeform atoms (com.apple.iTunes).
 *
 * Everything is hand-written and header-only: at most a couple of hundred KB is read, no
 * audio is decoded, and nothing here touches the network. Parsers return a dB value or
 * null and are pure enough to test against crafted header bytes.
 *
 * Call [factorFor] off the main thread — it opens the file. Results are cached per
 * MediaStore id for the process's lifetime.
 */
object ReplayGain {

    /** Q7.8 R128 reference (−23 LUFS) to ReplayGain reference — the +5 dB convention. */
    private const val R128_TO_REPLAYGAIN_DB = 5f

    /** Parse results per MediaStore id; the library is ~500 tracks, so this covers it. */
    private val cache = LruCache<Long, ReplayGainTags.Gains>(2048)

    /**
     * Linear gain factor for [mediaId] under [mode], parsed from the file at [uri] on the
     * calling thread. Untagged (or unparseable) → 1f: playback unchanged.
     */
    fun factorFor(
        context: Context,
        mediaId: Long,
        uri: Uri,
        mode: Settings.ReplayGainMode,
    ): Float {
        if (mode == Settings.ReplayGainMode.OFF) return 1f
        val gains = cache.get(mediaId) ?: readGains(context.contentResolver, uri).also {
            cache.put(mediaId, it)
        }
        val db = when (mode) {
            // Album mode is the album's own figure; a file without one falls back to its
            // track figure rather than playing on untouched.
            Settings.ReplayGainMode.ALBUM -> gains.albumDb ?: gains.trackDb
            else -> gains.trackDb ?: gains.albumDb
        }
        if (db == null || db >= 0f) return 1f
        return dbToFactor(db)
    }

    private fun dbToFactor(db: Float): Float = Math.pow(10.0, db / 20.0).toFloat()

    // ------------------------------------------------------------------ file access

    /** Read both gains off the file; an unreadable or untagged file yields empty gains. */
    private fun readGains(resolver: ContentResolver, uri: Uri): ReplayGainTags.Gains =
        try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    BufferedInputStream(fis, 8 * 1024).use { input ->
                        ReplayGainTags.gains(input) ?: ReplayGainTags.Gains(null, null)
                    }
                }
            } ?: ReplayGainTags.Gains(null, null)
        } catch (_: Exception) {
            // A missing, renamed or oddly-formed file must never affect playback.
            ReplayGainTags.Gains(null, null)
        }
}
