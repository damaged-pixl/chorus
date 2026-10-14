package com.bee.chorus.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.bee.chorus.R

/**
 * RecyclerView adapter for saved playlists, backed by `PlaylistStore.listPlaylists()`
 * i.e. a list of (playlist id, name).
 *
 * Rows come from `R.layout.item_playlist` (circular glyph badge, name, muted chevron).
 * The store only hands back id + name here, so the row deliberately invents nothing
 * else. Tapping fires [onClick] with the id and name.
 */
class PlaylistAdapter(
    var onClick: ((Long, String) -> Unit)? = null
) : RecyclerView.Adapter<PlaylistAdapter.VH>() {

    private val items = ArrayList<Pair<Long, String>>()

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

        init {
            // Why code, not a drawable resource: the badge is a tinted circle behind a
            // text glyph, so a programmatic shape keeps the APK free of another asset
            // and costs one tiny drawable per recycled holder — never on the bind path.
            val badge: TextView = view.findViewById(R.id.glyph)
            badge.background = badgeFill(view.context)

            // Set once: resolves its own position at click time, so no re-binding and
            // no allocation per bind.
            itemView.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) items[p]?.let { onClick?.invoke(it.first, it.second) }
            }
        }

        fun bind(item: Pair<Long, String>) {
            title.text = item.second.ifBlank { UNNAMED }
        }
    }

    private companion object {
        const val UNNAMED = "Untitled playlist"

        /** Circular badge fill: a hair off the window background in either theme. */
        fun badgeFill(ctx: Context): GradientDrawable {
            val base = themeColor(ctx, android.R.attr.colorBackground, Color.BLACK)
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

        fun themeColor(ctx: Context, attr: Int, fallback: Int): Int {
            val out = TypedValue()
            val resolved = ctx.theme.resolveAttribute(attr, out, true) &&
                out.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT
            return if (resolved) out.data else fallback
        }
    }
}
