package com.bee.chorus.data

import android.util.Log
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * MP3 ID3v2 tag reading/writing. In-place only, no tag growth, no moving frames.
 * Only handles ID3v2.3 and v2.4 text frames we care about: TIT2, TPE1, TALB, TYER/TDRC.
 */
object Id3Tags {
    private const val TAG = "Id3Tags"
    private const val HEADER_SIZE = 10
    private const val FRAME_HEADER_SIZE = 10

    data class Tags(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val year: String = ""
    )

    enum class Version { V23, V24 }

    data class FrameInfo(
        val id: String,
        val frameDataStart: Long, // absolute position in file where frame data starts (after frame header)
        val frameDataSize: Int,   // size of frame data as declared in header
        val headerStart: Long,    // absolute position of frame header start
    )

    data class TagInfo(
        val version: Version,
        val tagStart: Long, // position of 'I' in file
        val tagSize: Int,   // total tag size after header (syncsafe) - declared
        val frames: List<FrameInfo>,
        val headerFlags: Byte
    )

    fun isMp3(path: String): Boolean {
        if (!path.endsWith(".mp3", ignoreCase = true)) return false
        return try {
            RandomAccessFile(path, "r").use { raf ->
                if (raf.length() < 10) return false
                val b = ByteArray(3)
                raf.readFully(b)
                if (b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()) {
                    return true
                }
                // check for MPEG frame sync
                raf.seek(0)
                val b2 = ByteArray(2)
                if (raf.read(b2) == 2) {
                    val sync = ((b2[0].toInt() and 0xFF) shl 8) or (b2[1].toInt() and 0xFF)
                    // frame sync is 11 bits set
                    return (sync and 0xFFE0) == 0xFFE0
                }
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "isMp3 failed", e)
            false
        }
    }

    fun readTags(path: String): Tags? {
        val info = readTagInfo(path) ?: return null
        return try {
            RandomAccessFile(path, "r").use { raf ->
                var title = ""
                var artist = ""
                var album = ""
                var year = ""
                for (f in info.frames) {
                    raf.seek(f.frameDataStart)
                    val data = ByteArray(f.frameDataSize)
                    raf.readFully(data)
                    if (data.isEmpty()) continue
                    val enc = data[0].toInt() and 0xFF
                    val text = decodeText(data, 1, data.size - 1, enc)
                    when (f.id) {
                        "TIT2" -> title = text
                        "TPE1" -> artist = text
                        "TALB" -> album = text
                        "TYER" -> if (year.isEmpty()) year = text
                        "TDRC" -> year = text // prefer TDRC in v2.4 if present
                    }
                }
                Tags(title = title, artist = artist, album = album, year = year)
            }
        } catch (e: Exception) {
            Log.d(TAG, "readTags failed", e)
            null
        }
    }

    private fun readTagInfo(path: String): TagInfo? {
        return try {
            RandomAccessFile(path, "r").use { raf ->
                if (raf.length() < HEADER_SIZE) return null
                val header = ByteArray(HEADER_SIZE)
                raf.readFully(header)
                if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
                    return null
                }
                val major = header[3].toInt() and 0xFF
                if (major != 3 && major != 4) return null
                val version = if (major == 4) Version.V24 else Version.V23
                val flags = header[5]
                val tagSize = syncSafeInt(header, 6)
                if (tagSize <= 0) return null
                val tagStart = 0L
                val frames = ArrayList<FrameInfo>()
                var pos = HEADER_SIZE.toLong()
                val tagEnd = HEADER_SIZE.toLong() + tagSize.toLong()
                while (pos + FRAME_HEADER_SIZE <= tagEnd) {
                    raf.seek(pos)
                    val fh = ByteArray(FRAME_HEADER_SIZE)
                    raf.readFully(fh)
                    // padding
                    if (fh[0] == 0.toByte()) break
                    val id = String(fh, 0, 4, StandardCharsets.ISO_8859_1)
                    // check if it's valid ASCII frame id
                    if (!isValidFrameId(id)) {
                        pos++
                        continue
                    }
                    // Format flags set means the frame's *data layout* is transformed —
                    // compressed, encrypted, grouped (v2.3) or length-prefixed (v2.4's
                    // data-length indicator). Writing raw text over a transformed frame
                    // corrupts it, and decoding it as plain text would misplace the edit;
                    // mainstream encoders never set these on text frames, so skipping to
                    // the next frame (which then refuses the field as "missing") is the
                    // honest answer.
                    if (fh[9] != 0.toByte()) {
                        val skipSize = if (version == Version.V24) syncSafeInt(fh, 4) else beInt(fh, 4)
                        pos = if (skipSize > 0) pos + FRAME_HEADER_SIZE + skipSize
                              else pos + FRAME_HEADER_SIZE
                        continue
                    }
                    val frameDataSize = if (version == Version.V24) syncSafeInt(fh, 4) else beInt(fh, 4)
                    if (frameDataSize <= 0) {
                        pos += FRAME_HEADER_SIZE
                        continue
                    }
                    val frameDataStart = pos + FRAME_HEADER_SIZE
                    if (frameDataStart + frameDataSize > tagEnd) break // don't read past tag
                    frames.add(
                        FrameInfo(
                            id = id,
                            frameDataStart = frameDataStart,
                            frameDataSize = frameDataSize,
                            headerStart = pos
                        )
                    )
                    pos = frameDataStart + frameDataSize
                }
                TagInfo(version = version, tagStart = tagStart, tagSize = tagSize, frames = frames, headerFlags = flags)
            }
        } catch (e: Exception) {
            Log.d(TAG, "readTagInfo failed", e)
            null
        }
    }

    private fun isValidFrameId(id: String): Boolean {
        if (id.length != 4) return false
        for (c in id) {
            if (c.code < 0x41 || c.code > 0x5A) return false // A-Z
        }
        return true
    }

    /**
     * Try to write tags in place. Returns list of field names that were refused (too long).
     * Only writes if new encoded value fits within existing frame's byte space
     * (existing frame data length) - conservative/safe approach as per spec.
     */
    fun writeTagsInPlace(path: String, newTags: Tags): List<String> {
        val refused = ArrayList<String>()
        val info = readTagInfo(path) ?: return listOf("title", "artist", "album", "year")
        // Global unsynchronisation (v2.3 bit 7, v2.4 bit 1 of the header flags) bit-stuffs
        // every frame's data — the parser above reads it as raw bytes, so any frame it
        // "found" may sit at the wrong offset. Writing there would corrupt the tag, so the
        // only safe answer for an unsync'd tag is refusal. (LAME and every mainstream
        // encoder leave this off; it exists for damaged-stream protection.)
        val unsync = if (info.version == Version.V23) {
            (info.headerFlags.toInt() and 0x80) != 0
        } else {
            (info.headerFlags.toInt() and 0x02) != 0
        }
        if (unsync) return listOf("title", "artist", "album", "year")
        return try {
            RandomAccessFile(path, "rw").use { raf ->
                // Try to update existing frames
                // TIT2
                if (newTags.title.isNotEmpty()) {
                    val existing = info.frames.find { it.id == "TIT2" }
                    if (existing == null) {
                        refused.add("title")
                    } else if (!tryWriteFrame(raf, info, existing, newTags.title)) {
                        refused.add("title")
                    }
                }
                if (newTags.artist.isNotEmpty()) {
                    val existing = info.frames.find { it.id == "TPE1" }
                    if (existing == null) {
                        refused.add("artist")
                    } else if (!tryWriteFrame(raf, info, existing, newTags.artist)) {
                        refused.add("artist")
                    }
                }
                if (newTags.album.isNotEmpty()) {
                    val existing = info.frames.find { it.id == "TALB" }
                    if (existing == null) {
                        refused.add("album")
                    } else if (!tryWriteFrame(raf, info, existing, newTags.album)) {
                        refused.add("album")
                    }
                }
                if (newTags.year.isNotEmpty()) {
                    // prefer updating TDRC if exists (v2.4) else TYER
                    val existing = info.frames.find { it.id == "TDRC" } ?: info.frames.find { it.id == "TYER" }
                    if (existing == null) {
                        refused.add("year")
                    } else if (!tryWriteFrame(raf, info, existing, newTags.year)) {
                        refused.add("year")
                    }
                }
                refused
            }
        } catch (e: Exception) {
            Log.d(TAG, "writeTagsInPlace failed", e)
            listOf("title", "artist", "album", "year")
        }
    }

    private fun tryWriteFrame(raf: RandomAccessFile, info: TagInfo, frame: FrameInfo, newValue: String): Boolean {
        // Try to encode - prefer ISO-8859-1 if it fits, else UTF-16 with BOM
        val latin1 = tryEncode(newValue, StandardCharsets.ISO_8859_1)
        val canUseLatin1 = latin1 != null
        val (encoding, encodedText) = if (canUseLatin1 && 1 + latin1.size <= frame.frameDataSize) {
            0 to latin1
        } else {
            val utf16 = encodeUtf16WithBom(newValue)
            if (1 + utf16.size <= frame.frameDataSize) {
                1 to utf16 // UTF-16 with BOM as per spec for v2.4? encoding 1 is UTF-16 with BOM in v2.4
            } else {
                return false
            }
        }
        // Write in place: encoding byte + text
        raf.seek(frame.frameDataStart)
        raf.writeByte(encoding)
        raf.write(encodedText)
        // If we used less space, pad rest with zeros (frame data size remains same in header for safety)
        val written = 1 + encodedText.size
        var pad = frame.frameDataSize - written
        while (pad > 0) {
            raf.writeByte(0)
            pad--
        }
        // Keep frame header size the same (conservative, safe in-place) - don't change it
        return true
    }

    private fun tryEncode(s: String, charset: Charset): ByteArray? {
        return try {
            val bytes = s.toByteArray(charset)
            // Verify roundtrip
            val decoded = String(bytes, charset)
            if (decoded == s) bytes else null
        } catch (e: Exception) {
            null
        }
    }

    private fun encodeUtf16WithBom(s: String): ByteArray {
        // UTF-16 with BOM (little endian BOM FF FE on little endian systems, but better to use big endian BOM FE FF in general?)
        // ID3v2.4 encoding 1 means UTF-16 with BOM
        val chars = s.toCharArray()
        val bytes = ByteArray(2 + chars.size * 2)
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xFE.toByte() // little endian BOM
        var i = 0
        while (i < chars.size) {
            val c = chars[i].code
            bytes[2 + i * 2] = (c and 0xFF).toByte()
            bytes[2 + i * 2 + 1] = ((c shr 8) and 0xFF).toByte()
            i++
        }
        return bytes
    }

    private fun decodeText(data: ByteArray, start: Int, len: Int, enc: Int): String {
        if (len <= 0 || start >= data.size) return ""
        val actualLen = minOf(len, data.size - start)
        return try {
            when (enc) {
                0 -> String(data, start, actualLen, StandardCharsets.ISO_8859_1)
                1 -> {
                    // UTF-16 with BOM
                    if (actualLen >= 2) {
                        val bom0 = data[start].toInt() and 0xFF
                        val bom1 = data[start + 1].toInt() and 0xFF
                        if (bom0 == 0xFF && bom1 == 0xFE) {
                            // LE
                            val out = CharArray((actualLen - 2) / 2)
                            var i = 0
                            while (i < out.size && start + 2 + i * 2 + 1 < start + actualLen) {
                                val b0 = data[start + 2 + i * 2].toInt() and 0xFF
                                val b1 = data[start + 2 + i * 2 + 1].toInt() and 0xFF
                                out[i] = ((b1 shl 8) or b0).toChar()
                                i++
                            }
                            String(out)
                        } else if (bom0 == 0xFE && bom1 == 0xFF) {
                            // BE
                            val out = CharArray((actualLen - 2) / 2)
                            var i = 0
                            while (i < out.size && start + 2 + i * 2 + 1 < start + actualLen) {
                                val b0 = data[start + 2 + i * 2].toInt() and 0xFF
                                val b1 = data[start + 2 + i * 2 + 1].toInt() and 0xFF
                                out[i] = ((b0 shl 8) or b1).toChar()
                                i++
                            }
                            String(out)
                        } else {
                            // try as UTF-16LE
                            val out = CharArray(actualLen / 2)
                            var i = 0
                            while (i < out.size && start + i * 2 + 1 < start + actualLen) {
                                val b0 = data[start + i * 2].toInt() and 0xFF
                                val b1 = data[start + i * 2 + 1].toInt() and 0xFF
                                out[i] = ((b1 shl 8) or b0).toChar()
                                i++
                            }
                            String(out)
                        }
                    } else {
                        ""
                    }
                }
                2 -> {
                    // UTF-16BE
                    val out = CharArray(actualLen / 2)
                    var i = 0
                    while (i < out.size && start + i * 2 + 1 < start + actualLen) {
                        val b0 = data[start + i * 2].toInt() and 0xFF
                        val b1 = data[start + i * 2 + 1].toInt() and 0xFF
                        out[i] = ((b0 shl 8) or b1).toChar()
                        i++
                    }
                    String(out)
                }
                3 -> String(data, start, actualLen, StandardCharsets.UTF_8)
                else -> String(data, start, actualLen, StandardCharsets.ISO_8859_1)
            }.trimEnd('\u0000')
        } catch (e: Exception) {
            ""
        }
    }

    private fun syncSafeInt(b: ByteArray, off: Int): Int {
        return ((b[off].toInt() and 0x7F) shl 21) or
            ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or
            (b[off + 3].toInt() and 0x7F)
    }

    private fun beInt(b: ByteArray, off: Int): Int {
        return ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
    }

    private fun putSyncSafe(b: ByteArray, off: Int, value: Int) {
        b[off] = ((value shr 21) and 0x7F).toByte()
        b[off + 1] = ((value shr 14) and 0x7F).toByte()
        b[off + 2] = ((value shr 7) and 0x7F).toByte()
        b[off + 3] = (value and 0x7F).toByte()
    }

    private fun putBeInt(b: ByteArray, off: Int, value: Int) {
        b[off] = ((value shr 24) and 0xFF).toByte()
        b[off + 1] = ((value shr 16) and 0xFF).toByte()
        b[off + 2] = ((value shr 8) and 0xFF).toByte()
        b[off + 3] = (value and 0xFF).toByte()
    }
}
