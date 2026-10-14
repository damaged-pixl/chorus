package com.bee.chorus.ui

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.Player
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bee.chorus.R
import com.bee.chorus.playback.PlayerHolder

/**
 * The shared player's queue: what is playing now plus everything ahead of it.
 *
 * Pure view over [PlayerHolder] — it holds a snapshot of the queue for display and never owns
 * playback. Tapping a row seeks the real controller to that index; the Shuffle/Repeat buttons
 * delegate to [PlayerHolder] and re-read the resulting state afterwards.
 *
 * The queue is rebuilt only when its contents actually change (a cheap content signature), so
 * ordinary playback events do not churn the adapter.
 */
class QueuePanel @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : LinearLayout(ctx, attrs) {

    private val shuffleButton: Button
    private val repeatButton: Button
    private val nowPlaying: TextView
    private val listView: RecyclerView

    private val adapter = QueueAdapter { position ->
        PlayerHolder.whenReady { c ->
            if (position in 0 until c.mediaItemCount) {
                c.seekTo(position, 0L)
                c.play()
            }
        }
    }

    private var observing = false

    /** Changes when the queue contents change; identical signature means "same list". */
    private var contentSignature: String? = null

    // Same instance is handed back to removePlayerListener, which removes by identity.
    private val playerListener: (Player) -> Unit = { player -> render(player) }

    init {
        orientation = LinearLayout.VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_queue, this, true)

        shuffleButton = findViewById(R.id.queue_shuffle)
        repeatButton = findViewById(R.id.queue_repeat)
        nowPlaying = findViewById(R.id.queue_now_playing)
        listView = findViewById(R.id.queue_list)

        listView.layoutManager = LinearLayoutManager(context)
        listView.adapter = adapter

        shuffleButton.setOnClickListener {
            PlayerHolder.toggleShuffle()
            PlayerHolder.whenReady { c -> renderToggles(c) }
        }

        repeatButton.setOnClickListener {
            PlayerHolder.cycleRepeat()
            PlayerHolder.whenReady { c -> renderToggles(c) }
        }

        renderToggles(null)
    }

    // ---------------------------------------------------------------- public

    /** Start observing the shared player. Idempotent. */
    fun show() {
        if (observing) return
        observing = true
        PlayerHolder.addPlayerListener(playerListener)
    }

    /** Stop observing. Safe to call when not attached. */
    fun hide() {
        if (observing) {
            observing = false
            PlayerHolder.removePlayerListener(playerListener)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        show()
    }

    override fun onDetachedFromWindow() {
        hide()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- render

    private fun render(player: Player) {
        renderToggles(player)
        renderNowPlaying(player)
        syncQueue(player)
    }

    private fun renderToggles(player: Player?) {
        val on = player?.shuffleModeEnabled == true
        shuffleButton.text = if (on) SHUFFLE_ON else SHUFFLE_OFF
        shuffleButton.isSelected = on
        shuffleButton.alpha = if (on) ON_ALPHA else OFF_ALPHA

        val repeat = player?.repeatMode ?: Player.REPEAT_MODE_OFF
        val repeatLabel = when (repeat) {
            Player.REPEAT_MODE_ONE -> REPEAT_ONE
            Player.REPEAT_MODE_ALL -> REPEAT_ALL
            else -> REPEAT_OFF
        }
        repeatButton.text = repeatLabel
        repeatButton.isSelected = repeat != Player.REPEAT_MODE_OFF
        repeatButton.alpha = if (repeat != Player.REPEAT_MODE_OFF) ON_ALPHA else OFF_ALPHA
    }

    private fun renderNowPlaying(player: Player) {
        val metadata = player.currentMediaItem?.mediaMetadata
        if (metadata == null) {
            nowPlaying.text = NOTHING_PLAYING
            return
        }
        val title = metadata.title?.toString().orEmpty().ifBlank { UNKNOWN_TITLE }
        val artist = metadata.artist?.toString().orEmpty().ifBlank { UNKNOWN_ARTIST }
        nowPlaying.text = if (player.isPlaying) "$title  —  $artist" else "$title  —  $artist  (paused)"
    }

    private fun syncQueue(player: Player) {
        val count = player.mediaItemCount
        if (count <= 0) {
            if (contentSignature != null) {
                contentSignature = null
                adapter.submit(emptyList())
            }
            return
        }

        val signature = queueSignature(player, count)
        if (signature != contentSignature) {
            contentSignature = signature
            val rows = ArrayList<QueueRow>(count)
            for (i in 0 until count) {
                val metadata = player.getMediaItemAt(i).mediaMetadata
                rows.add(
                    QueueRow(
                        title = metadata.title?.toString().orEmpty().ifBlank { UNKNOWN_TITLE },
                        artist = metadata.artist?.toString().orEmpty().ifBlank { UNKNOWN_ARTIST },
                        album = metadata.albumTitle?.toString().orEmpty(),
                        artworkUri = metadata.artworkUri
                    )
                )
            }
            adapter.submit(rows)
        }

        // The playing marker is state, not content: refresh it on every event.
        adapter.setCurrentIndex(player.currentMediaItemIndex)
    }

    /**
     * Cheap identity of the current queue contents. PlayerHolder replaces the whole queue at
     * once, so count + first + last + current is enough to notice a change without walking
     * every item on every playback event.
     */
    private fun queueSignature(player: Player, count: Int): String {
        val first = player.getMediaItemAt(0).mediaId
        val last = player.getMediaItemAt(count - 1).mediaId
        val current = player.currentMediaItem?.mediaId.orEmpty()
        return "$count|$first|$last|$current"
    }

    // ---------------------------------------------------------------- adapter

    private data class QueueRow(
        val title: String,
        val artist: String,
        val album: String,
        val artworkUri: Uri?
    )

    /**
     * Queue rows reuse the shared `item_track` layout (art + title + "artist — album" +
     * right-hand slot). The right-hand slot shows the 1-based queue position; the current item
     * is marked with a "▶" prefix and full opacity.
     */
    private inner class QueueAdapter(
        private val onRowClick: (Int) -> Unit
    ) : RecyclerView.Adapter<QueueAdapter.VH>() {

        private val items = ArrayList<QueueRow>()
        private var currentIndex = RecyclerView.NO_POSITION

        @Suppress("NotifyDataSetChanged")
        fun submit(rows: List<QueueRow>) {
            items.clear()
            items.addAll(rows)
            notifyDataSetChanged()
        }

        @Suppress("NotifyDataSetChanged")
        fun setCurrentIndex(index: Int) {
            if (index == currentIndex) return
            val previous = currentIndex
            currentIndex = index
            if (previous != RecyclerView.NO_POSITION) notifyItemChanged(previous)
            if (index != RecyclerView.NO_POSITION) notifyItemChanged(index)
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val art: ImageView = view.findViewById(R.id.art)
            private val title: TextView = view.findViewById(R.id.title)
            private val subtitle: TextView = view.findViewById(R.id.subtitle)
            private val position: TextView = view.findViewById(R.id.duration)

            fun bind(row: QueueRow) {
                val playing = bindingAdapterPosition == currentIndex
                title.text = if (playing) "$PLAYING_GLYPH ${row.title}" else row.title
                title.alpha = if (playing) ON_ALPHA else OFF_ALPHA

                val detail = listOfNotNull(
                    row.artist.takeIf { it.isNotBlank() && it != UNKNOWN_ARTIST },
                    row.album.takeIf { it.isNotBlank() }
                ).joinToString(" — ").ifBlank { UNKNOWN_ARTIST }
                subtitle.text = detail

                position.text = (bindingAdapterPosition + 1).toString()

                val uri = row.artworkUri
                if (uri != null) {
                    // lowMem=true keeps this identical to the track list's decode, so the
                    // two share one Art cache entry (same uri@128#low key) instead of
                    // holding a full-quality copy each.
                    Art.load(itemView.context, art, uri, 128, lowMem = true)
                } else {
                    art.setImageDrawable(null)
                }

                itemView.setOnClickListener {
                    val p = bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) onRowClick(p)
                }
            }
        }
    }

    private companion object {
        const val SHUFFLE_OFF = "Shuffle"
        const val SHUFFLE_ON = "Shuffle on"
        const val REPEAT_OFF = "Repeat"
        const val REPEAT_ALL = "Repeat all"
        const val REPEAT_ONE = "Repeat one"
        const val PLAYING_GLYPH = "▶"
        const val NOTHING_PLAYING = "Nothing playing"
        const val UNKNOWN_TITLE = "Unknown title"
        const val UNKNOWN_ARTIST = "Unknown artist"
        const val ON_ALPHA = 1f
        const val OFF_ALPHA = 0.7f
    }
}