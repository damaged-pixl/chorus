package com.bee.chorus.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.bee.chorus.R
import kotlin.math.min

/**
 * The A–Z rail down the right edge of the track list: press or drag on a letter and the
 * list jumps to the first row that starts with it.
 *
 * It is a *jump* rail, not a scrollbar — the list already scrolls, and the point of this
 * is the two-second trip from "C" to "T" in a 1,500-track library. So it reports the row
 * index for the letter under the finger and leaves the scrolling to the host, which is the
 * only thing that knows about the RecyclerView.
 *
 * Only letters present in the list are drawn: with nothing starting at "X", an X in the
 * rail would jump nowhere, and a rail full of dead keys reads as broken.
 *
 * The label colour and size are resolved from the theme and the measured slot in code, so
 * day/night/cycle all reach it and the letters cannot collide on a short screen.
 */
class AzScroller @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
) : View(ctx, attrs) {

    /**
     * One rail entry: the letter as drawn, and the row it jumps to. Set wholesale by
     * [setIndex] whenever the list changes; an empty list hides the rail.
     */
    data class Entry(val letter: Char, val position: Int)

    private var entries: List<Entry> = emptyList()

    /** Row to jump to, handed back for the letter under the finger. */
    var onJump: ((Int) -> Unit)? = null

    /** The letter last jumped to, so dragging over one letter does not re-fire every tick. */
    private var lastJumped: Char = ' '

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val insetPx = resources.displayMetrics.density * INSET_DP

    private val labelSizePx = resources.displayMetrics.scaledDensity * LETTER_SP

    private val labelColor = Theming.color(ctx, R.attr.chorusTextMuted)

    /** Replace the rail's contents; an empty list is how the host hides it. */
    fun setIndex(next: List<Entry>) {
        entries = next
        lastJumped = ' '
        visibility = if (next.isEmpty()) GONE else VISIBLE
        invalidate()
    }

    private fun slotPx(): Float =
        if (entries.isEmpty() || height == 0) 1f
        else (height - insetPx * 2f) / entries.size

    override fun onDraw(canvas: Canvas) {
        if (entries.isEmpty()) return
        // One letter per slot, and never bigger than its slot: 26 entries in a strip this
        // tall is a comfortable ~9sp on a phone, but a short screen (or cycle mode's bigger
        // text) would otherwise run the labels into each other.
        labelPaint.textSize = min(labelSizePx, slotPx() * 0.7f)
        labelPaint.color = labelColor
        val slot = slotPx()
        val baseline = insetPx + slot / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
        entries.forEachIndexed { i, entry ->
            canvas.drawText(entry.letter.toString(), width / 2f, baseline + slot * i, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (entries.isEmpty()) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val index = ((event.y - insetPx) / slotPx()).toInt().coerceIn(0, entries.lastIndex)
                if (entries[index].letter != lastJumped) {
                    lastJumped = entries[index].letter
                    onJump?.invoke(entries[index].position)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastJumped = ' '
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // Part of the click contract for accessibility: the rail is a touch target, so it has
    // to answer performClick even though the jump happens on ACTION_DOWN.
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        /** Label size in sp; trimmed to the slot at draw time if that is smaller. */
        const val LETTER_SP = 9f
        const val INSET_DP = 7f
    }
}
