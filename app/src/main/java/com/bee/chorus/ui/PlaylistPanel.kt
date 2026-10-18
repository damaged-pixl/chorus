package com.bee.chorus.ui

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bee.chorus.R
import com.bee.chorus.data.Favourites
import com.bee.chorus.data.PlaylistStore
import com.bee.chorus.data.Track
import com.bee.chorus.playback.PlayerHolder

/**
 * Playlist detail: header, Play all / Shuffle all, and the playlist's tracks.
 *
 * The panel is a pure renderer over injected state — it does not scan the library and does not
 * own the database. The host injects both:
 *  - [setStore] with a [PlaylistStore] (the host keeps ownership; the panel never closes it),
 *  - [setLibrary] with the current library so playlist track ids can be resolved to [Track]s.
 * Then [open] selects a playlist. Either injection order works: setting either one while a
 * playlist is already open refreshes the contents.
 *
 * A long press on a row removes that track from the playlist (the short press still plays), so
 * no per-row button is needed.
 */
class PlaylistPanel @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : LinearLayout(ctx, attrs) {

    private val nameView: TextView
    private val countView: TextView
    private val playAllButton: Button
    private val shuffleAllButton: Button
    private val listView: RecyclerView

    private val adapter = TrackAdapter()

    private var store: PlaylistStore? = null
    private var library: Map<Long, Track> = emptyMap()

    private var playlistId: Long = NO_PLAYLIST
    private var playlistName: String = ""
    private var tracks: List<Track> = emptyList()

    /**
     * A long press removes a row; the click that follows must not also start playback, so row
     * clicks are ignored for a moment after a removal.
     */
    private var ignoreClicksUntil = 0L

    init {
        orientation = LinearLayout.VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_playlist, this, true)

        nameView = findViewById(R.id.playlist_name)
        countView = findViewById(R.id.playlist_count)
        playAllButton = findViewById(R.id.playlist_play_all)
        shuffleAllButton = findViewById(R.id.playlist_shuffle_all)
        listView = findViewById(R.id.playlist_list)

        listView.layoutManager = LinearLayoutManager(context)
        listView.adapter = adapter

        adapter.onClick = { index, list ->
            if (SystemClock.elapsedRealtime() >= ignoreClicksUntil && index in list.indices) {
                PlayerHolder.play(list, index)
            }
        }

        // Rows are recycled and TrackAdapter owns the click listener, so the long-press
        // listener is attached as each row is attached rather than baked into the adapter.
        listView.addOnChildAttachStateChangeListener(object :
            RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(child: View) {
                child.setOnLongClickListener {
                    val position = listView.getChildViewHolder(child).bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        removeAt(position)
                        true
                    } else {
                        false
                    }
                }
            }

            override fun onChildViewDetachedFromWindow(child: View) {
                child.setOnLongClickListener(null)
            }
        })

        playAllButton.setOnClickListener {
            if (tracks.isNotEmpty()) PlayerHolder.play(tracks, 0)
        }

        shuffleAllButton.setOnClickListener {
            if (tracks.isNotEmpty()) {
                // Enable shuffle before the queue is replaced so it applies from the first track.
                PlayerHolder.whenReady { it.shuffleModeEnabled = true }
                PlayerHolder.play(tracks, 0)
            }
        }

        refresh()
    }

    // ---------------------------------------------------------------- public

    /**
     * Supply the playlist database. The host keeps ownership of the store (and must close it);
     * the panel only reads and writes through it. Call once, before [open].
     */
    fun setStore(store: PlaylistStore) {
        this.store = store
        refresh()
    }

    /**
     * Supply the current library so playlist track ids can be turned into [Track]s. Tracks that
     * are no longer on the device are skipped rather than shown as blanks.
     */
    fun setLibrary(all: List<Track>) {
        val map = LinkedHashMap<Long, Track>(all.size)
        for (t in all) map[t.id] = t
        library = map
        refresh()
    }

    /** Show the playlist [playlistId] called [name], resolving and listing its tracks. */
    fun open(playlistId: Long, name: String) {
        this.playlistId = playlistId
        this.playlistName = name
        refresh()
    }

    /**
     * Show the Favourites pseudo-playlist: the shared [Favourites] set resolved against
     * the library, kept live while the panel is on screen (a star toggled anywhere —
     * including from the full-screen player — refreshes this list).
     */
    fun openFavourites() {
        playlistId = FAVOURITES
        playlistName = FAVOURITES_NAME
        refresh()
    }

    /** Clear the panel back to its empty state (no playlist selected). */
    fun close() {
        playlistId = NO_PLAYLIST
        playlistName = ""
        refresh()
    }

    /** Re-read the playlist from the store and re-bind. Call after changing it elsewhere. */
    fun refresh() {
        if (playlistId == NO_PLAYLIST) {
            tracks = emptyList()
            nameView.text = NO_PLAYLIST_NAME
            countView.text = "0 tracks"
            adapter.submit(emptyList())
            updateButtons()
            return
        }

        nameView.text = playlistName.ifBlank { UNTITLED_PLAYLIST }

        val s = store
        tracks = when {
            // Favourites read through the shared facade (its in-memory set is always
            // ahead of the async database write a star tap just queued).
            playlistId == FAVOURITES -> Favourites.ids().mapNotNull { library[it] }
            s == null -> emptyList()
            else -> s.trackIdsIn(playlistId).mapNotNull { library[it] }
        }

        countView.text = if (tracks.size == 1) "1 track" else "${tracks.size} tracks"
        adapter.submit(tracks)
        updateButtons()
    }

    /** Tracks currently listed, in playlist order. */
    fun tracks(): List<Track> = tracks

    /** Id of the playlist on screen, or [NO_PLAYLIST] when closed. */
    fun currentPlaylistId(): Long = playlistId

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Favourites.addListener(favouritesListener)
    }

    override fun onDetachedFromWindow() {
        // Paired add/remove with the window: a destroyed Activity leaks no listener.
        Favourites.removeListener(favouritesListener)
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- internals

    /**
     * Favourites can change while this panel is on screen (a star tapped in the track
     * list or the full-screen player), so the pseudo-playlist keeps itself in step.
     */
    private val favouritesListener: (Long, Boolean) -> Unit = { _, _ ->
        if (playlistId == FAVOURITES) refresh()
    }

    private fun removeAt(position: Int) {
        val track = tracks.getOrNull(position) ?: return
        if (playlistId == FAVOURITES) {
            // Long-press inside Favourites un-stars (there is no "playlist" to remove
            // from). The Favourites listener refreshes the list after the change.
            ignoreClicksUntil = SystemClock.elapsedRealtime() + CLICK_SUPPRESS_MS
            Favourites.set(track.id, false)
            return
        }
        val s = store ?: return
        s.removeTrack(playlistId, track.id)
        // Swallow the click that ends the long-press gesture.
        ignoreClicksUntil = SystemClock.elapsedRealtime() + CLICK_SUPPRESS_MS
        refresh()
    }

    private fun updateButtons() {
        val enabled = tracks.isNotEmpty()
        playAllButton.isEnabled = enabled
        shuffleAllButton.isEnabled = enabled
        playAllButton.alpha = if (enabled) 1f else DISABLED_ALPHA
        shuffleAllButton.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    companion object {
        /** Sentinel id used while no playlist is selected. */
        const val NO_PLAYLIST = -1L

        /**
         * Synthetic id for the Favourites pseudo-playlist. Real playlist ids come from
         * AUTOINCREMENT and are always positive, so a negative id can never collide.
         */
        const val FAVOURITES = -2L

        private const val FAVOURITES_NAME = "Favourites"
        private const val CLICK_SUPPRESS_MS = 700L
        private const val DISABLED_ALPHA = 0.4f
        private const val UNTITLED_PLAYLIST = "Playlist"
        private const val NO_PLAYLIST_NAME = "No playlist selected"
    }
}