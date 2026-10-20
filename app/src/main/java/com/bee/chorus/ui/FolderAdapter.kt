package com.bee.chorus.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bee.chorus.R

/**
 * RecyclerView adapter for one level of the folder browser: the subfolders of the
 * current directory as (folder name, tracks-in-subtree) pairs.
 *
 * Rows come from `R.layout.item_folder` (glyph badge, name, muted count, chevron) —
 * the folder twin of [PlaylistAdapter]. Tapping fires [onClick] with the folder's
 * name; the panel resolves it against the current path.
 */
class FolderAdapter(
    var onClick: ((String) -> Unit)? = null
) : RecyclerView.Adapter<FolderAdapter.VH>() {

    private val items = ArrayList<Pair<String, Int>>()

    /** Replace the contents and rebind. */
    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Pair<String, Int>>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.title)
        private val count: TextView = view.findViewById(R.id.count)

        init {
            // Set once: resolves its own position at click time, so no re-binding and
            // no allocation per bind. getOrNull rather than items[p] — a stale index is
            // a no-op instead of an exception.
            itemView.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) {
                    items.getOrNull(p)?.let { onClick?.invoke(it.first) }
                }
            }
        }

        fun bind(item: Pair<String, Int>) {
            title.text = item.first
            count.text = if (item.second == 1) "1 track" else "${item.second} tracks"
        }
    }

    // Distinct view type: FolderPanel stacks this adapter ahead of a TrackAdapter with
    // ConcatAdapter, which namespaces view types per inner adapter anyway — reporting
    // one explicitly keeps that mapping trivial (and would matter if the panel's list
    // ever shared a RecycledViewPool with another folder list).
    override fun getItemViewType(position: Int) = VIEW_TYPE

    private companion object {
        const val VIEW_TYPE = 4
    }
}
