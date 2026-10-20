package com.bee.chorus.lyrics

/**
 * LRC and plain-text lyrics, parsed by hand.
 *
 * LRC is line-oriented: `[mm:ss.xx]words`, optionally several timestamps on one line
 * (`[00:12.30][01:04.10]Chorus`), plus metadata tags (`[ar:…]`, `[ti:…]`, `[offset:…]`)
 * that carry no lyrics. The timestamp regex only matches `digits:digits`, so the metadata
 * tags are skipped without a separate branch. Enhanced LRC's per-word `<mm:ss.xx>` stamps
 * are stripped from the text; the line-level timestamp is what the panel follows.
 */
object LyricsParser {

    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TAG = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
    private val OFFSET_TAG = Regex("""^\[offset:\s*([+-]?\d+)]$""", RegexOption.IGNORE_CASE)

    /**
     * Parse [text] as LRC when it contains timestamps, otherwise as plain text. Both tag
     * text and a `.lrc` sidecar go through here: some files embed a whole LRC document in
     * their USLT frame.
     */
    fun parse(text: String): Lyrics? = parseLrc(text) ?: fromText(text)

    /** Timestamped lyrics, or null when the document has no timestamped line at all. */
    fun parseLrc(text: String): Lyrics? {
        var offset = 0L
        for (line in text.lineSequence()) {
            OFFSET_TAG.find(line.trim())?.let { offset = it.groupValues[1].toLongOrNull() ?: 0L }
        }

        val out = ArrayList<Lyrics.Line>()
        for (raw in text.lineSequence()) {
            val stamps = TIME_TAG.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val sung = WORD_TAG.replace(TIME_TAG.replace(raw, ""), "").trim()
            for (m in stamps) {
                val ms = m.groupValues[1].toLong() * 60_000L +
                    m.groupValues[2].toLong() * 1_000L +
                    fractionMs(m.groupValues[3])
                out.add(Lyrics.Line(ms + offset, sung))
            }
        }
        if (out.isEmpty()) return null
        // One line can be stamped twice (a repeated chorus); both entries are real.
        out.sortBy { it.timeMs }
        return Lyrics(out, synced = true)
    }

    /** Plain text: every line kept as-is, blank lines kept for stanza spacing. */
    fun fromText(text: String): Lyrics? {
        val lines = text.lines().map { it.trimEnd() }
        val first = lines.indexOfFirst { it.isNotBlank() }
        if (first < 0) return null
        val last = lines.indexOfLast { it.isNotBlank() }
        return Lyrics(
            lines.subList(first, last + 1).map { Lyrics.Line(-1L, it) },
            synced = false
        )
    }

    /** `.x` is tenths, `.xx` hundredths, `.xxx` milliseconds — the widths LRC uses. */
    private fun fractionMs(digits: String): Long = when (digits.length) {
        0 -> 0L
        1 -> digits.toLong() * 100L
        2 -> digits.toLong() * 10L
        else -> digits.toLong()
    }
}
