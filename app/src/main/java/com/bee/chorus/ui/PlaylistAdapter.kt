package com.bee.chorus.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
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

/**
 * RecyclerView adapter for saved playlists, backed by `PlaylistStore.listPlaylists()`
 * i.e. a list of (playlist id, name).
 *
 * Rows come from `R.layout.item_playlist` (cover, name, muted chevron). The store only
 * hands back id + name, so the cover is *pushed in* by the host with [setCovers] after it
 * has built the mosaics off the main thread — the bind path stays free of I/O, as every
 * bind path here is. Tapping fires [onClick] with the id and name.
 */
class PlaylistAdapter(
    var onClick: ((Long, String) -> Unit)? = null,
    var onLongClick: ((Long, String) -> Unit)? = null
) : RecyclerView.Adapter<PlaylistAdapter.VH>() {

    private val items = ArrayList<Pair<Long, String>>()

    /** Playlist id -> cover mosaic. Built off the main thread and pushed in wholesale. */
    private val covers = HashMap<Long, Bitmap>()

    /** Replace the covers and rebind. Missing ids fall back to the drawn badge. */
    @SuppressLint("NotifyDataSetChanged")
    fun setCovers(map: Map<Long, Bitmap>) {
        covers.clear()
        covers.putAll(map)
        notifyDataSetChanged()
    }

    /** Replace the contents and rebind. */
    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Pair<Long, String>>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.title)
        private val coverView: ImageView = view.findViewById(R.id.cover)

        init {
            // Why code, not a drawable resource: the badge is a tinted circle behind a
            // text glyph, so a programmatic shape keeps the APK free of another asset
            // and costs one tiny drawable per recycled holder — never on the bind path.
            val badge: TextView = view.findViewById(R.id.glyph)
            badge.background = badgeFill(view.context)

            // The cover reuses that shape as its clip, so a mosaic is rounded exactly like
            // an album's art and there is no second radius to keep in step.
            coverView.background = roundedSurface(view.context, COVER_CORNER_DP)
            coverView.clipToOutline = true

            // Set once: resolves its own position at click time, so no re-binding and
            // no allocation per bind. getOrNull (as the long-press handler below) rather
            // than items[p] — a stale index is a no-op instead of an exception.
            itemView.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) {
                    items.getOrNull(p)?.let { onClick?.invoke(it.first, it.second) }
                }
            }
            // Long-press opens the Rename/Delete sheet; resolves its own position like
            // the click handler so it needs no re-binding.
            itemView.setOnLongClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION && onLongClick != null) {
                    items.getOrNull(p)?.let { onLongClick?.invoke(it.first, it.second) }
                    true
                } else {
                    false
                }
            }
        }

        fun bind(item: Pair<Long, String>) {
            title.text = item.second.ifBlank { UNNAMED }
            // The cover sits over the badge: showing it means the playlist has art, and
            // hiding it hands the row back to the badge, which is always drawn underneath.
            val cover = covers[item.first]
            if (cover != null) {
                coverView.setImageBitmap(cover)
                coverView.visibility = View.VISIBLE
            } else {
                coverView.setImageDrawable(null)
                coverView.visibility = View.GONE
            }
        }
    }

    // Distinct view type: MainActivity shares one RecycledViewPool across the three
    // main lists and the pool is keyed by view type only — see TrackAdapter.
    override fun getItemViewType(position: Int) = VIEW_TYPE

    private companion object {
        const val VIEW_TYPE = 3
        const val UNNAMED = "Untitled playlist"

        /** Corner for the 44dp cover — the same shape as badgeFill, as a square. */
        const val COVER_CORNER_DP = 6f

        /**
         * Rounded surface for the cover. The same idea as the album and track adapters'
         * art surfaces (a hair off the themed page background, so the row looks
         * intentional while nothing is decoded yet) — a copy, because each adapter here
         * is self-contained and the two others each carry their own.
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

        /** Circular badge fill: a hair off the themed page background, either way round. */
        fun badgeFill(ctx: Context): GradientDrawable {
            val base = Theming.color(ctx, R.attr.chorusBg)
            val fill = if (ColorUtils.calculateLuminance(base) > 0.5f) {
                ColorUtils.blendARGB(base, Color.BLACK, 0.08f)
            } else {
                ColorUtils.blendARGB(base, Color.WHITE, 0.12f)
            }
            return GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
            }
        }
    }
}
