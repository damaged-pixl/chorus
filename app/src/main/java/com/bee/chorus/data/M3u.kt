package com.bee.chorus.data

/**
 * Minimal M3U/M3U8 support for playlist import and export.
 *
 * Format, for reference:
 * ```
 * #EXTM3U
 * #EXTINF:233,Artist - Title
 * /storage/emulated/0/Music/Artist/Album/01 Title.flac
 * ```
 * Lines starting with `#` are directives or comments; anything else is a file path —
 * absolute, or relative to wherever the playlist file itself lives. Chorus matches
 * entries against the library by path only (an `#EXTINF` title is never used for
 * matching), so reading a playlist really is just collecting its non-comment lines.
 */
object M3u {

    /** Serialise [tracks] as a UTF-8 `.m3u8` body, with `#EXTINF` duration + title lines. */
    fun build(tracks: List<Track>): String {
        val sb = StringBuilder(64 + tracks.size * 96)
        sb.append("#EXTM3U\n")
        for (t in tracks) {
            // EXTINF duration is whole seconds, rounded to nearest.
            sb.append("#EXTINF:").append((t.durationMs + 500) / 1000).append(',')
            if (t.artist.isNotBlank()) sb.append(t.artist).append(" - ")
            sb.append(t.title).append('\n')
            sb.append(t.path).append('\n')
        }
        return sb.toString()
    }

    /**
     * The file-entry lines of an `.m3u`/`.m3u8` body: every non-blank line that is not a
     * directive or comment. CR line endings and stray whitespace are trimmed.
     */
    fun entries(text: String): List<String> {
        val out = ArrayList<String>()
        for (raw in text.split('\n')) {
            val line = raw.trim() // also drops a trailing \r from CRLF files
            if (line.isEmpty() || line.startsWith("#")) continue
            out.add(line)
        }
        return out
    }
}
