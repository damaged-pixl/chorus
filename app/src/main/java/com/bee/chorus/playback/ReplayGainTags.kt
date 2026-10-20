package com.bee.chorus.playback

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Pure tag parsing for ReplayGain — no Android types, so every parser here runs on the
 * JVM in tests. See [ReplayGain] for how the result becomes a gain factor.
 */
object ReplayGainTags {

    /** Q7.8 R128 reference (−23 LUFS) to ReplayGain reference — the +5 dB convention. */
    private const val R128_TO_REPLAYGAIN_DB = 5f

    // ------------------------------------------------------------------ sniff + dispatch

    /**
     * Parse loudness tags from [input] (positioned at the container start): dispatch on
     * the magic bytes, returning the resolved dB figure or null when the file carries no
     * usable gain tags. Pure stream code — no Android types — so it is testable on the JVM.
     */
    fun gainDb(input: BufferedInputStream): Float? {
        input.mark(8)
        val head = input.readN(8) ?: return null
        input.reset()
        val text = String(head, Charsets.ISO_8859_1)
        return when {
            // Magic lengths differ per container: fLaC/OggS are 4 bytes, ID3 is 3 (the
            // 4th byte read here is the ID3 version), and MP4 has no magic at all — its
            // 'ftyp' type sits at offset 4 behind the first atom's size.
            text.startsWith("fLaC") -> parseFlac(input)
            text.startsWith("OggS") -> parseOgg(input)
            text.startsWith("ID3") -> parseId3(input)
            head.size == 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> parseMp4(input)
            else -> null
        }
    }

    // ------------------------------------------------------------------ value resolution

    /**
     * Resolve a comment map (keys upper-cased) to one dB figure: track first, then album,
     * REPLAYGAIN spelling before R128 (an Opus file SHOULD only carry one flavour).
     * [extraDb] carries container-level gain (Opus output gain).
     */
    private fun dbFromComments(comments: Map<String, String>, extraDb: Float = 0f): Float? {
        val track = parseGainDb(comments["REPLAYGAIN_TRACK_GAIN"])
            ?: parseR128(comments["R128_TRACK_GAIN"])?.plus(R128_TO_REPLAYGAIN_DB)
        if (track != null) return track + extraDb
        val album = parseGainDb(comments["REPLAYGAIN_ALBUM_GAIN"])
            ?: parseR128(comments["R128_ALBUM_GAIN"])?.plus(R128_TO_REPLAYGAIN_DB)
        return album?.plus(extraDb)
    }

    /** "-6.50 dB" / "+1.2" → dB. Tolerates surrounding whitespace and the "dB" suffix. */
    private fun parseGainDb(raw: String?): Float? {
        raw ?: return null
        val token = raw.trim().substringBefore(' ').trim()
        return token.toFloatOrNull()
    }

    /** Q7.8 fixed-point integer ("-573") → dB, before any reference conversion. */
    private fun parseR128(raw: String?): Float? {
        raw ?: return null
        val token = raw.trim().substringBefore(' ').trim()
        return token.toIntOrNull()?.div(256f)
    }

    // ------------------------------------------------------------------ FLAC

    /**
     * "fLaC" magic, then metadata blocks (last-flag/type byte, 24-bit BE length). Block
     * type 4 is the VORBIS_COMMENT; the parse stops there — audio is never read.
     */
    private fun parseFlac(input: BufferedInputStream): Float? {
        input.readN(4) ?: return null                      // magic
        repeat(256) {                                      // block cap, not audio cap
            val header = input.u8() ?: throw EOFException()
            val type = header and 0x7F
            val length = input.be24()
            if (type == 4) {
                val block = input.readN(length) ?: throw EOFException()
                return dbFromComments(vorbisComments(block))
            }
            input.skipFully(length.toLong())
            if (header and 0x80 != 0) return null          // last block, no comment
        }
        return null
    }

    /** A VORBIS_COMMENT block: vendor string + KEY=value list, all lengths little-endian. */
    private fun vorbisComments(block: ByteArray): Map<String, String> {
        val buf = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        val out = HashMap<String, String>(8)
        try {
            buf.skipFully(buf.int.coerceAtLeast(0))        // vendor
            val count = buf.int.coerceIn(0, 4096)
            repeat(count) {
                val len = buf.int.coerceIn(0, 1 shl 20)
                val bytes = ByteArray(len)
                buf.get(bytes)
                putComment(out, String(bytes, Charsets.UTF_8))
            }
        } catch (_: Exception) {
            // Truncated block: keep whatever was read.
        }
        return out
    }

    /** "KEY=value" → map entry, keys upper-cased. */
    private fun putComment(out: MutableMap<String, String>, comment: String) {
        val eq = comment.indexOf('=')
        if (eq <= 0) return
        val key = comment.substring(0, eq).trim().uppercase()
        if (key == "REPLAYGAIN_TRACK_GAIN" || key == "REPLAYGAIN_ALBUM_GAIN" ||
            key == "R128_TRACK_GAIN" || key == "R128_ALBUM_GAIN"
        ) {
            out[key] = comment.substring(eq + 1)
        }
    }

    // ------------------------------------------------------------------ Ogg (Opus / Vorbis)

    /**
     * Walk Ogg pages of the first logical stream, reassembling packets, until the two
     * headers that matter are in hand: the codec ID header (Opus `output gain`) and the
     * comment packet (`OpusTags` for Opus, `\x03vorbis` for Vorbis).
     */
    private fun parseOgg(input: BufferedInputStream): Float? {
        var opusHeaderDb = 0f
        var opus = false
        var current = java.io.ByteArrayOutputStream()
        var seenVorbisId = false
        repeat(64) {                                       // page cap
            val capture = input.readN(4) ?: throw EOFException()
            if (String(capture, Charsets.ISO_8859_1) != "OggS") return null
            input.u8()                                     // version
            input.u8()                                     // header type
            input.skipFully(8)                             // granule position
            input.skipFully(8)                             // serial + crc
            val segments = input.u8() ?: throw EOFException()
            val lacing = input.readN(segments) ?: throw EOFException()
            var dataLen = 0
            for (v in lacing) dataLen += v.toInt()
            val data = input.readN(dataLen) ?: throw EOFException()

            var offset = 0
            for (v in lacing) {
                val len = v.toInt()
                current.write(data, offset, len)
                offset += len
                if (len < 255) {                             // packet complete
                    val packet = current.toByteArray()
                    current = java.io.ByteArrayOutputStream()
                    val text = packet.toString(Charsets.ISO_8859_1)
                    when {
                        text.startsWith("OpusHead") -> {
                            opus = true
                            if (packet.size >= 18) {
                                // output gain: signed Q7.8 at bytes 16..17 (RFC 7845 §5.1)
                                opusHeaderDb =
                                    ((packet[16].toInt() and 0xFF) or
                                        (packet[17].toInt() shl 8)).toShort() / 256f
                            }
                        }
                        text.startsWith("OpusTags") ->
                            return dbFromComments(
                                vorbisComments(packet.copyOfRange(8, packet.size)),
                                opusHeaderDb,
                            )
                        text.startsWith("\u0001vorbis") -> seenVorbisId = true
                        seenVorbisId && text.startsWith("\u0003vorbis") ->
                            return dbFromComments(
                                vorbisComments(packet.copyOfRange(7, packet.size)),
                            )
                    }
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ ID3v2 (MP3)

    /** ID3v2 header + TXXX frames (2.3 and 2.4). */
    private fun parseId3(input: BufferedInputStream): Float? {
        val header = input.readN(10) ?: throw EOFException()
        val major = header[3].toInt() and 0xFF
        val flags = header[5].toInt() and 0xFF
        var remaining = syncsafe(header, 6).toLong()
        if (flags and 0x40 != 0 && major == 4) {            // v2.4 extended header
            val ext = input.readN(4) ?: throw EOFException()
            remaining -= 4 + syncsafe(ext, 0)
        } else if (flags and 0x40 != 0) {                   // v2.3: size is plain BE32
            val ext = input.readN(4) ?: throw EOFException()
            remaining -= 4 + ((ext[0].toLong() shl 24) or
                (ext[1].toLong() and 0xFF shl 16) or
                (ext[2].toLong() and 0xFF shl 8) or
                (ext[3].toLong() and 0xFF))
        }

        val comments = HashMap<String, String>(4)
        while (remaining >= 10) {
            val frame = input.readN(10) ?: break
            if (frame[0] == 0.toByte()) break              // padding
            val id = String(frame, 0, 4, Charsets.ISO_8859_1)
            val size = if (major == 4) syncsafe(frame, 4)
            else ((frame[4].toLong() shl 24) or (frame[5].toLong() and 0xFF shl 16) or
                (frame[6].toLong() and 0xFF shl 8) or (frame[7].toLong() and 0xFF)).toInt()
            remaining -= 10 + size.toLong()
            if (size <= 0 || size > remaining + size) break
            val body = input.readN(size) ?: break
            if (id == "TXXX") {
                val pair = txxx(body) ?: continue
                putComment(comments, "${pair.first}=${pair.second}")
            }
        }
        return dbFromComments(comments)
    }

    /** TXXX body: encoding byte, description (NUL-terminated), value. */
    private fun txxx(body: ByteArray): Pair<String, String>? {
        if (body.isEmpty()) return null
        val enc = body[0].toInt() and 0xFF
        val charset = when (enc) {
            1 -> "UTF-16"      // BOM-aware per ID3 spec
            2 -> "UTF-16BE"
            3 -> "UTF-8"
            else -> "ISO-8859-1"
        }
        val wide = enc == 1 || enc == 2
        val term = if (wide) 2 else 1
        var i = 1
        while (i + term <= body.size) {
            val zero = if (wide) body[i] == 0.toByte() && body[i + 1] == 0.toByte()
            else body[i] == 0.toByte()
            if (zero) {
                val desc = String(body, 1, i - 1, Charset.forName(charset)).trim()
                val value = String(body, i + term, body.size - i - term, Charset.forName(charset)).trim()
                return desc to value
            }
            i += term
        }
        return null
    }

    // ------------------------------------------------------------------ MP4

    /**
     * Atom walk: moov → udta → meta (4-byte version/flags) → ilst → `----` freeform
     * entries with mean/name/data children. Sizes are big-endian; 0 means "to end of
     * file", 1 introduces a 64-bit largesize.
     */
    private fun parseMp4(input: BufferedInputStream): Float? {
        val comments = HashMap<String, String>(4)
        // Top-level limit is "until EOF": atom sizes are authoritative, and readN/skip
        // throw at a truncated file, which the outer catch turns into "no gain".
        walkMp4(input, Long.MAX_VALUE / 2, 0, comments)
        return dbFromComments(comments)
    }

    private fun walkMp4(
        input: BufferedInputStream,
        limit: Long,
        depth: Int,
        comments: MutableMap<String, String>,
    ) {
        if (depth > 8) return
        var remaining = limit
        while (remaining >= 8) {
            val head = input.readN(8) ?: return
            remaining -= 8
            var size = ((head[0].toLong() and 0xFF) shl 24) or
                ((head[1].toLong() and 0xFF) shl 16) or
                ((head[2].toLong() and 0xFF) shl 8) or (head[3].toLong() and 0xFF)
            val type = String(head, 4, 4, Charsets.ISO_8859_1)
            if (size == 1L) {                              // largesize
                val large = input.readN(8) ?: return
                remaining -= 8
                size = 0
                for (b in large) size = (size shl 8) or (b.toLong() and 0xFF)
            }
            if (size == 0L) size = remaining + 8           // extends to EOF
            val payload = size - 8
            if (payload < 0 || payload > remaining) return
            when (type) {
                "moov", "udta", "ilst" -> walkMp4(input, payload, depth + 1, comments)
                "meta" -> {                                // 4-byte version/flags prefix
                    input.skipFully(4)
                    walkMp4(input, payload - 4, depth + 1, comments)
                }
                "----" -> readFreeform(input, payload, comments)
                else -> input.skipFully(payload)
            }
            remaining -= payload
        }
    }

    /** A `----` entry: mean (the iTunes atom prefix), name (the tag), data (the value). */
    private fun readFreeform(
        input: BufferedInputStream,
        payload: Long,
        comments: MutableMap<String, String>,
    ) {
        var remaining = payload
        var name: String? = null
        var value: String? = null
        while (remaining >= 8) {
            val head = input.readN(8) ?: return
            remaining -= 8
            val size = ((head[0].toLong() and 0xFF) shl 24) or
                ((head[1].toLong() and 0xFF) shl 16) or
                ((head[2].toLong() and 0xFF) shl 8) or (head[3].toLong() and 0xFF)
            val type = String(head, 4, 4, Charsets.ISO_8859_1)
            val body = (size - 8).toInt()
            if (body < 0 || body > remaining) return
            when (type) {
                "name" -> name = String(input.readN(body) ?: return, 4, body - 4, Charsets.ISO_8859_1)
                "data" -> {
                    val data = input.readN(body) ?: return
                    if (body > 12) value = String(data, 12, body - 12, Charsets.UTF_8)
                }
                else -> input.skipFully(body.toLong())
            }
            remaining -= body
        }
        if (name != null && value != null) putComment(comments, "$name=$value")
    }

    // ------------------------------------------------------------------ byte helpers

    /** Read exactly [n] bytes, or null at EOF. */
    private fun InputStream.readN(n: Int): ByteArray? {
        if (n <= 0) return ByteArray(0)
        val bytes = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = read(bytes, read, n - read)
            if (r < 0) return null
            read += r
        }
        return bytes
    }

    private fun InputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) {
                if (read() < 0) throw EOFException()
                left--
            } else {
                left -= skipped
            }
        }
    }

    /** Unsigned byte, or null at EOF. */
    private fun InputStream.u8(): Int? = read()

    /** Big-endian 24-bit. */
    private fun InputStream.be24(): Int {
        val b = readN(3) ?: throw EOFException()
        return ((b[0].toInt() and 0xFF) shl 16) or ((b[1].toInt() and 0xFF) shl 8) or
            (b[2].toInt() and 0xFF)
    }

    /** ID3 syncsafe (28-bit) at [offset] within [b]. */
    private fun syncsafe(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0x7F) shl 21) or
            ((b[offset + 1].toInt() and 0x7F) shl 14) or
            ((b[offset + 2].toInt() and 0x7F) shl 7) or
            (b[offset + 3].toInt() and 0x7F)

    /** ByteBuffer variant: skip [n] bytes. */
    private fun ByteBuffer.skipFully(n: Int) {
        position(position() + n)
    }
}
