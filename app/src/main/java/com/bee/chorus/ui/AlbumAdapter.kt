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
import com.bee.chorus.data.Album

/**
 * RecyclerView adapter for the album list.
 *
 * Rows come from `R.layout.item_album` (64dp rounded art, bold album title,
 * "artist • year • N tracks", muted chevron).
 *
 * The year is omitted entirely when the device does not have a usable one:
 * `MediaStore.Audio.Media.YEAR` is 0 for many files, which used to render rows
 * like "ABC • 0 • 20 tracks".
 *
 * Bind path discipline: no I/O, no decoding, no listener objects. The click listener
 * and the artwork shape are built once per recycled ViewHolder.
 */
class AlbumAdapter(
    var onClick: ((Album) -> Unit)? = null
) : RecyclerView.Adapter<AlbumAdapter.VH>() {

    private val items = ArrayList<Album>()

    /** Replace the contents and rebind. */
    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Album>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_album, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val art: ImageView = view.findViewById(R.id.art)
        private val title: TextView = view.findViewById(R.id.title)
        private val subtitle: TextView = view.findViewById(R.id.subtitle)

        init {
            // Why in code, not a drawable resource: the row owns its own corner radius,
            // and a GradientDrawable is both the tinted placeholder and the outline
            // source that lets clipToOutline round the bitmap Art hands us.
            art.background = roundedSurface(view.context, ART_CORNER_DP)
            art.clipToOutline = true

            // Set once: resolves its own position at click time, so no re-binding and
            // no allocation per bind.
            itemView.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) items[p]?.let { onClick?.invoke(it) }
            }
        }

        fun bind(a: Album) {
            title.text = a.title.ifBlank { UNKNOWN_ALBUM }

            // Only the parts that exist, so a year of 0 disappears instead of showing
            // "ABC • 0 • 20 tracks", and no separator is ever left dangling.
            val detail = joinExisting(a.artist, yearText(a.year), trackCountText(a.trackCount))
            subtitle.text = detail
            subtitle.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE

            Art.load(itemView.context, art, a.artUri, ART_PX)
        }
    }

    private companion object {
        /** Decode target for the 64dp album thumbnail — enough for 3x screens without
         *  holding a near-full-resolution bitmap (the previous 256 was ~2x oversized). */
        const val ART_PX = 192
        const val ART_CORNER_DP = 10f
        const val SEPARATOR = "  •  "
        const val UNKNOWN_ALBUM = "Unknown album"

        /**
         * Joins only the non-empty parts with [SEPARATOR], so a missing year never
         * leaves a dangling or doubled separator. Single StringBuilder, no vararg.
         */
        fun joinExisting(first: String, second: String, third: String): String {
            val sb = StringBuilder(48)
            appendIfPresent(sb, first)
            appendIfPresent(sb, second)
            appendIfPresent(sb, third)
            return sb.toString()
        }

        fun appendIfPresent(sb: StringBuilder, part: String) {
            // isBlank, not isEmpty: a whitespace-only artist would otherwise still emit
            // a separator on its own.
            if (part.isBlank()) return
            if (sb.isNotEmpty()) sb.append(SEPARATOR)
            sb.append(part)
        }

        /** MediaStore.Audio.Media.YEAR is 0 (or junk) for plenty of files — omit it then. */
        fun yearText(year: Int): String = if (year > 0) year.toString() else ""

        fun trackCountText(count: Int): String = when {
            count <= 0 -> ""
            count == 1 -> "1 track"
            else -> "$count tracks"
        }

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
