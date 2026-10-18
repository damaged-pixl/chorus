package com.bee.chorus

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bee.chorus.data.Album
import com.bee.chorus.data.Favourites
import com.bee.chorus.data.LibraryReader
import com.bee.chorus.data.PlaylistStore
import com.bee.chorus.data.Settings
import com.bee.chorus.data.Track
import com.bee.chorus.playback.PlayerHolder
import com.bee.chorus.ui.AlbumAdapter
import com.bee.chorus.ui.CycleMode
import com.bee.chorus.ui.CycleToggle
import com.bee.chorus.ui.MiniPlayer
import com.bee.chorus.ui.PlaylistAdapter
import com.bee.chorus.ui.PlaylistPanel
import com.bee.chorus.ui.QueuePanel
import com.bee.chorus.ui.SearchPanel
import com.bee.chorus.ui.TrackAdapter
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var store: PlaylistStore
    private lateinit var trackAdapter: TrackAdapter
    private lateinit var albumAdapter: AlbumAdapter
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var playlistPanel: PlaylistPanel
    private lateinit var searchPanel: SearchPanel
    private lateinit var queuePanel: QueuePanel
    private lateinit var miniPlayer: MiniPlayer
    private lateinit var cycleToggle: CycleToggle

    private var library: List<Track> = emptyList()
    private var albums: List<Album> = emptyList()

    private val io = Executors.newSingleThreadExecutor()
    private var cycle = false

    // Track-list sort. TITLE is the MediaStore scan order (title, case-insensitive);
    // the other two order by the play_stats table, unplayed tracks last.
    private var sortMode = SORT_TITLE
    private lateinit var sortButtons: List<TextView>
    private var sortIdleColor = 0
    private val sortActiveColor = 0xFFFFC107.toInt()

    // One RecycledViewPool shared by the three main lists, so row views survive tab
    // switches instead of being torn down per list. Safe to share because each adapter
    // reports its own view type (see getItemViewType in Track/Album/PlaylistAdapter) —
    // the pool is keyed by view type alone and would otherwise hand a row of one
    // layout to a list that inflates another.
    private val sharedViewPool = RecyclerView.RecycledViewPool()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Cycle mode changes the theme, so it must be applied before any inflation.
        cycle = Settings.isCycleMode(this)
        CycleMode.applyTheme(this, cycle)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // targetSdk 36 forces edge-to-edge on Android 15+, so inset the whole UI
        // ourselves or the tab strip renders underneath the status bar.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_root)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        store = PlaylistStore(this)
        Favourites.init(this)
        PlayerHolder.bind(this)

        wireLists()
        wireSortBar()
        wireTabs()
        wireCycle()

        findViewById<View>(android.R.id.content).post {
            CycleMode.apply(findViewById(android.R.id.content), cycle)
        }

        if (hasAudioPermission()) loadLibrary() else requestAudio()
    }

    // ---------- permissions ----------

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, audioPermission()) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestAudio() {
        AlertDialog.Builder(this)
            .setTitle("Permission needed")
            .setMessage("Chorus reads audio files already on this phone. It never uploads them anywhere.")
            .setPositiveButton("Allow") { _, _ ->
                requestPermissions(arrayOf(audioPermission()), 1)
            }
            .setNegativeButton("Not now") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) loadLibrary()
            else finish()
        }
    }

    // ---------- data ----------

    private fun loadLibrary() {
        io.execute {
            val reader = LibraryReader(this)
            val tracks = reader.loadTracks()
            val albums = reader.loadAlbums(tracks)
            runOnUiThread {
                library = tracks
                this.albums = albums
                applySort()
                albumAdapter.submit(albums)
                searchPanel.setTracks(tracks)
                playlistPanel.setLibrary(tracks)
                playlistPanel.setStore(store)
                refreshPlaylists()
            }
        }
    }

    private fun refreshPlaylists() {
        val list = io.submit<List<Pair<Long, String>>> { store.listPlaylists() }
        io.execute {
            val res = list.get()
            runOnUiThread { playlistAdapter.submit(res) }
        }
    }

    // ---------- track-list sorting ----------

    private fun wireSortBar() {
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
        sortIdleColor = ta.getColor(0, 0x99FFFFFF.toInt())
        ta.recycle()

        sortButtons = listOf(
            findViewById(R.id.sort_title),
            findViewById(R.id.sort_plays),
            findViewById(R.id.sort_recent)
        )
        sortButtons[SORT_TITLE].setOnClickListener { setSort(SORT_TITLE) }
        sortButtons[SORT_PLAYS].setOnClickListener { setSort(SORT_PLAYS) }
        sortButtons[SORT_RECENT].setOnClickListener { setSort(SORT_RECENT) }
        updateSortBar()
    }

    private fun setSort(mode: Int) {
        if (mode == sortMode) return
        sortMode = mode
        applySort()
    }

    /** Highlight the active sort; the other two stay at the muted secondary tint. */
    private fun updateSortBar() {
        for (i in sortButtons.indices) {
            sortButtons[i].setTextColor(if (i == sortMode) sortActiveColor else sortIdleColor)
        }
    }

    /**
     * Submit the track list in the current sort order and refresh the highlight.
     * The stats-backed sorts re-read play_stats, so this is also the refresh path
     * for plays counted in the background (see onResume).
     */
    private fun applySort() {
        updateSortBar()
        when (sortMode) {
            // MediaStore scans in title order already; no work needed.
            SORT_TITLE -> trackAdapter.submit(library)
            else -> io.execute {
                val stats = store.playStats()
                // Unplayed tracks (0 / never) sort last; ties break A–Z for a stable list.
                val sorted = when (sortMode) {
                    SORT_PLAYS -> library.sortedWith(
                        compareByDescending<Track> { stats[it.id]?.plays ?: 0 }
                            .thenBy { it.title.lowercase() }
                    )
                    else -> library.sortedWith(
                        compareByDescending<Track> { stats[it.id]?.lastPlayed ?: 0L }
                            .thenBy { it.title.lowercase() }
                    )
                }
                runOnUiThread { trackAdapter.submit(sorted) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Plays counted while another screen (or the notification) was in front can
        // change the stats-backed orders; re-sort so returning to the list is current.
        if (sortMode != SORT_TITLE) applySort()
    }

    // ---------- wiring ----------

    private fun wireLists() {
        // Named onClick: keeps the trailing-lambda-from-hell from ever rebinding to
        // onLongClick (both are trailing-parameter candidates). onLongClick is set below.
        trackAdapter = TrackAdapter(onClick = { index, list -> PlayerHolder.play(list, index) })
        // Named onClick here too: AlbumAdapter gained an onLongClick parameter, so a bare
        // trailing lambda would bind to onLongClick instead of onClick.
        albumAdapter = AlbumAdapter(onClick = { album ->
            val tracks = library.filter { it.albumId == album.id }
                .sortedBy { it.trackNo }
            PlayerHolder.play(tracks, 0)
        })
        // Named onClick: PlaylistAdapter gained onLongClick, so a positional lambda
        // would bind to onLongClick instead of onClick.
        playlistAdapter = PlaylistAdapter(onClick = { id, name -> openPlaylist(id, name) })

        findViewById<RecyclerView>(R.id.track_list).apply {
            setRecycledViewPool(sharedViewPool)
            // Every row is the same height (art block + fixed padding dominate), and the
            // list fills its parent, so adapter changes never resize the view.
            setHasFixedSize(true)
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = trackAdapter
            trackAdapter.onLongClick = { track -> addToPlaylistDialog(listOf(track)) }
        }
        findViewById<RecyclerView>(R.id.album_list).apply {
            setRecycledViewPool(sharedViewPool)
            setHasFixedSize(true)
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = albumAdapter
            albumAdapter.onLongClick = { album ->
                // Same ordering the click-to-play uses, so the playlist matches play order.
                addToPlaylistDialog(
                    library.filter { it.albumId == album.id }.sortedBy { it.trackNo }
                )
            }
        }
        findViewById<RecyclerView>(R.id.playlist_list).apply {
            setRecycledViewPool(sharedViewPool)
            setHasFixedSize(true)
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = playlistAdapter
            playlistAdapter.onLongClick = { id, name -> playlistOptionsDialog(id, name) }
        }

        playlistPanel = findViewById(R.id.playlist_panel)
        searchPanel = findViewById(R.id.search_panel)
        queuePanel = findViewById(R.id.queue_panel)
        miniPlayer = findViewById(R.id.mini_player)

        // Tapping the mini player opens the full-screen player.
        miniPlayer.setOnClickListener { PlayerActivity.start(this) }

        // Favourites: pseudo-playlist detail, same panel as a real playlist.
        findViewById<Button>(R.id.fav_playlist).setOnClickListener { openFavourites() }

        // Playlists: create.
        findViewById<Button>(R.id.new_playlist).setOnClickListener {
            val input = EditText(this).apply { hint = "Playlist name" }
            AlertDialog.Builder(this)
                .setTitle("New playlist")
                .setView(input)
                .setPositiveButton("Create") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty()) {
                        io.execute {
                            store.createPlaylist(name)
                            runOnUiThread { refreshPlaylists() }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun openPlaylist(id: Long, name: String) {
        playlistPanel.open(id, name)
        show(R.id.playlist_panel)
    }

    private fun openFavourites() {
        playlistPanel.openFavourites()
        show(R.id.playlist_panel)
    }

    // ---------- playlist editing ----------

    /**
     * Add-to-playlist chooser for a long-press on a track or album row. Each row carries the
     * tracks it represents, so a long-pressed album appends its whole (play-ordered) track set
     * in one go. A "+ New playlist" row at the top creates a playlist and adds to it immediately.
     */
    private fun addToPlaylistDialog(tracks: List<Track>) {
        if (tracks.isEmpty()) {
            Toast.makeText(this, "No tracks to add", Toast.LENGTH_SHORT).show()
            return
        }
        io.execute {
            val existing = store.listPlaylists()
            runOnUiThread {
                // "0" is the synthetic new-playlist row; 1..n are the real playlists.
                val labels = ArrayList<String>(existing.size + 1)
                labels.add("+ New playlist")
                for ((_, name) in existing) labels.add(name.ifBlank { "Untitled playlist" })

                AlertDialog.Builder(this)
                    .setTitle(
                        if (tracks.size == 1) "Add \"${tracks[0].title}\" to"
                        else "Add ${tracks.size} tracks to"
                    )
                    .setItems(labels.toTypedArray()) { _, which ->
                        if (which == 0) createPlaylistThenAdd(tracks)
                        else {
                            val (id, _) = existing[which - 1]
                            addTracksTo(id, tracks)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    /** Name a new playlist, then add the waiting tracks to it. */
    private fun createPlaylistThenAdd(tracks: List<Track>) {
        val input = EditText(this).apply {
            hint = "Playlist name"
            // Give the keyboard a stable field; the dialog below has no view of its own.
            isFocusable = true
            isFocusableInTouchMode = true
        }
        AlertDialog.Builder(this)
            .setTitle("New playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    io.execute {
                        val id = store.createPlaylist(name)
                        store.addTracks(id, tracks.map { it.id })
                        runOnUiThread { refreshPlaylists() }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addTracksTo(playlistId: Long, tracks: List<Track>) {
        io.execute {
            store.addTracks(playlistId, tracks.map { it.id })
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (tracks.size == 1) "Added to playlist"
                    else "Added ${tracks.size} tracks",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** Long-press options for a saved playlist: rename or delete. */
    private fun playlistOptionsDialog(id: Long, name: String) {
        val label = name.ifBlank { "Untitled playlist" }
        val options = arrayOf("Rename", "Delete")
        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> renamePlaylistDialog(id, label)
                    1 -> confirmDeletePlaylist(id, label)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renamePlaylistDialog(id: Long, current: String) {
        val input = EditText(this).apply { setText(current) }
        AlertDialog.Builder(this)
            .setTitle("Rename playlist")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    io.execute {
                        store.renamePlaylist(id, name)
                        runOnUiThread {
                            refreshPlaylists()
                            // Keep an open detail header in step with the new name.
                            if (playlistPanel.currentPlaylistId() == id) {
                                playlistPanel.open(id, name)
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeletePlaylist(id: Long, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete playlist")
            .setMessage("\"$name\" will be removed. The music files stay on this phone.")
            .setPositiveButton("Delete") { _, _ ->
                io.execute {
                    store.deletePlaylist(id)
                    runOnUiThread {
                        // If the deleted playlist is on screen, fall back to the list.
                        if (playlistPanel.currentPlaylistId() == id) {
                            playlistPanel.close()
                            show(R.id.playlist_home)
                        }
                        refreshPlaylists()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun wireTabs() {
        val map = mapOf(
            // track_screen (sort bar + list) is shown as one unit, so the screen id —
            // not the bare RecyclerView — is what the tab strip switches between.
            R.id.tab_tracks to R.id.track_screen,
            R.id.tab_albums to R.id.album_list,
            R.id.tab_playlists to R.id.playlist_home,
            R.id.tab_search to R.id.search_panel,
            R.id.tab_queue to R.id.queue_panel,
            R.id.tab_settings to R.id.settings_panel
        )
        for ((tab, target) in map) {
            findViewById<Button>(tab).setOnClickListener { show(target) }
        }
    }

    private fun wireCycle() {
        cycleToggle = findViewById(R.id.cycle_toggle)
        cycleToggle.onChanged = { recreate() }
    }

    /** Show one content view, hide the rest of the top-level panels. */
    private fun show(target: Int) {
        val panels = listOf(
            R.id.track_screen, R.id.album_list, R.id.playlist_home, R.id.playlist_panel,
            R.id.search_panel, R.id.queue_panel, R.id.settings_panel
        )
        for (id in panels) {
            val v: View = findViewById(id)
            v.visibility = if (id == target) View.VISIBLE else View.GONE
        }
        if (target == R.id.playlist_home) {
            io.execute {
                val res = store.listPlaylists()
                runOnUiThread { playlistAdapter.submit(res) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private companion object {
        /** Sort modes for the track list; index into [sortButtons]. */
        const val SORT_TITLE = 0
        const val SORT_PLAYS = 1
        const val SORT_RECENT = 2
    }
}