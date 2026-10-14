package com.bee.chorus.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.bee.chorus.R
import com.bee.chorus.data.Track

/**
 * RecyclerView adapter for a flat list of tracks.
 *
 * Rows come from `R.layout.item_track` (52dp rounded art, bold title,
 * "artist • album • year", tabular duration on the right).
 *
 * Tapping a row fires [onClick] with the *bound* position and the current list, so the
 * host can do `PlayerHolder.play(list, index)` without holding its own copy.
 *
 * Bind path discipline: no I/O, no decoding, no listener objects. The click listener
 * and the artwork shape are built once per recycled ViewHolder; every bind only reads
 * fields and hands a cache key to [Art].
 */
class TrackAdapter(
    var onClick: ((Int, List<Track>) -> Unit)? = null,
    var onLongClick: ((Track) -> Unit)? = null
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    private val items = ArrayList<Track>()

    /** Replace the contents and rebind. */
    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Track>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val art: ImageView = view.findViewById(R.id.art)
        private val title: TextView = view.findViewById(R.id.title)
        private val subtitle: TextView = view.findViewById(R.id.subtitle)
        private val duration: TextView = view.findViewById(R.id.duration)

        init {
            // Why in code, not a drawable resource: the row owns its own corner radius,
            // and a GradientDrawable is both the tinted placeholder and the outline
            // source that lets clipToOutline round the bitmap Art hands us.
            art.background = roundedSurface(view.context, ART_CORNER_DP)
            art.clipToOutline = true

            // Set once: the handler resolves its own position at click time, so it never
            // needs re-binding and costs no allocation per bind.
            itemView.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick?.invoke(p, items)
            }
            // Set once like the click listener; returns the bound Track at gesture time.
            itemView.setOnLongClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION && onLongClick != null) {
                    items.getOrNull(p)?.let { onLongClick?.invoke(it) }
                    true
                } else {
                    false
                }
            }
        }

        fun bind(t: Track) {
            title.text = t.title.ifBlank { UNKNOWN_TITLE }

            // MediaStore drops fields it cannot fill in, so build from the parts that
            // actually exist instead of concatenating blindly (which left a leading
            // " — " and showed a bogus "0" year).
            val detail = joinExisting(t.artist, t.album, yearText(t.year))
            subtitle.text = detail
            subtitle.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE

            duration.text = formatDuration(t.durationMs)
            Art.load(itemView.context, art, t.albumArtUri, ART_PX)
        }
    }

    private companion object {
        /** Decode target for the 52dp thumbnail. 128px is what QueuePanel uses for this
         *  same layout, so both share Art's cache entry instead of holding two copies. */
        const val ART_PX = 128
        const val ART_CORNER_DP = 8f
        const val SEPARATOR = "  •  "
        const val UNKNOWN_TITLE = "Unknown title"
        const val UNKNOWN_DURATION = "--:--"

        /**
         * Joins only the non-empty parts with [SEPARATOR], so a missing year or album
         * never leaves a dangling or doubled separator. Single StringBuilder, no vararg.
         */
        fun joinExisting(first: String, second: String, third: String): String {
            val sb = StringBuilder(48)
            appendIfPresent(sb, first)
            appendIfPresent(sb, second)
            appendIfPresent(sb, third)
            return sb.toString()
        }

        fun appendIfPresent(sb: StringBuilder, part: String) {
            // isBlank, not isEmpty: a whitespace-only album/title would otherwise still
            // emit a separator on its own.
            if (part.isBlank()) return
            if (sb.isNotEmpty()) sb.append(SEPARATOR)
            sb.append(part)
        }

        /** MediaStore.Audio.Media.YEAR is 0 (or junk) for plenty of files — omit it then. */
        fun yearText(year: Int): String = if (year > 0) year.toString() else ""

        /** mm:ss, or h:mm:ss past an hour; unknown durations read as --:-- . */
        fun formatDuration(ms: Int): String {
            if (ms <= 0) return UNKNOWN_DURATION
            val total = ms / 1000
            val seconds = total % 60
            val minutes = (total / 60) % 60
            val hours = total / 3600
            return if (hours > 0) "$hours:" + two(minutes) + ":" + two(seconds)
            else "$minutes:" + two(seconds)
        }

        fun two(value: Int): String = if (value < 10) "0$value" else value.toString()

        /**
         * Rounded placeholder for artwork: a hair lighter than the dark theme's
         * background, a hair darker than the light (Cycle) theme's, so rows look
         * intentional while nothing is decoded yet. Built once per ViewHolder.
         */
        fun roundedSurface(ctx: Context, cornerDp: Float): GradientDrawable {
            val base = themeColor(ctx, android.R.attr.colorBackground, Color.BLACK)
            val fill = if (ColorUtils.calculateLuminance(base) > 0.5f) {
                ColorUtils.blendARGB(base, Color.BLACK, 0.08f)
            } else {
                ColorUtils.blendARGB(base, Color.WHITE, 0.10f)
            }
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerDp * ctx.resources.displayMetrics.density
                setColor(fill)
            }
        }

        fun themeColor(ctx: Context, attr: Int, fallback: Int): Int {
            val out = TypedValue()
            val resolved = ctx.theme.resolveAttribute(attr, out, true) &&
                out.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT
            return if (resolved) out.data else fallback
        }
    }
}
