package com.bee.chorus.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
 * One artist on the [ArtistPanel] root list: a display name and how many albums they have.
 * Lives here so the artist rows reuse [item_album]'s shape (64dp art, title, subtitle)
 * through the same adapter, rather than a near-duplicate row type. [artAlbum] is the
 * album whose artwork stands in for the artist — the first of theirs the panel hands over.
 */
data class ArtistRow(
    val name: String,
    val albumCount: Int,
    val artAlbum: Album
)

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
 *
 * It serves the artist list too: [submitArtists] switches it to [ArtistRow] rows, whose
 * click fires [onArtistClick] instead of [onClick].
 */
class AlbumAdapter(
    var onClick: ((Album) -> Unit)? = null,
    var onLongClick: ((Album) -> Unit)? = null,
    var onArtistClick: ((ArtistRow) -> Unit)? = null
) : RecyclerView.Adapter<AlbumAdapter.VH>() {

    private val items = ArrayList<Album>()
    private var artists: List<ArtistRow> = emptyList()

    /** Replace the album contents and rebind (artist rows are cleared). */
    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Album>) {
        items.clear()
        items.addAll(list)
        artists = emptyList()
        notifyDataSetChanged()
    }

    /** Replace the artist contents and rebind (album rows are cleared). */
    @SuppressLint("NotifyDataSetChanged")
    fun submitArtists(list: List<ArtistRow>) {
        artists = list
        items.clear()
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = if (artists.isNotEmpty()) artists.size else items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_album, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        // Two row shapes over one layout: an artist (when submitArtists was last used)
        // or an album. Decided by which list is populated, so no view-type juggling.
        if (artists.isNotEmpty()) holder.bindArtist(artists[position])
        else items.getOrNull(position)?.let { holder.bind(it) }
    }

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
            // no allocation per bind. getOrNull (as the long-press handler below) rather
            // than items[p] — a stale index is a no-op instead of an exception.
            itemView.setOnClickListener {
                val p = bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) return@setOnClickListener
                // Artist rows are up when submitArtists was last used; they fire the
                // artist callback, albums the album one. Long-press is albums-only.
                val artist = artists.getOrNull(p)
                if (artist != null) {
                    onArtistClick?.invoke(artist)
                } else {
                    items.getOrNull(p)?.let { onClick?.invoke(it) }
                }
            }
            // Set once like the click listener; returns the bound Album at gesture time.
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

        fun bind(a: Album) {
            title.text = a.title.ifBlank { UNKNOWN_ALBUM }

            // Only the parts that exist, so a year of 0 disappears instead of showing
            // "ABC • 0 • 20 tracks", and no separator is ever left dangling.
            val detail = joinExisting(a.artist, yearText(a.year), trackCountText(a.trackCount))
            subtitle.text = detail
            subtitle.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE

            Art.load(itemView.context, art, a.artUri, ART_PX, lowMem = true)
        }

        /** Artist row: their name as the title, album count as the subtitle, first
         *  album's art as the stand-in. Same row shape as an album, by design. */
        fun bindArtist(row: ArtistRow) {
            title.text = row.name
            subtitle.text = when (row.albumCount) {
                1 -> "1 album"
                else -> "${row.albumCount} albums"
            }
            subtitle.visibility = View.VISIBLE
            Art.load(itemView.context, art, row.artAlbum.artUri, ART_PX, lowMem = true)
        }
    }

    // Distinct view type: MainActivity shares one RecycledViewPool across the three
    // main lists and the pool is keyed by view type only — see TrackAdapter.
    override fun getItemViewType(position: Int) = VIEW_TYPE

    private companion object {
        const val VIEW_TYPE = 2
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
         * Rounded placeholder for artwork: a hair lighter than a dark page's background, a
         * hair darker than a light one, so rows look intentional while nothing is decoded
         * yet. Reads the themed page colour, so it follows day/night and cycle mode.
         * Built once per ViewHolder.
         */
        fun roundedSurface(ctx: Context, cornerDp: Float): GradientDrawable {
            val base = Theming.color(ctx, R.attr.chorusBg)
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
    }
}
