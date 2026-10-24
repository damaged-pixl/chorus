package com.bee.chorus.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bee.chorus.R
import com.bee.chorus.data.Album

/**
 * Artist browse: the library's artists, and one artist's albums underneath.
 *
 * Two levels in one panel, walked like the Folders breadcrumb: the crumb strip reads
 * "Artists" at the root, and the host's back step ([goUp]) returns there. Tapping an
 * artist swaps the list to their albums; tapping an album plays it, exactly as the
 * Albums tab does — both rows reuse [item_album] through the one [AlbumAdapter].
 *
 * The host injects the albums through [setLibrary]; artists are grouped from them by
 * [Album.artist] (the album-level artist where MediaStore has one, else the first
 * track's). Nothing is cached to disk and no listener is held — the panel is a pure
 * view over injected state, like the other browse panels.
 */
class ArtistPanel @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : LinearLayout(ctx, attrs) {

    private val crumbBar: LinearLayout
    private val emptyView: TextView
    private val listView: RecyclerView

    /** One adapter drives both levels: artist rows at the root, album rows underneath. */
    private val adapter = AlbumAdapter(
        onClick = { album -> onAlbumClick?.invoke(album) },
        onArtistClick = { row -> openArtist(row.name) }
    )

    /** The whole library's albums, injected once; artists are grouped from it. */
    private var allAlbums: List<Album> = emptyList()

    /** Every artist name, in the order the root list shows them. */
    private var artistNames: List<String> = emptyList()

    /** Null at the root; the artist whose albums are on screen once one is open. */
    private var currentArtist: String? = null

    /** Tapping an album plays it; the host wires this to the shared player. */
    var onAlbumClick: ((Album) -> Unit)? = null

    init {
        orientation = LinearLayout.VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_artists, this, true)

        crumbBar = findViewById(R.id.artist_crumbs)
        emptyView = findViewById(R.id.artist_empty)
        listView = findViewById(R.id.artist_list)

        listView.layoutManager = LinearLayoutManager(context)
        listView.adapter = adapter
        showRoot()
    }

    // ---------------------------------------------------------------- public

    /**
     * Supply the current library's albums. The panel groups them into artists on each
     * call and re-binds whatever is on screen, so a rescan is picked up on the next
     * visit without the panel holding a stale copy.
     */
    fun setLibrary(albums: List<Album>) {
        allAlbums = albums
        // Group case-insensitively so "the Beatles"/"The beatles" land together, but keep
        // the first spelling seen as the label. Albums stay in the order given (the
        // Albums tab's current sort) within each artist.
        val byArtist = LinkedHashMap<String, MutableList<Album>>()
        for (a in albums) {
            byArtist.getOrPut(a.artist.lowercase()) { mutableListOf() }.add(a)
        }
        // Sort artist names A–Z; the map's own order is the album sort, which would
        // otherwise leave the root list in an arbitrary sequence.
        artistNames = byArtist.keys.sorted()
        render()
    }

    /**
     * Step back one level — the host's back step's companion to the crumb. Returns false
     * at the root, so the host knows there was no level to give and takes its own next
     * step instead (the panel is then just a tab).
     */
    fun goUp(): Boolean {
        if (currentArtist == null) return false
        currentArtist = null
        render()
        listView.scrollToPosition(0)
        return true
    }

    // ---------------------------------------------------------------- internals

    /** Re-bind the list and crumbs for whichever level is current. */
    private fun render() {
        val artist = currentArtist
        if (artist == null) showRoot() else showArtist(artist)
    }

    private fun showRoot() {
        currentArtist = null
        // One row per artist, keyed on the lowercase name the grouping used; the first
        // album's art stands in for the artist.
        val rows = ArrayList<ArtistRow>(artistNames.size)
        for (key in artistNames) {
            val theirs = allAlbums.filter { it.artist.lowercase() == key }
            if (theirs.isNotEmpty()) {
                rows.add(ArtistRow(theirs.first().artist.ifBlank { key }, theirs.size, theirs.first()))
            }
        }
        adapter.submitArtists(rows)
        crumbs("Artists")
        updateEmpty(rows.isEmpty(), "No artists in the library")
    }

    private fun showArtist(name: String) {
        currentArtist = name
        adapter.submit(allAlbums.filter { it.artist.equals(name, ignoreCase = true) })
        crumbs("Artists", name)
        updateEmpty(false, "")
    }

    /** Open one artist's albums (root row tap). */
    private fun openArtist(name: String) {
        showArtist(name)
        listView.scrollToPosition(0)
    }

    /** Rebuild the crumb strip: a tappable "Artists" root, then the open artist. */
    private fun crumbs(vararg levels: String) {
        crumbBar.removeAllViews()
        for ((i, level) in levels.withIndex()) {
            val last = i == levels.lastIndex
            val tv = TextView(context).apply {
                text = level
                textSize = if (last) 14f else 13f
                setTextColor(
                    Theming.color(
                        context,
                        if (last) R.attr.chorusTextPrimary else R.attr.chorusTextSecondary
                    )
                )
                setPadding(0, 8, 0, 8)
                // The last level is where you already are; only earlier ones are tappable.
                if (!last) setOnClickListener { goUp() }
            }
            crumbBar.addView(tv)
            if (!last) {
                val sep = TextView(context).apply {
                    text = "  ›  "
                    textSize = 13f
                    setTextColor(Theming.color(context, R.attr.chorusTextSecondary))
                    setPadding(0, 8, 0, 8)
                }
                crumbBar.addView(sep)
            }
        }
    }

    private fun updateEmpty(show: Boolean, message: String) {
        if (show) {
            emptyView.text = message
            emptyView.visibility = View.VISIBLE
        } else {
            emptyView.visibility = View.GONE
        }
    }
}
