package com.bee.chorus.lyrics

/**
 * One track's lyrics, plus the two hand-written parsers that produce them.
 *
 * Why hand-written: Chorus ships no formatting dependency, and the two formats that
 * matter for a local player are small — an ID3v2 USLT/SYLT frame inside the audio file,
 * or an LRC/plain-text sidecar next to it. Everything here is offline and dependency-free.
 */
data class Lyrics(
    /** Sorted by time when [synced]; unsynchronised lines carry -1. */
    val lines: List<Line>,
    /** True only when every line has a real timestamp, so the panel can follow playback. */
    val synced: Boolean
) {
    data class Line(val timeMs: Long, val text: String)

    /** The text as one block, for the plain scrollable (unsynchronised) rendering. */
    fun asText(): String = lines.joinToString("\n") { it.text }
}

/**
 * `/Music/Song.mp3` -> `/Music/Song`: the basename a `.lrc`/`.txt` sidecar shares.
 * A dot in a directory name never counts, and a file with no extension is left alone.
 */
internal fun sidecarStem(path: String): String {
    val dot = path.lastIndexOf('.')
    return if (dot > path.lastIndexOf('/')) path.substring(0, dot) else path
}
