package com.bee.chorus.lyrics

import java.io.InputStream

/**
 * Lyrics out of an ID3v2 tag, read straight off the audio file's stream.
 *
 * Only two frames matter: USLT (unsynchronised lyrics — one block of text) and SYLT
 * (synchronised lyrics — text/timestamp pairs). Both are read here rather than through
 * Media3, whose ID3 decoder does not expose them. ID3v2.2's 3-byte frame ids are not
 * supported: they are vanishingly rare next to v2.3/v2.4.
 *
 * The whole tag is read into one bounded buffer (tags are a few KB; the cap stops a
 * corrupt size field from asking for a huge array). Every length is bounds-checked
 * because these bytes come from a file we did not write.
 */
object Id3Lyrics {

    /** A tag this big is broken, not a lyrics tag — refuse it rather than allocate. */
    private const val MAX_TAG_BYTES = 4 * 1024 * 1024

    /**
     * Read the lyrics from the tag at the start of [input]. Returns null when there is no
     * usable tag or no USLT/SYLT frame in it. SYLT wins because it is the synced one.
     */
    fun read(input: InputStream): Lyrics? {
        val header = ByteArray(10)
        if (!readFully(input, header, header.size)) return null
        if (header[0] != 'I'.code.toByte() ||
            header[1] != 'D'.code.toByte() ||
            header[2] != '3'.code.toByte()
        ) {
            return null
        }
        val major = header[3].toInt() and 0xFF
        if (major != 3 && major != 4) return null
        val tagSize = syncSafe(header, 6)
        if (tagSize <= 0 || tagSize > MAX_TAG_BYTES) return null
        val tag = ByteArray(tagSize)
        if (!readFully(input, tag, tagSize)) return null

        var unsynced: Lyrics? = null
        var pos = 0
        while (pos + 10 <= tag.size) {
            // Frame ids are ASCII; a zero byte here is the padding that ends the tag.
            if (tag[pos] == 0.toByte()) break
            val id = String(tag, pos, 4, Charsets.ISO_8859_1)
            // v2.4 sizes are syncsafe; v2.3 (and some sloppy v2.4 writers) are plain BE.
            val size = if (major == 4) syncSafe(tag, pos + 4) else beInt(tag, pos + 4)
            pos += 10
            if (size <= 0 || pos + size > tag.size) break
            when (id) {
                "SYLT" -> return parseSylt(tag, pos, size)
                "USLT" -> if (unsynced == null) unsynced = parseUslt(tag, pos, size)
            }
            pos += size
        }
        return unsynced
    }

    // ---------------------------------------------------------------- frames

    /** encoding(1) language(3) descriptor(null-terminated) lyrics(rest). */
    private fun parseUslt(tag: ByteArray, off: Int, len: Int): Lyrics? {
        if (len < 5) return null
        val enc = tag[off].toInt() and 0xFF
        val bodyStart = off + 4
        val end = off + len
        val term = findTerminator(tag, bodyStart, end, enc)
        val textStart = if (term < 0) bodyStart else term + terminatorWidth(enc)
        if (textStart >= end) return null
        return LyricsParser.parse(decode(tag, textStart, end - textStart, enc))
    }

    /**
     * encoding(1) language(3) timestampFormat(1) contentType(1) descriptor(null-terminated)
     * then repeated: text(null-terminated) timestamp(4, big-endian).
     *
     * timestampFormat 2 is milliseconds, which is the only one we can place on the seek
     * bar; format 1 counts MPEG frames, so those entries keep their text but lose their
     * timing and render as plain lyrics.
     */
    private fun parseSylt(tag: ByteArray, off: Int, len: Int): Lyrics? {
        if (len < 7) return null
        val enc = tag[off].toInt() and 0xFF
        val millisecondStamps = (tag[off + 4].toInt() and 0xFF) == 2
        val end = off + len
        var pos = off + 6
        val descriptorEnd = findTerminator(tag, pos, end, enc)
        if (descriptorEnd < 0) return null
        pos = descriptorEnd + terminatorWidth(enc)

        val lines = ArrayList<Lyrics.Line>()
        while (pos < end) {
            val term = findTerminator(tag, pos, end, enc)
            if (term < 0) break
            val text = decode(tag, pos, term - pos, enc).trim()
            pos = term + terminatorWidth(enc)
            if (pos + 4 > end) break
            val stamp = beInt(tag, pos).toLong() and 0xFFFF_FFFFL
            pos += 4
            if (text.isNotEmpty()) {
                lines.add(Lyrics.Line(if (millisecondStamps) stamp else -1L, text))
            }
        }
        if (lines.isEmpty()) return null
        if (!millisecondStamps) return Lyrics(lines, synced = false)
        lines.sortBy { it.timeMs }
        return Lyrics(lines, synced = true)
    }

    // ---------------------------------------------------------------- text

    private fun decode(bytes: ByteArray, off: Int, len: Int, encoding: Int): String {
        if (len <= 0) return ""
        val charset = when (encoding) {
            1 -> Charsets.UTF_16       // honours the BOM the frame carries
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        // Frames are null-padded; the padding is not text. Trim whole code units: cutting a
        // UTF-16 unit in half would turn a trailing Latin-1 character (high byte 0) into
        // two bytes of rubbish, which is most European lyrics ending in "é" and friends.
        val unit = terminatorWidth(encoding)
        var end = off + len
        while (end - unit >= off && (0 until unit).all { bytes[end - unit + it] == 0.toByte() }) {
            end -= unit
        }
        return String(bytes, off, end - off, charset).trim()
    }

    private fun terminatorWidth(encoding: Int): Int =
        if (encoding == 1 || encoding == 2) 2 else 1

    /** Index of the first null terminator of [encoding], or -1. UTF-16's is two bytes. */
    private fun findTerminator(b: ByteArray, from: Int, end: Int, encoding: Int): Int {
        val width = terminatorWidth(encoding)
        var i = from
        while (i + width <= end) {
            var zero = true
            for (j in 0 until width) {
                if (b[i + j] != 0.toByte()) { zero = false; break }
            }
            if (zero) return i
            i += width
        }
        return -1
    }

    // ---------------------------------------------------------------- bytes

    private fun readFully(input: InputStream, out: ByteArray, length: Int): Boolean {
        var read = 0
        while (read < length) {
            val n = input.read(out, read, length - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    private fun syncSafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or
            ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or
            (b[off + 3].toInt() and 0x7F)

    private fun beInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
}
