package com.bee.chorus.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bee.chorus.R
import com.bee.chorus.data.Settings
import com.bee.chorus.lyrics.Lyrics

/**
 * Read-only lyrics sheet, drawn inside the full-screen player's art card.
 *
 * It owns no playback state and registers no listeners: [syncTo] is pushed from
 * [PlayerPanel]'s existing ~500 ms tick, which only runs while audio is playing and the
 * player is attached. That is deliberate — a second timer here would be another thing to
 * start, stop and forget to unregister.
 *
 * Two renderings: timestamped lyrics get one [TextView] per line with the current line
 * scrolled to a third of the way down; untimed lyrics are one scrollable block of text.
 * Everything is styled from the player design tokens, so the sheet follows the palette.
 */
class LyricsPanel @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    private val scroll: ScrollView
    private val column: LinearLayout
    private val emptyView: TextView

    private val lineColor: Int
    private val activeColor: Int

    /**
     * Cycle-mode text size. The line views are built per track, after CycleMode.apply has
     * already walked the inflated tree, so they have to pick the scale up themselves —
     * otherwise the one screen you read at arm's length in sunlight stays small type.
     */
    private val lineSp: Float

    private var lines: List<Lyrics.Line> = emptyList()
    private var lineViews: List<TextView> = emptyList()
    private var synced = false
    private var activeIndex = -1
    private var scrollRetry = false

    init {
        LayoutInflater.from(context).inflate(R.layout.view_lyrics, this, true)
        scroll = findViewById(R.id.lyrics_scroll)
        column = findViewById(R.id.lyrics_column)
        emptyView = findViewById(R.id.lyrics_empty)

        lineColor = Theming.color(context, R.attr.chorusPlayerTextMuted)
        activeColor = Theming.color(context, R.attr.chorusPlayerText)
        lineSp = if (Settings.isCycleMode(context)) LINE_SP * CYCLE_TEXT_SCALE else LINE_SP

        // The same page colour and corner radius as the art card it replaces, so opening
        // lyrics reads as a swap of the same object rather than a new surface.
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = Ui.dp(context, CORNER_DP)
            setColor(Theming.color(context, R.attr.chorusPlayerBg))
        }
    }

    /** A load is in flight — say so rather than showing the "none" line prematurely. */
    fun showLoading() {
        lines = emptyList()
        lineViews = emptyList()
        synced = false
        activeIndex = -1
        column.removeAllViews()
        scroll.visibility = View.GONE
        emptyView.text = LOADING
        emptyView.visibility = View.VISIBLE
    }

    /** Replace the contents. Null or empty means the track has no lyrics. */
    fun bind(lyrics: Lyrics?) {
        lineViews = emptyList()
        lines = emptyList()
        activeIndex = -1
        column.removeAllViews()

        if (lyrics == null || lyrics.lines.isEmpty()) {
            synced = false
            scroll.visibility = View.GONE
            emptyView.text = NONE
            emptyView.visibility = View.VISIBLE
            return
        }

        scroll.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        synced = lyrics.synced

        if (lyrics.synced) {
            lines = lyrics.lines
            lineViews = lyrics.lines.map { line ->
                lineView(line.text).also { column.addView(it) }
            }
            scroll.scrollTo(0, 0)
        } else {
            // Unsynchronised: one block, no per-line views, no highlighting.
            column.addView(bodyView(lyrics.asText()))
            scroll.scrollTo(0, 0)
        }
    }

    /**
     * Follow playback. Driven by [PlayerPanel]'s tick; a no-op for unsynchronised lyrics.
     * Only the two lines whose state changed are re-tinted — this runs twice a second.
     */
    fun syncTo(positionMs: Long) {
        if (!synced || lineViews.isEmpty()) return
        val index = indexAt(positionMs)
        if (index == activeIndex) return
        setActive(activeIndex, index)
    }

    // ---------------------------------------------------------------- lines

    private fun lineView(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, lineSp)
        setLineSpacing(Ui.dp(context, LINE_GAP_DP), 1f)
        setPadding(0, dp(LINE_PADDING_DP), 0, dp(LINE_PADDING_DP))
        setTextColor(lineColor)
    }

    private fun bodyView(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, lineSp)
        setLineSpacing(Ui.dp(context, BODY_LINE_GAP_DP), 1f)
        setTextColor(Theming.color(context, R.attr.chorusPlayerText))
    }

    /** Last line whose timestamp has passed, or -1 before the first one. */
    private fun indexAt(positionMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    private fun setActive(previous: Int, next: Int) {
        activeIndex = next
        if (previous in lineViews.indices) {
            lineViews[previous].apply {
                setTextColor(lineColor)
                typeface = Typeface.DEFAULT
            }
        }
        if (next !in lineViews.indices) return
        lineViews[next].apply {
            setTextColor(activeColor)
            typeface = Typeface.DEFAULT_BOLD
        }
        scrollToLine(next)
    }

    /** Keep the current line a third of the way down, which leaves the next line visible. */
    private fun scrollToLine(index: Int) {
        // A bind can land before the first layout pass, when nothing has a height yet.
        // Retry exactly once on the next frame rather than looping while the panel is
        // hidden (a GONE ScrollView never gains a height).
        if (scroll.height <= 0 || column.height <= 0) {
            if (scrollRetry || !isAttachedToWindow || visibility != View.VISIBLE) return
            scrollRetry = true
            scroll.post {
                scrollRetry = false
                scrollToLine(index)
            }
            return
        }
        val view = lineViews.getOrNull(index) ?: return
        val max = (column.height - scroll.height).coerceAtLeast(0)
        val target = (view.top - scroll.height / 3).coerceIn(0, max)
        scroll.smoothScrollTo(0, target)
    }

    private fun dp(value: Int): Int = Ui.dp(context, value.toFloat()).toInt()

    private companion object {
        const val CORNER_DP = 22f
        const val LINE_SP = 16f
        const val CYCLE_TEXT_SCALE = 1.25f
        const val LINE_GAP_DP = 2f
        const val BODY_LINE_GAP_DP = 8f
        const val LINE_PADDING_DP = 6
        const val NONE = "No lyrics for this track"
        const val LOADING = "Looking for lyrics…"
    }
}
