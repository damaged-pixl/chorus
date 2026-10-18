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
import com.bee.chorus.data.Favourites
import com.bee.chorus.data.Track

/**
 * RecyclerView adapter for a flat list of tracks.
 *
 * Rows come from `R.layout.item_track` (56dp rounded art, bold title,
 * "artist • album • year", tabular duration on the right, favourite star).
 *
 * Tapping a row fires [onClick] with the *bound* position and the current list, so the
 * host can do `PlayerHolder.play(list, index)` without holding its own copy.
 *
 * Bind path discipline: no I/O, no decoding, no listener objects. The click listener, the
 * artwork shape, the now-playing cue and the star tint are built once per recycled
 * ViewHolder; every bind only reads fields and hands a cache key to [Art].
 */
class TrackAdapter(
    var onClick: ((Int, List<Track>) -> Unit)? = null,
    var onLongClick: ((Track) -> Unit)? = null
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    private val items = ArrayList<Track>()

    /**
     * MediaStore id of the track the shared player currently holds, or null when nothing is
     * loaded. The host pushes it down (MainActivity reads the one PlayerHolder controller);
     * a matching row then gets the now-playing cue — an accent bar on its leading edge over
     * a faintly tinted row.
     *
     * Pushed rather than observed, for the same reason as [observeFavourites]: a listener
     * registered from inside the adapter would need a host-lifecycle pairing this class
     * cannot make safely.
     */
    var currentTrackId: Long? = null
        set(value) {
            val previous = field
            if (previous == value) return
            field = value
            // Only the two rows that changed need rebinding.
            for (i in items.indices) {
                if (items[i].id == previous || items[i].id == value) notifyItemChanged(i)
            }
        }

    /**
     * The favourite star owns its own wiring: its state is read from [Favourites] at bind
     * time and a tap toggles it, so every list built on this adapter (tracks, search,
     * playlist detail, Favourites) gets the star for free. External changes re-bind only
     * while the adapter is observing — see [observeFavourites] for why the host, not the
     * adapter, owns that window.
     */
    private val favouriteListener: (Long, Boolean) -> Unit = { trackId, _ ->
        if (trackId == Favourites.ALL) {
            notifyDataSetChanged()
        } else {
            // Only the rows showing this track changed; re-bind exactly those.
            for (i in items.indices) {
                if (items[i].id == trackId) notifyItemChanged(i)
            }
        }
    }

    private var observingFavourites = false

    /** Replace the contents and rebind. */
    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Track>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /**
     * Start re-binding stars when [Favourites] changes. Pair with
     * [stopObservingFavourites] in the *host's* lifecycle — Activity
     * `onCreate`/`onDestroy`, or a view's `onAttachedToWindow`/`onDetachedFromWindow`.
     * Idempotent.
     *
     * The adapter deliberately does not do this itself from
     * `onAttachedToRecyclerView`/`onDetachedFromRecyclerView`: RecyclerView only calls
     * those on an adapter swap (`setAdapter`), never on window detach or Activity
     * destroy, so every `recreate()` appended another dead adapter to the static
     * `Favourites.listeners` for the life of the process.
     */
    fun observeFavourites() {
        if (observingFavourites) return
        observingFavourites = true
        Favourites.addListener(favouriteListener)
    }

    /** Undo [observeFavourites]. Safe to call when it was never observing. */
    fun stopObservingFavourites() {
        if (!observingFavourites) return
        observingFavourites = false
        Favourites.removeListener(favouriteListener)
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
        private val star: TextView = view.findViewById(R.id.track_star)
        private val cue: View = view.findViewById(R.id.now_playing_bar)

        /** Off-star tint, resolved once per recycled holder, not per bind. */
        private val starOffColor = Theming.color(view.context, R.attr.chorusTextMuted)

        /** On-star tint: the amber accent, darkened on a light page so it still reads. */
        private val starOnColor = Theming.color(view.context, R.attr.chorusAccentText)

        /** The row's two press/tint backgrounds, built once — a bind just swaps them. */
        private val rowIdle = Ui.rowBackground(view.context, cue = false)
        private val rowPlaying = Ui.rowBackground(view.context, cue = true)

        /** Whether the row was drawn as the playing one, so a rebind does not re-set it. */
        private var showingCue = false

        init {
            // Why in code, not a drawable resource: the row owns its own corner radius,
            // and a GradientDrawable is both the tinted placeholder and the outline
            // source that lets clipToOutline round the bitmap Art hands us.
            art.background = roundedSurface(view.context, ART_CORNER_DP)
            art.clipToOutline = true

            // Cue colours are read off the theme here rather than in the layout: a @color
            // resolves by configuration, not by theme, so it would ignore cycle mode.
            cue.background = Ui.accentBar(view.context)

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
            // The star consumes its own taps (it is clickable), so toggling never also
            // fires the row's play action. Resolves its own position like the others.
            star.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) items.getOrNull(p)?.let { Favourites.toggle(it.id) }
            }
        }

        fun bind(t: Track) {
            title.text = t.title.ifBlank { UNKNOWN_TITLE }

            // Now-playing cue: an accent bar down the leading edge over a faintly tinted
            // row. Both are fills — the accent is never used as text, which would be
            // unreadable on a light page. The bar's width is always reserved by the layout
            // (invisible, not gone) so rows never shift as the cue moves, and the tint
            // swaps between two prebuilt backgrounds rather than redrawing a drawable.
            val playing = t.id == currentTrackId
            cue.visibility = if (playing) View.VISIBLE else View.INVISIBLE
            if (playing != showingCue) {
                showingCue = playing
                itemView.background = if (playing) rowPlaying else rowIdle
            }

            // MediaStore drops fields it cannot fill in, so build from the parts that
            // actually exist instead of concatenating blindly (which left a leading
            // " — " and showed a bogus "0" year).
            val detail = joinExisting(t.artist, t.album, yearText(t.year))
            subtitle.text = detail
            subtitle.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE

            duration.text = formatDuration(t.durationMs)
            Art.load(itemView.context, art, t.albumArtUri, ART_PX, lowMem = true)

            // Favourite star: shown (the layout defaults it to gone so QueuePanel's copy
            // of this row stays untouched) and tinted from the shared Favourites set.
            val liked = Favourites.isFavourite(t.id)
            star.visibility = View.VISIBLE
            star.text = if (liked) STAR_ON else STAR_OFF
            star.setTextColor(if (liked) starOnColor else starOffColor)
            star.contentDescription =
                if (liked) "Favourite, tap to remove" else "Not a favourite, tap to add"
        }
    }

    // Distinct view type: MainActivity shares one RecycledViewPool across the three
    // main lists and the pool is keyed by view type only — reporting 0 like every
    // other adapter would let a recycled row reach a list that inflates another
    // layout (ClassCastException at bind time).
    override fun getItemViewType(position: Int) = VIEW_TYPE

    private companion object {
        const val VIEW_TYPE = 1
        /** Decode target for the 56dp thumbnail. 128px is what QueuePanel uses for this
         *  same layout, so both share Art's cache entry instead of holding two copies. */
        const val ART_PX = 128
        const val ART_CORNER_DP = 8f
        const val SEPARATOR = "  •  "
        const val UNKNOWN_TITLE = "Unknown title"
        const val UNKNOWN_DURATION = "--:--"

        // Favourite star glyphs. The "on" tint is the themed accent, resolved per
        // ViewHolder rather than baked in here.
        const val STAR_ON = "★"
        const val STAR_OFF = "☆"

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
