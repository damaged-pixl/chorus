package com.bee.chorus

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bee.chorus.data.Album
import com.bee.chorus.data.Favourites
import com.bee.chorus.data.Library
import com.bee.chorus.data.LibraryReader
import com.bee.chorus.data.M3u
import com.bee.chorus.data.PlaylistStore
import com.bee.chorus.data.Settings
import com.bee.chorus.data.Track
import com.bee.chorus.playback.EqualiserCaps
import com.bee.chorus.playback.PlayerHolder
import android.graphics.Bitmap
import com.bee.chorus.ui.AlbumAdapter
import com.bee.chorus.ui.Art
import com.bee.chorus.ui.ArtistPanel
import com.bee.chorus.ui.AzScroller
import com.bee.chorus.ui.CycleMode
import com.bee.chorus.ui.CycleToggle
import com.bee.chorus.ui.Dialogs
import com.bee.chorus.ui.FolderPanel
import com.bee.chorus.ui.MiniPlayer
import com.bee.chorus.ui.PlayerPanel
import com.bee.chorus.ui.PlaylistAdapter
import com.bee.chorus.ui.PlaylistPanel
import com.bee.chorus.ui.QueuePanel
import com.bee.chorus.ui.SearchPanel
import com.bee.chorus.ui.Theming
import com.bee.chorus.ui.TrackAdapter
import com.bee.chorus.ui.Ui
import com.bee.chorus.ui.chorusShow
import java.nio.file.Paths
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var store: PlaylistStore
    private lateinit var trackAdapter: TrackAdapter
    private lateinit var albumAdapter: AlbumAdapter
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var playlistPanel: PlaylistPanel
    private lateinit var searchPanel: SearchPanel
    private lateinit var queuePanel: QueuePanel
    private lateinit var folderPanel: FolderPanel
    private lateinit var artistPanel: ArtistPanel
    private lateinit var miniPlayer: MiniPlayer
    private lateinit var cycleToggle: CycleToggle

    // SAF pickers for M3U export/import. Registered as field initialisers (the Activity
    // Result API requires registration before the activity starts) — every file this
    // feature ever touches is behind a URI the user picks; nothing else is readable or
    // writable. Export writes happen in writeExported(); import reads in importPlaylist().
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(M3U_MIME)) { uri ->
            if (uri != null) writeExported(uri)
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importPlaylist(uri)
        }

    /** (playlist id, name) waiting while the export location picker is open. */
    private var pendingExport: Pair<Long, String>? = null

    private var library: List<Track> = emptyList()
    private var albums: List<Album> = emptyList()

    private val io = Executors.newSingleThreadExecutor()

    /**
     * Run [block] on the library thread, and ignore the call once this screen is gone.
     *
     * A `runOnUiThread` posted from a background task can land *after* onDestroy has shut
     * the executor down: the screen is gone, the work is not wanted any more, and letting
     * the pool reject it turns a finished screen into a crash. That is a real sequence —
     * the library load posts its last UI update just as an install or a permission churn
     * tears the Activity down. One guard here means every submission is safe, instead of
     * a dozen call sites each remembering to check.
     */
    private fun runIo(block: () -> Unit) {
        if (io.isShutdown) return
        io.execute { block() }
    }
    private var cycle = false

    // Tab strip: the six buttons plus the accent underline that sits under the selected one.
    // Built once in wireTabs(); highlighting only flips their state.
    private lateinit var tabButtons: Map<Int, Button>
    private lateinit var tabIndicators: Map<Int, View>
    private var activeTab = View.NO_ID

    // The shared controller's current item, pushed into the track rows so the list can mark
    // what is playing. Added in wireLists() and removed in onDestroy() — PlayerHolder holds
    // listeners strongly and statically, so an unpaired one leaks for the life of the process.
    private val playerListener: (Player) -> Unit = { player -> publishNowPlaying(player) }

    // Track-list sort. TITLE is the MediaStore scan order (title, case-insensitive);
    // the other two order by the play_stats table, unplayed tracks last.
    private var sortMode = SORT_TITLE
    private lateinit var sortButtons: List<TextView>

    // Album sort. ARTIST is the default so the Albums tab keeps the order it has always
    // shipped with; the others are the same one bar, the same way the Tracks tab works.
    private var albumSort = ALBUM_SORT_ARTIST
    private lateinit var albumSortButtons: List<TextView>

    /**
     * The device's equaliser capabilities, read once per screen. Null means "this phone
     * will not let an app open an equaliser", which the row and its tap both answer for.
     */
    private var eqCaps: EqualiserCaps? = null

    /**
     * Playlist cover bitmaps, keyed by playlist id, each beside the signature (its first
     * few track ids) it was built from — so a revisit only rebuilds what changed. Holds
     * row-sized bitmaps only, and drops a playlist's entry when the playlist goes.
     */
    private val coverCache = HashMap<Long, Pair<String, Bitmap>>()

    // A–Z jump rail over the track list, and the panel the screen is currently on (the
    // rail belongs to one panel only, so it needs to know which one is up).
    private lateinit var azScroller: AzScroller
    private var activePanel = R.id.track_screen

    /**
     * Back steps *out* of wherever the user is — up a folder, back to the home tab — and
     * only leaves the app from the track screen, where there is nothing left to step out
     * of. Enabled per [show]; see [stepBack] for the actual ladder.
     */
    private lateinit var backStep: OnBackPressedCallback

    // Sort-bar tints, resolved in wireSortBar() rather than as field initialisers: the
    // theme is not final until onCreate has had its say (CycleMode.applyTheme runs first),
    // and a field initialiser would read the manifest theme instead.
    private var sortIdleColor = 0
    private var sortActiveColor = 0

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

        // A recreation (rotation, uiMode change) while the export picker is open still
        // receives its result — restore the waiting playlist so the write isn't dropped.
        if (savedInstanceState?.containsKey(STATE_EXPORT_ID) == true) {
            pendingExport = savedInstanceState.getLong(STATE_EXPORT_ID) to
                savedInstanceState.getString(STATE_EXPORT_NAME).orEmpty()
        }

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
        wireAlbumSortBar()
        wireTabs()
        wireCycle()
        wireSettings()
        wireBack()

        findViewById<View>(android.R.id.content).post {
            CycleMode.apply(findViewById(android.R.id.content), cycle)
        }

        if (hasAudioPermission()) loadLibrary() else requestAudio()

        // Opened from the full-screen player's queue pill: land on the queue tab. Here and
        // in onNewIntent, because the player may fire this at an already-alive screen.
        if (intent?.getBooleanExtra(EXTRA_OPEN_QUEUE, false) == true) {
            show(R.id.queue_panel)
        }

        // A track's info sheet asks to be shown where its file lives. Same two-place rule
        // as the queue extra for the same reason: the player may fire this at a screen
        // that is already up.
        intent?.getStringExtra(EXTRA_OPEN_FOLDER)?.takeIf { it.isNotEmpty() }?.let { path ->
            show(R.id.folder_panel)
            folderPanel.openPath(path)
        }
    }

    /**
     * The player's queue pill hands back here. The flag pair in PlayerPanel
     * (CLEAR_TOP + SINGLE_TOP) routes the intent to the *existing* screen rather than
     * stacking a second one, so this is the path that runs when the main screen is
     * already alive behind the player.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_QUEUE, false)) {
            show(R.id.queue_panel)
        }
        intent.getStringExtra(EXTRA_OPEN_FOLDER)?.takeIf { it.isNotEmpty() }?.let { path ->
            show(R.id.folder_panel)
            folderPanel.openPath(path)
        }
    }

    // ---------- permissions ----------

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, audioPermission()) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestAudio() {
        Dialogs.builder(this)
            .setTitle("Permission needed")
            .setMessage("Chorus reads audio files already on this phone. It never uploads them anywhere.")
            .setPositiveButton("Allow") { _, _ ->
                requestPermissions(arrayOf(audioPermission()), 1)
            }
            .setNegativeButton("Not now") { _, _ -> finish() }
            .setCancelable(false)
            .chorusShow()
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
        runIo {
            val reader = LibraryReader(this)
            val tracks = reader.loadTracks()
            val albums = reader.loadAlbums(tracks)
            runOnUiThread {
                library = tracks
                this.albums = albums
                applySort()
                applyAlbumSort()
                searchPanel.setTracks(tracks)
                folderPanel.setLibrary(tracks)
                playlistPanel.setLibrary(tracks)
                playlistPanel.setStore(store)
                refreshPlaylists()
            }
        }
    }

    private fun refreshPlaylists() {
        // Same window as runIo's guard: this method is reached from a posted update, so
        // it has to answer for itself where the executor is concerned.
        if (io.isShutdown) return
        val list = io.submit<List<Pair<Long, String>>> { store.listPlaylists() }
        runIo {
            val res = list.get()
            val covers = buildPlaylistCovers(res)
            runOnUiThread {
                playlistAdapter.submit(res)
                playlistAdapter.setCovers(covers)
                // One-line empty state centred over the list. Playlists live in our
                // own DB (not MediaStore), so the state is accurate immediately.
                findViewById<View>(R.id.playlist_empty).visibility =
                    if (res.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    // ---------- track-list sorting ----------

    private fun wireSortBar() {
        sortIdleColor = Theming.color(this, R.attr.chorusTextSecondary)
        sortActiveColor = Theming.color(this, R.attr.chorusAccentText)

        sortButtons = listOf(
            findViewById(R.id.sort_title),
            findViewById(R.id.sort_plays),
            findViewById(R.id.sort_recent),
            findViewById(R.id.sort_added)
        )
        sortButtons[SORT_TITLE].setOnClickListener { setSort(SORT_TITLE) }
        sortButtons[SORT_PLAYS].setOnClickListener { setSort(SORT_PLAYS) }
        sortButtons[SORT_RECENT].setOnClickListener { setSort(SORT_RECENT) }
        sortButtons[SORT_ADDED].setOnClickListener { setSort(SORT_ADDED) }
        updateSortBar()
    }

    // ---------- album sorting ----------

    /** The four buttons of the Albums tab's sort bar, in [albumSort] order. */
    private fun wireAlbumSortBar() {
        albumSortButtons = listOf(
            findViewById(R.id.album_sort_title),
            findViewById(R.id.album_sort_artist),
            findViewById(R.id.album_sort_year),
            findViewById(R.id.album_sort_added)
        )
        albumSortButtons[ALBUM_SORT_TITLE].setOnClickListener { setAlbumSort(ALBUM_SORT_TITLE) }
        albumSortButtons[ALBUM_SORT_ARTIST].setOnClickListener { setAlbumSort(ALBUM_SORT_ARTIST) }
        albumSortButtons[ALBUM_SORT_YEAR].setOnClickListener { setAlbumSort(ALBUM_SORT_YEAR) }
        albumSortButtons[ALBUM_SORT_ADDED].setOnClickListener { setAlbumSort(ALBUM_SORT_ADDED) }
        // The Artists door: hands the panel the albums in the *current* sort order (so an
        // artist's albums come up in whatever order the tab is showing) and swaps the
        // panel *over* the album list — the album screen goes down so only one list is on
        // screen (the same rule as every other panel pair). activePanel stays
        // album_screen, so the Albums tab stays lit and back steps out of the overlay
        // first (see stepBack); show() tears the overlay down on any top-level switch.
        findViewById<TextView>(R.id.album_artists).setOnClickListener {
            artistPanel.setLibrary(albums)
            findViewById<View>(R.id.album_screen).visibility = View.GONE
            artistPanel.visibility = View.VISIBLE
        }
        updateAlbumSortBar()
    }

    private fun setAlbumSort(mode: Int) {
        if (mode == albumSort) return
        albumSort = mode
        applyAlbumSort()
    }

    /** Highlight the active album sort; the rest stay at the muted secondary ink. */
    private fun updateAlbumSortBar() {
        for (i in albumSortButtons.indices) {
            albumSortButtons[i].setTextColor(
                if (i == albumSort) sortActiveColor else sortIdleColor
            )
        }
    }

    /**
     * Submit the album list in the current order. Everything sorts in memory — the
     * library is already loaded — so switching is one comparison pass, no query.
     *
     * Ties break A–Z, and the tie-break runs once per comparison with
     * CASE_INSENSITIVE_ORDER rather than `lowercase()` (the track list learned that
     * lesson the hard way: two new Strings per comparison).
     */
    private fun applyAlbumSort() {
        updateAlbumSortBar()
        val sorted = when (albumSort) {
            // An explicit comparator: the (comparator, selector) overload of compareBy
            // has two type parameters, so the type has nowhere to come from inside
            // sortedWith — and CASE_INSENSITIVE_ORDER is what keeps "a" next to "A".
            ALBUM_SORT_TITLE -> albums.sortedWith(
                Comparator { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title) }
            )
            ALBUM_SORT_YEAR -> albums.sortedWith(
                compareByDescending<Album> { it.year.coerceAtLeast(0) }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
            ALBUM_SORT_ADDED -> albums.sortedWith(
                compareByDescending<Album> { it.dateAdded }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
            // ARTIST: the album artist where MediaStore has one, first track's otherwise.
            else -> albums.sortedWith(
                Comparator<Album> { a, b ->
                    String.CASE_INSENSITIVE_ORDER.compare(a.artist, b.artist)
                }.thenBy { it.year.coerceAtLeast(0) }
            )
        }
        albumAdapter.submit(sorted)
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
            SORT_TITLE -> {
                trackAdapter.submit(library)
                updateAzRail()
            }
            // Date added is already on the row, so unlike the two play-stat sorts this
            // one needs no stats query — but it still runs off the main thread, because
            // sorting a few thousand rows is not free either.
            SORT_ADDED -> runIo {
                val sorted = library.sortedWith(
                    compareByDescending<Track> { it.dateAdded }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                )
                runOnUiThread {
                    trackAdapter.submit(sorted)
                    updateAzRail()
                }
            }
            else -> runIo {
                val stats = store.playStats()
                // Unplayed tracks (0 / never) sort last; ties break A–Z for a stable list.
                // The tie-break uses CASE_INSENSITIVE_ORDER rather than `lowercase()`:
                // the selector runs per comparison, and allocating two Strings each time
                // churned ~120k short-lived strings per sort of a 5k library.
                val sorted = when (sortMode) {
                    SORT_PLAYS -> library.sortedWith(
                        compareByDescending<Track> { stats[it.id]?.plays ?: 0 }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                    )
                    else -> library.sortedWith(
                        compareByDescending<Track> { stats[it.id]?.lastPlayed ?: 0L }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                    )
                }
                runOnUiThread {
                    trackAdapter.submit(sorted)
                    // A rail only means anything in title order: in "Most played" the
                    // first row is not "A", and jumping to it would be nonsense.
                    updateAzRail()
                }
            }
        }
    }

    /**
     * Rebuild the A–Z rail — one entry per letter the list actually starts with, in order
     * — and decide whether the rail should be on screen at all: only on the Tracks tab,
     * and only in title order. Everything else hides it, so it can never swallow a tap
     * meant for another screen.
     *
     * The index reads [library] directly, which is exactly what the title sort submits
     * (MediaStore returns rows ordered by title), so the rail and the rows cannot drift.
     */
    private fun updateAzRail() {
        val trackList = findViewById<RecyclerView>(R.id.track_list)
        if (activePanel != R.id.track_screen || sortMode != SORT_TITLE) {
            azScroller.setIndex(emptyList())
            // Give the rows their edge back: with the rail hidden, 34dp of dead space on
            // the right would just be a gap.
            if (trackList.paddingEnd != 0) {
                trackList.setPaddingRelative(0, trackList.paddingTop, 0, trackList.paddingBottom)
            }
            return
        }
        // The rail sits over the right edge of the rows, and the star lives there — without
        // this, the rail would eat taps meant for the star. Padding is what puts the rows
        // clear of it (clipToPadding is already off on this list, which only affects
        // scrolling, not where the rows are laid out).
        val railWidth = azScroller.resources.getDimensionPixelSize(R.dimen.az_rail_width)
        if (trackList.paddingEnd != railWidth) {
            trackList.setPaddingRelative(
                trackList.paddingStart, trackList.paddingTop, railWidth, trackList.paddingBottom
            )
        }
        val entries = ArrayList<AzScroller.Entry>(26)
        var last = Char.MIN_VALUE
        library.forEachIndexed { position, track ->
            val first = track.title.firstOrNull()?.uppercaseChar()
            val letter = if (first != null && first in 'A'..'Z') first else '#'
            // The list is title-ordered, so a letter's rows are contiguous and the first
            // one is the only one worth an entry.
            if (letter != last) {
                entries.add(AzScroller.Entry(letter, position))
                last = letter
            }
        }
        azScroller.setIndex(entries)
    }

    override fun onResume() {
        super.onResume()
        // A share import may have landed while another app was in front: re-read once
        // so the file is in the lists immediately, then clear the flag (read-and-clear
        // here so a share during *this* resume still gets picked up by the next one).
        if (Library.dirty) {
            Library.dirty = false
            loadLibrary()
            return
        }
        // Plays counted while another screen (or the notification) was in front can
        // change the stats-backed orders; re-sort so returning to the list is current.
        if (sortMode != SORT_TITLE) applySort()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Keep a pending export pick alive across recreation (see onCreate).
        pendingExport?.let {
            outState.putLong(STATE_EXPORT_ID, it.first)
            outState.putString(STATE_EXPORT_NAME, it.second)
        }
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
            albumAdapter.onLongClick = { album -> albumOptionsDialog(album) }
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
        folderPanel = findViewById(R.id.folder_panel)
        artistPanel = findViewById(R.id.artist_panel)
        miniPlayer = findViewById(R.id.mini_player)

        // The artist panel plays an album exactly as the Albums tab does: the album's own
        // tracks, in track order, from the top. Same shared player, no second path.
        artistPanel.onAlbumClick = { album ->
            val albumTracks = library.filter { it.albumId == album.id }.sortedBy { it.trackNo }
            if (albumTracks.isNotEmpty()) PlayerHolder.play(albumTracks, 0)
        }

        // Favourites exports through the panel's own button; a saved playlist keeps its
        // long-press Export in the Lists tab. The SAF picker lives here, in the Activity.
        playlistPanel.onExportFavourites = {
            startExport(PlaylistPanel.FAVOURITES, "Favourites")
        }
        // The queue becomes a playlist through the panel's Save button: name it here,
        // write it in the store the Lists tab reads, and leave the queue untouched.
        queuePanel.onSaveQueue = { ids -> saveQueueAsPlaylist(ids) }

        // The track list's stars follow the Favourites singleton. Paired with onDestroy
        // below, because this adapter is owned by the Activity, not by a view that can
        // hook its own window attach/detach. Search and playlist panels do the same in
        // their own onAttachedToWindow/onDetachedFromWindow.
        trackAdapter.observeFavourites()

        // A–Z rail: jumping is just a scroll; the rail only reports a row index, because
        // this Activity is the only place that holds the RecyclerView.
        azScroller = findViewById(R.id.az_scroller)
        azScroller.onJump = { position ->
            (findViewById<RecyclerView>(R.id.track_list).layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(position, 0)
        }

        // Now-playing cue for the track rows, from the single shared controller. Same
        // Activity-lifetime pairing as the Favourites observer above (see onDestroy).
        PlayerHolder.addPlayerListener(playerListener)

        // Tapping the mini player opens the full-screen player.
        miniPlayer.setOnClickListener { PlayerActivity.start(this) }

        // Favourites: pseudo-playlist detail, same panel as a real playlist.
        findViewById<Button>(R.id.fav_playlist).setOnClickListener { openFavourites() }

        // Playlists: create.
        findViewById<Button>(R.id.new_playlist).setOnClickListener {
            val input = EditText(this).apply { hint = "Playlist name" }
            Dialogs.builder(this)
                .setTitle("New playlist")
                .setView(input)
                .setPositiveButton("Create") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty()) {
                        runIo {
                            store.createPlaylist(name)
                            runOnUiThread { refreshPlaylists() }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .chorusShow()
        }

        // Playlists: import an .m3u/.m3u8 the user picks through SAF.
        findViewById<Button>(R.id.import_playlist).setOnClickListener { startImport() }

        // Folder browser: long-press on a track row gets the same add-to-playlist
        // chooser as every other list (the tap still plays).
        folderPanel.onTrackLongClick = { track -> addToPlaylistDialog(listOf(track)) }
    }

    /**
     * A cover mosaic per playlist: the first few tracks' album art, 2x2, exactly as every
     * other player draws a playlist. Built here on the library-load thread, never on a
     * bind path — the adapter is handed the finished bitmaps with [PlaylistAdapter.setCovers].
     *
     * The signature (which tracks the cover came from) is cached per playlist, so
     * re-visiting the Lists tab only rebuilds the ones that actually changed. Covers are
     * sized to the row they live in, which keeps the whole set to a few hundred kilobytes
     * rather than a few megabytes.
     */
    private fun buildPlaylistCovers(
        playlists: List<Pair<Long, String>>
    ): Map<Long, Bitmap> {
        val byId = library.associateBy { it.id }
        val out = HashMap<Long, Bitmap>(playlists.size)
        val keep = HashSet<Long>(playlists.size)
        val size = (PLAYLIST_COVER_DP * resources.displayMetrics.density).toInt().coerceAtLeast(64)
        for ((id, _) in playlists) {
            keep.add(id)
            // Head of the playlist: what the cover always shows, and a signature in one.
            val ids = store.trackIdsIn(id).take(4)
            if (ids.isEmpty()) continue
            val signature = ids.joinToString(",")
            val cached = coverCache[id]
            if (cached != null && cached.first == signature) {
                out[id] = cached.second
                continue
            }
            val uris = ids.mapNotNull { byId[it]?.albumArtUri }
            if (uris.isEmpty()) continue
            val mosaic = Art.mosaicBlocking(this, uris, size / 2, size) ?: continue
            coverCache[id] = signature to mosaic
            out[id] = mosaic
        }
        // A deleted playlist's cover must not outlive it — the cache is the only thing
        // here that would otherwise hold on to the bitmap for the life of the process.
        coverCache.keys.retainAll(keep)
        return out
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
    /**
     * Long-press on an album: add its tracks to a playlist, or go and look at where they
     * physically live. The folder join is here rather than as a control on the Albums tab
     * because it is a *look*, not a mode — one album, one trip, then back.
     */
    private fun albumOptionsDialog(album: Album) {
        Dialogs.builder(this)
            .setTitle(album.title)
            .setItems(arrayOf("Add to playlist", "Show in folder")) { _, which ->
                // Same ordering the click-to-play uses, so the playlist matches play order.
                val tracks = library.filter { it.albumId == album.id }.sortedBy { it.trackNo }
                when (which) {
                    0 -> addToPlaylistDialog(tracks)
                    1 -> {
                        val path = tracks.firstOrNull()?.parentPath
                        if (path != null) {
                            show(R.id.folder_panel)
                            folderPanel.openPath(path)
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .chorusShow()
    }

    private fun addToPlaylistDialog(tracks: List<Track>) {
        if (tracks.isEmpty()) {
            Toast.makeText(this, "No tracks to add", Toast.LENGTH_SHORT).show()
            return
        }
        runIo {
            val existing = store.listPlaylists()
            val containing = store.playlistsContaining(tracks.map { it.id })
            runOnUiThread {
                // "0" is the synthetic new-playlist row; 1..n are the real playlists.
                val labels = ArrayList<String>(existing.size + 1)
                labels.add("+ New playlist")
                for ((id, name) in existing) {
                    // A tick on the playlists that already hold these tracks. That is the
                    // whole answer to "where does this track live?" — asked here, in the
                    // one dialog that is already about a track and its playlists, rather
                    // than as a second screen to hunt for.
                    val tick = if (id in containing) "\u2713 " else ""
                    labels.add(tick + name.ifBlank { "Untitled playlist" })
                }

                Dialogs.builder(this)
                    .setTitle(
                        if (tracks.size == 1) "Add \"${tracks[0].title}\" to"
                        else "Add ${tracks.size} tracks to"
                    )
                    .setItems(labels.toTypedArray()) { _, which ->
                        if (which == 0) createPlaylistThenAdd(tracks)
                        else {
                            val (id, name) = existing[which - 1]
                            // Ticking a playlist and then appending to it blindly would
                            // duplicate a row the tick says is already there, so it is a
                            // choice: open it, or add again anyway.
                            if (id in containing) alreadyInDialog(id, name, tracks)
                            else addTracksTo(id, tracks)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .chorusShow()
            }
        }
    }

    /** The playlist picked already holds these tracks: open it, or append again. */
    private fun alreadyInDialog(id: Long, name: String, tracks: List<Track>) {
        Dialogs.builder(this)
            .setTitle("Already in ${name.ifBlank { "Untitled playlist" }}")
            .setItems(arrayOf("Open it", "Add it again")) { _, which ->
                when (which) {
                    0 -> openPlaylist(id, name)
                    1 -> addTracksTo(id, tracks)
                }
            }
            .setNegativeButton("Cancel", null)
            .chorusShow()
    }

    /** Name a new playlist, then add the waiting tracks to it. */
    private fun createPlaylistThenAdd(tracks: List<Track>) {
        val input = EditText(this).apply {
            hint = "Playlist name"
            // Give the keyboard a stable field; the dialog below has no view of its own.
            isFocusable = true
            isFocusableInTouchMode = true
        }
        Dialogs.builder(this)
            .setTitle("New playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    runIo {
                        val id = store.createPlaylist(name)
                        store.addTracks(id, tracks.map { it.id })
                        runOnUiThread { refreshPlaylists() }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .chorusShow()
    }

    /**
     * Turn the queue into a real playlist: name it, write it, leave the queue alone.
     * The ids arrive in queue order from the panel's Save button, so the playlist keeps
     * the order the user arranged — the same ordering addTracks would rebuild anyway.
     */
    private fun saveQueueAsPlaylist(ids: List<Long>) {
        val input = EditText(this).apply {
            hint = "Playlist name"
            isFocusable = true
            isFocusableInTouchMode = true
        }
        Dialogs.builder(this)
            .setTitle("Save queue")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    runIo {
                        val id = store.createPlaylist(name)
                        store.addTracks(id, ids)
                        runOnUiThread {
                            refreshPlaylists()
                            Toast.makeText(
                                this, "Saved “$name”", Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .chorusShow()
    }

    private fun addTracksTo(playlistId: Long, tracks: List<Track>) {
        runIo {
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

    /**
     * Long-press options for a saved playlist: rename, export to .m3u8, sort alphabetically,
     * or delete.
     */
    private fun playlistOptionsDialog(id: Long, name: String) {
        val label = name.ifBlank { "Untitled playlist" }
        val options = arrayOf("Rename", "Export", "Sort A\u2013Z", "Delete")
        Dialogs.builder(this)
            .setTitle(label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> renamePlaylistDialog(id, label)
                    1 -> startExport(id, label)
                    2 -> sortPlaylistAZ(id, label)
                    3 -> confirmDeletePlaylist(id, label)
                }
            }
            .setNegativeButton("Cancel", null)
            .chorusShow()
    }

    /**
     * Rewrite a playlist's order alphabetically. Destructive to a hand-built order, which
     * is why it lives in the long-press sheet and not as a button on the detail screen:
     * it is a tidy-up, done once or not at all.
     *
     * A track the library no longer knows (a file deleted since) keeps its place at the
     * end rather than being dropped — sorting is not a reason to lose a row.
     */
    private fun sortPlaylistAZ(id: Long, label: String) {
        runIo {
            val byId = library.associateBy { it.id }
            val known = ArrayList<Track>()
            val gone = ArrayList<Long>()
            for (trackId in store.trackIdsIn(id)) {
                val track = byId[trackId]
                if (track != null) known.add(track) else gone.add(trackId)
            }
            val ordered = known.sortedWith(
                Comparator { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title) }
            ).map { it.id } + gone
            store.replaceTracks(id, ordered)
            runOnUiThread {
                Toast.makeText(this, "Sorted $label", Toast.LENGTH_SHORT).show()
                // If that playlist is on screen right now, its rows must follow the order.
                if (playlistPanel.currentPlaylistId() == id) playlistPanel.refresh()
            }
        }
    }

    private fun renamePlaylistDialog(id: Long, current: String) {
        val input = EditText(this).apply { setText(current) }
        Dialogs.builder(this)
            .setTitle("Rename playlist")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    runIo {
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
            .chorusShow()
    }

    private fun confirmDeletePlaylist(id: Long, name: String) {
        Dialogs.builder(this)
            .setTitle("Delete playlist")
            .setMessage("\"$name\" will be removed. The music files stay on this phone.")
            .setPositiveButton("Delete") { _, _ ->
                runIo {
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
            .chorusShow()
    }

    // ---------- M3U import / export ----------

    /**
     * Ask where to save the playlist (SAF picks the location — Chorus never writes
     * outside a document the user chose). The write happens in [writeExported] once
     * they pick one.
     */
    private fun startExport(id: Long, name: String) {
        pendingExport = id to name
        // A playlist name can contain anything; keep the suggested file name sane.
        val suggestion = name.replace('/', ' ').trim().ifEmpty { "Playlist" } + ".m3u8"
        exportLauncher.launch(suggestion)
    }

    /** Write the waiting playlist to the document the user just created. */
    private fun writeExported(uri: Uri) {
        val (id, name) = pendingExport ?: return
        pendingExport = null
        runIo {
            val byId = HashMap<Long, Track>(library.size)
            for (t in library) byId[t.id] = t
            // Favourites is not a table, so its rows come from the shared set; a real
            // playlist reads its ordering from the store.
            val tracks = if (id == PlaylistPanel.FAVOURITES) {
                Favourites.ids().mapNotNull { byId[it] }
            } else {
                store.trackIdsIn(id).mapNotNull { byId[it] }
            }
            val ok = try {
                // "wt" truncates: re-exporting over an existing file must not append.
                contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(M3u.build(tracks).toByteArray(Charsets.UTF_8))
                } != null
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "Exported “$name”" else "Couldn't export “$name”",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** Pick an .m3u/.m3u8 to import (ACTION_OPEN_DOCUMENT), then parse it. */
    private fun startImport() {
        importLauncher.launch(IMPORT_MIME)
    }

    /**
     * Turn the picked playlist file into a Chorus playlist: entries are matched
     * against the library by file path (relative entries resolved against the
     * playlist's own folder where SAF can tell us it), unmatched entries are skipped
     * and counted in one toast, and the only thing ever written is our own database.
     * Re-importing a file imported before updates that playlist instead of duplicating
     * it (see `PlaylistStore.playlist_origin`).
     */
    private fun importPlaylist(uri: Uri) {
        runIo {
            val text = try {
                contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            } catch (e: Exception) {
                null
            }
            if (text == null) {
                runOnUiThread {
                    Toast.makeText(this, "Couldn't read that playlist file", Toast.LENGTH_SHORT)
                        .show()
                }
                return@runIo
            }

            val raw = displayName(uri) ?: "Imported playlist"
            val name = raw.replace(Regex("\\.m3u8?$", RegexOption.IGNORE_CASE), "")
                .trim().ifEmpty { "Imported playlist" }
            val baseDir = baseDirOf(uri)

            val byPath = HashMap<String, Long>(library.size)
            for (t in library) if (t.path.isNotEmpty()) byPath[t.path] = t.id

            val matched = ArrayList<Long>()
            var missed = 0
            for (entry in M3u.entries(text)) {
                val id = byPath[entry] ?: baseDir?.let { base ->
                    byPath[runCatching { Paths.get(base, entry).normalize().toString() }.getOrNull()]
                }
                if (id != null) matched.add(id) else missed++
            }
            if (matched.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(
                        this, "No tracks from that playlist are on this phone", Toast.LENGTH_LONG
                    ).show()
                }
                return@runIo
            }

            val source = uri.toString()
            val existing = store.playlistIdByOrigin(source)
            if (existing != null) {
                store.replaceTracks(existing, matched)
                store.setOrigin(existing, source)
            } else {
                val newId = store.createPlaylist(name)
                store.addTracks(newId, matched)
                store.setOrigin(newId, source)
            }

            val summary = buildString {
                append(if (matched.size == 1) "1 track" else "${matched.size} tracks")
                append(if (existing != null) " updated" else " added")
                if (missed > 0) append(", $missed not found on this phone")
            }
            runOnUiThread {
                refreshPlaylists()
                // Keep an open detail view in step when its playlist was updated.
                if (existing != null && playlistPanel.currentPlaylistId() == existing) {
                    playlistPanel.refresh()
                }
                Toast.makeText(this, "“$name”: $summary", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Display name SAF has for [uri]; used as the new playlist's name. */
    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    /**
     * The on-device folder holding the picked document, where SAF can express it —
     * only needed to resolve *relative* entry paths. The parent comes from stripping
     * the last path segment off the document id itself (file-style ids like
     * "primary:Music/list.m3u8"); a primary-storage id maps onto the well-known
     * primary mount point. Ids that are not path-shaped yield null, and their
     * relative entries simply land in the unmatched count.
     */
    private fun baseDirOf(uri: Uri): String? {
        if (!DocumentsContract.isDocumentUri(this, uri)) return null
        val docId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (e: Exception) {
            return null
        }
        // Downloads-root picks hand back a MediaStore file id ("msf:67462") instead of a
        // path-shaped one — no slash, not "primary:" — which used to fall through to
        // null and silently broke every *relative* entry in a playlist chosen from
        // Downloads (the most common place for them: matched=0, nothing imported).
        // The id is a real file row: ask MediaStore where it lives.
        if (!docId.contains('/') && !docId.startsWith("primary:")) {
            val mediaId = docId.substringAfter(':', "").takeIf {
                docId.contains(':') && it.isNotEmpty() && it.all { ch -> ch.isDigit() }
            }?.toLong()
            if (mediaId != null) {
                @Suppress("DEPRECATION") // DATA: the only column that carries the path
                val path = try {
                    contentResolver.query(
                        ContentUris.withAppendedId(
                            Uri.parse("content://media/external/file"), mediaId
                        ),
                        arrayOf(MediaStore.MediaColumns.DATA), null, null, null
                    )?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                } catch (e: Exception) {
                    null
                }
                if (path != null) return path.substringBeforeLast('/')
            }
            return null
        }
        val parent = when {
            docId.contains('/') -> docId.substringBeforeLast('/')
            // Document at its volume's root — the volume prefix is the parent dir.
            docId.startsWith("primary:") -> "primary:"
            else -> return null
        }
        return when {
            parent.startsWith("primary:") -> {
                // Deprecated since API 29, but still the only way to name the primary
                // mount point — MediaStore does not hand it back as a path.
                @Suppress("DEPRECATION")
                val primary = Environment.getExternalStorageDirectory().absolutePath
                val rel = parent.removePrefix("primary:")
                if (rel.isEmpty()) primary else "$primary/$rel"
            }
            parent.startsWith("/") -> parent
            else -> null
        }
    }

    private fun wireTabs() {
        val map = mapOf(
            // track_screen (sort bar + list) is shown as one unit, so the screen id —
            // not the bare RecyclerView — is what the tab strip switches between.
            R.id.tab_tracks to R.id.track_screen,
            R.id.tab_albums to R.id.album_screen,
            R.id.tab_folders to R.id.folder_panel,
            R.id.tab_playlists to R.id.playlist_home,
            R.id.tab_search to R.id.search_panel,
            R.id.tab_settings to R.id.settings_panel
        )
        // The accent underline that sits under whichever tab is selected. Six tabs: the
        // queue dropped out of the strip once it became reachable from the player.
        val indicators = mapOf(
            R.id.tab_tracks to R.id.tab_indicator_tracks,
            R.id.tab_albums to R.id.tab_indicator_albums,
            R.id.tab_folders to R.id.tab_indicator_folders,
            R.id.tab_playlists to R.id.tab_indicator_playlists,
            R.id.tab_search to R.id.tab_indicator_search,
            R.id.tab_settings to R.id.tab_indicator_settings
        )

        tabButtons = map.keys.associateWith { findViewById(it) }
        tabIndicators = indicators.keys.associateWith { findViewById(indicators.getValue(it)) }

        // Underline colours come from the theme in code, never from a @color drawable: a
        // colour resource resolves by configuration (day/night), not by theme, so it would
        // ignore the Cycle theme entirely.
        for (indicator in tabIndicators.values) indicator.background = Ui.accentBar(this)

        for ((tab, target) in map) {
            val button = tabButtons.getValue(tab)
            // The pill and the label colour come from the button's own selected state, so a
            // tab change costs one setSelected() and nothing else has to be redrawn.
            button.background = Ui.tabBackground(this)
            button.setTextColor(Ui.tabTextColors(this))
            button.setOnClickListener { show(target) }
        }

        // Hairline between the strip and the content: tinted from the theme, not a literal.
        findViewById<View>(R.id.tab_divider).setBackgroundColor(
            Ui.faintColor(this, android.R.attr.textColorSecondary, HAIRLINE_ALPHA)
        )

        // track_screen is the layout's default panel, so Tracks starts lit.
        highlightTab(R.id.tab_tracks)
    }

    /**
     * Light one tab: accent bold label, the filled pill (its background state list) and the
     * underline. Guarded, so re-showing the panel already on screen costs nothing.
     */
    private fun highlightTab(tab: Int) {
        if (tab == activeTab) return
        val previous = activeTab
        activeTab = tab
        styleTab(previous, false)
        styleTab(tab, true)
    }

    private fun styleTab(tab: Int, selected: Boolean) {
        if (tab == View.NO_ID) return
        tabButtons[tab]?.apply {
            isSelected = selected
            setTypeface(if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
        }
        tabIndicators[tab]?.visibility = if (selected) View.VISIBLE else View.GONE
    }

    private fun wireCycle() {
        cycleToggle = findViewById(R.id.cycle_toggle)
        cycleToggle.onChanged = { recreate() }
    }

    // ---------- settings ----------

    /**
     * The Setup tab. Every row writes straight through to [Settings] and updates its own
     * label, so the tab always shows what the app is actually doing.
     */
    private fun wireSettings() {
        // ReplayGain: off, track gain or album gain.
        val replayGainValue = findViewById<TextView>(R.id.settings_replaygain_value)
        fun showReplayGain() {
            replayGainValue.text = when (Settings.replayGainMode(this)) {
                Settings.ReplayGainMode.OFF -> "Off"
                Settings.ReplayGainMode.TRACK -> "Track"
                Settings.ReplayGainMode.ALBUM -> "Album"
            }
        }
        showReplayGain()
        findViewById<View>(R.id.settings_replaygain).setOnClickListener {
            val modes = Settings.ReplayGainMode.entries.toTypedArray()
            val labels = arrayOf("Off", "Track gain", "Album gain")
            val current = modes.indexOf(Settings.replayGainMode(this))
            // A single-choice dialog does not close itself, so it is dismissed by hand.
            var dialog: AlertDialog? = null
            dialog = Dialogs.builder(this)
                .setTitle("ReplayGain")
                .setSingleChoiceItems(labels, current) { _, which ->
                    // The service re-applies the mode to the track that is playing, so the
                    // change is audible at once.
                    Settings.setReplayGainMode(this, modes[which])
                    showReplayGain()
                    dialog?.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .chorusShow()
        }

        // Equaliser: the phone's own effect chain, as one of its presets or hand-set bands.
        val eqValue = findViewById<TextView>(R.id.settings_equaliser_value)
        fun showEqualiser() {
            val caps = eqCaps
            eqValue.text = when {
                caps == null -> getString(R.string.settings_equaliser_unavailable)
                !Settings.isEqualiserOn(this) -> "Off"
                Settings.equaliserPreset(this) == Settings.EQ_CUSTOM ->
                    getString(R.string.settings_equaliser_custom)
                else -> caps.presets.getOrElse(Settings.equaliserPreset(this)) {
                    caps.presets.firstOrNull() ?: "Off"
                }
            }
        }
        showEqualiser()
        // The device's preset names live inside the effect chain and nowhere else, so the
        // row can only be labelled after one short probe — cached for the screen's life.
        runIo {
            val caps = EqualiserCaps.probeCaps()
            runOnUiThread {
                eqCaps = caps
                showEqualiser()
            }
        }
        findViewById<View>(R.id.settings_equaliser).setOnClickListener {
            val caps = eqCaps
            if (caps == null) {
                Toast.makeText(
                    this, R.string.settings_equaliser_missing, Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            // One list, because it is one decision: off, a device preset, or the sliders.
            val labels = ArrayList<String>(caps.presets.size + 2)
            labels += "Off"
            labels += caps.presets
            labels += getString(R.string.settings_equaliser_custom) + "\u2026"
            val customRow = labels.size - 1
            val current = when {
                !Settings.isEqualiserOn(this) -> 0
                Settings.equaliserPreset(this) == Settings.EQ_CUSTOM -> customRow
                else -> 1 + Settings.equaliserPreset(this).coerceIn(0, caps.presets.size - 1)
            }
            var dialog: AlertDialog? = null
            dialog = Dialogs.builder(this)
                .setTitle(R.string.settings_equaliser_title)
                .setSingleChoiceItems(labels.toTypedArray(), current) { _, which ->
                    when (which) {
                        0 -> Settings.setEqualiserOn(this, false)
                        customRow -> {
                            // The editor saves for itself; this list just gets out of its way.
                            dialog?.dismiss()
                            equaliserBandDialog(caps)
                        }
                        else -> {
                            Settings.setEqualiserPreset(this, which - 1)
                            Settings.setEqualiserOn(this, true)
                        }
                    }
                    showEqualiser()
                    if (which != customRow) dialog?.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .chorusShow()
        }

        // Resume position: reopen on the same track at the same point.
        val resumeSwitch = findViewById<SwitchCompat>(R.id.settings_resume_switch)
        resumeSwitch.isChecked = Settings.resumePosition(this)
        resumeSwitch.setOnCheckedChangeListener { _, checked ->
            Settings.setResumePosition(this, checked)
        }

        // Default shuffle: the state every session starts in.
        val shuffleSwitch = findViewById<SwitchCompat>(R.id.settings_shuffle_switch)
        shuffleSwitch.isChecked = Settings.defaultShuffle(this)
        shuffleSwitch.setOnCheckedChangeListener { _, checked ->
            Settings.setDefaultShuffle(this, checked)
        }

        // Skip distance: how far the player's own skip buttons jump.
        val skipValue = findViewById<TextView>(R.id.settings_skip_value)
        fun showSkip() {
            skipValue.text = getString(R.string.settings_skip_value, Settings.skipSeconds(this))
        }
        showSkip()
        findViewById<View>(R.id.settings_skip).setOnClickListener {
            val choices = intArrayOf(5, 10, 15)
            val labels = choices.map { getString(R.string.settings_skip_value, it) }
                .toTypedArray()
            val current = choices.indexOf(Settings.skipSeconds(this))
            // Same shape as the ReplayGain row: a single-choice list that shows what is
            // selected and closes itself on the pick.
            var dialog: AlertDialog? = null
            dialog = Dialogs.builder(this)
                .setTitle(R.string.settings_skip_title)
                .setSingleChoiceItems(labels, current) { _, which ->
                    Settings.setSkipSeconds(this, choices[which])
                    showSkip()
                    dialog?.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .chorusShow()
        }

        // Play on connect: plug in headphones or pair Bluetooth and it starts.
        val connectSwitch = findViewById<SwitchCompat>(R.id.settings_connect_switch)
        connectSwitch.isChecked = Settings.playOnConnect(this)
        connectSwitch.setOnCheckedChangeListener { _, checked ->
            Settings.setPlayOnConnect(this, checked)
        }

        // Restore after reboot: the queue comes back paused rather than empty.
        val bootSwitch = findViewById<SwitchCompat>(R.id.settings_boot_switch)
        bootSwitch.isChecked = Settings.restoreOnBoot(this)
        bootSwitch.setOnCheckedChangeListener { _, checked ->
            Settings.setRestoreOnBoot(this, checked)
        }

        // Rescan: read the phone's music library again, in case files have changed.
        findViewById<View>(R.id.settings_rescan).setOnClickListener {
            loadLibrary()
            Toast.makeText(this, "Rescanning music library", Toast.LENGTH_SHORT).show()
        }

        // About: what the app is, what it is built on, and the licence it ships under.
        findViewById<View>(R.id.settings_about).setOnClickListener { showAbout() }
    }

    /**
     * The equaliser's band editor: one slider per band, spanning the range this device
     * allows.
     *
     * Saved as millibels and applied as "custom", which also switches the equaliser on — a
     * set of sliders you have just set is not a state to leave switched off behind you. The
     * three writes each tell the service to re-apply, so their order matters: the preset
     * identity first (so the bands are read as *the* bands), then the levels, then the
     * switch. On a phone with no equaliser the service simply has nothing to apply to, and
     * the settings are still saved for the day it does.
     */
    private fun equaliserBandDialog(caps: EqualiserCaps) {
        val saved = Settings.equaliserBands(this)
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val labelWidth = (64 * density).toInt()

        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bars = ArrayList<SeekBar>(caps.bandCount)
        for (i in 0 until caps.bandCount) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, 0, pad, 0)
            }
            row.addView(
                TextView(this).apply {
                    text = caps.bandLabels[i]
                    textSize = 14f
                },
                LinearLayout.LayoutParams(labelWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            val bar = SeekBar(this).apply {
                max = EQUALISER_SLIDER_STEPS
                progress = levelToSlider(saved.getOrElse(i) { 0 }, caps)
            }
            row.addView(
                bar,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            bars += bar
            rows.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        Dialogs.builder(this)
            .setTitle(getString(R.string.settings_equaliser_custom))
            .setView(rows)
            .setPositiveButton("Save") { _, _ ->
                val levels = IntArray(caps.bandCount) { i ->
                    sliderToLevel(bars[i].progress, caps)
                }
                Settings.setEqualiserPreset(this, Settings.EQ_CUSTOM)
                Settings.setEqualiserBands(this, levels)
                Settings.setEqualiserOn(this, true)
                findViewById<TextView>(R.id.settings_equaliser_value)
                    .setText(R.string.settings_equaliser_custom)
            }
            .setNegativeButton("Cancel", null)
            .chorusShow()
    }

    /** Millibels -> slider position, inside the range this device allows. */
    private fun levelToSlider(millibels: Int, caps: EqualiserCaps): Int {
        val span = (caps.maxMillibels - caps.minMillibels).coerceAtLeast(1)
        return ((millibels - caps.minMillibels) * EQUALISER_SLIDER_STEPS / span)
            .coerceIn(0, EQUALISER_SLIDER_STEPS)
    }

    /** Slider position -> millibels, clamped to what the device said it can do. */
    private fun sliderToLevel(position: Int, caps: EqualiserCaps): Int {
        val span = (caps.maxMillibels - caps.minMillibels).coerceAtLeast(1)
        return (caps.minMillibels + position * span / EQUALISER_SLIDER_STEPS)
            .coerceIn(caps.minMillibels, caps.maxMillibels)
    }

    /**
     * The about sheet: the version, the privacy line, and the third-party components with
     * their licences. The app is GPL-3.0, so the licence text ships with the app (in
     * res/raw) rather than only in the repository.
     */
    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "1.0"
        }
        val message = getString(R.string.about_text, version)
        Dialogs.builder(this)
            .setTitle("Chorus")
            .setMessage(message)
            .setPositiveButton("Licence") { _, _ -> showLicence() }
            .setNegativeButton("Close", null)
            .chorusShow()
    }

    /** The licence text, shipped in res/raw so it travels with the app. */
    private fun showLicence() {
        val text = try {
            resources.openRawResource(R.raw.licence_gpl_3_0).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            getString(R.string.about_licence_missing)
        }
        Dialogs.builder(this)
            .setTitle("GNU General Public License v3")
            .setMessage(text)
            .setPositiveButton("Close", null)
            .chorusShow()
    }

    /** Show one content view, hide the rest of the top-level panels. */
    private fun show(target: Int) {
        activePanel = target
        // Back has somewhere to step out of everywhere except the track screen (see
        // stepBack); there the system handles it and the app leaves the normal way.
        backStep.isEnabled = target != R.id.track_screen
        val panels = listOf(
            R.id.track_screen, R.id.album_screen, R.id.artist_panel, R.id.folder_panel,
            R.id.playlist_home, R.id.playlist_panel, R.id.search_panel, R.id.queue_panel,
            R.id.settings_panel
        )
        for (id in panels) {
            val v: View = findViewById(id)
            v.visibility = if (id == target) View.VISIBLE else View.GONE
        }
        // The rail belongs to one panel: refresh it now that a different one is up.
        updateAzRail()
        // Keep the strip in step with the content. playlist_panel is the Lists tab's own
        // detail screen, so opening a playlist leaves that tab lit.
        PANEL_TABS[target]?.let { highlightTab(it) }
        if (target == R.id.playlist_home) {
            // Re-runs the list query (and its empty state) on every visit, so a
            // playlist created or deleted elsewhere is never stale on screen.
            refreshPlaylists()
        }
    }

    /**
     * The back ladder, one step per press: a folder first (up a directory, like the
     * breadcrumb), then whatever detail screen is up back to the tab it belongs to, then
     * any tab back to the home track screen, and only from there does the app leave —
     * which is what [backStep]'s enabled flag tracks, so at home the system gets the
     * back event itself (and its predictive-back animation) exactly as before.
     */
    private fun wireBack() {
        backStep = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = stepBack()
        }
        onBackPressedDispatcher.addCallback(this, backStep)
    }

    private fun stepBack() {
        // The artist panel overlays the Albums tab: back first steps up one artist level
        // (to the artist list), then show(album_screen) brings the album list back —
        // which also tears the overlay down, since it is not a switchable panel.
        if (activePanel == R.id.album_screen && artistPanel.visibility == View.VISIBLE) {
            if (artistPanel.goUp()) return
            show(R.id.album_screen)
            return
        }
        // Folders walk up a level, exactly like tapping the parent crumb.
        if (activePanel == R.id.folder_panel && folderPanel.goUp()) return
        // A playlist detail screen belongs to the Lists tab — one step back to it.
        if (activePanel == R.id.playlist_panel) {
            show(R.id.playlist_home)
            return
        }
        // Queue (the player's way in) and every tab land on the home track screen.
        if (activePanel != R.id.track_screen) {
            show(R.id.track_screen)
            return
        }
        // Nothing left to step out of; the enabled flag keeps this unreachable in
        // practice, but leaving it as "then leave" makes the ladder total.
        finish()
    }

    /**
     * Push the controller's current track into every track list on screen so the now-playing
     * cue follows the one shared player. Media ids are the MediaStore track id (see
     * PlayerHolder.play), which is exactly what the adapters compare against.
     */
    private fun publishNowPlaying(player: Player) {
        val id = player.currentMediaItem?.mediaId?.toLongOrNull()
        trackAdapter.currentTrackId = id
        searchPanel.setCurrentTrackId(id)
        playlistPanel.setCurrentTrackId(id)
        folderPanel.setCurrentTrackId(id)
    }

    override fun onDestroy() {
        // Undo the Activity-lifetime pairing from wireLists. Favourites holds listeners
        // strongly and statically, so anything not removed here is leaked for good —
        // including across every recreate() from the cycle-mode toggle. PlayerHolder holds
        // its listeners just as strongly, so the now-playing push is unhooked the same way.
        trackAdapter.stopObservingFavourites()
        PlayerHolder.removePlayerListener(playerListener)
        super.onDestroy()
        io.shutdown()
    }

    internal companion object {
        /**
         * Intent extra: open this screen on its queue tab. Set by the full-screen player's
         * queue pill. Lives here rather than in PlayerPanel because the *destination*
         * defines the contract, and PlayerPanel's companion is private.
         */
        const val EXTRA_OPEN_QUEUE = "chorus.open_queue"

        /**
         * Intent extra: open this screen on the folder holding this file path. Set by the
         * full-screen player's track info sheet — "Show in folder". Like the queue extra,
         * the destination owns the contract.
         */
        const val EXTRA_OPEN_FOLDER = "chorus.open_folder"

        /** Slider travel in the equaliser's band editor, in steps. */
        const val EQUALISER_SLIDER_STEPS = 100

        /** Sort modes for the track list; index into [sortButtons]. */
        const val SORT_TITLE = 0
        const val SORT_PLAYS = 1
        const val SORT_RECENT = 2
        const val SORT_ADDED = 3

        /** Edge of a playlist row's cover in dp; its mosaic tiles are half that. */
        const val PLAYLIST_COVER_DP = 44

        /** Album sort modes; index into [albumSortButtons]. */
        const val ALBUM_SORT_TITLE = 0
        const val ALBUM_SORT_ARTIST = 1
        const val ALBUM_SORT_YEAR = 2
        const val ALBUM_SORT_ADDED = 3

        /** How Android classifies .m3u/.m3u8 — export document type and import filter. */
        const val M3U_MIME = "audio/x-mpegurl"

        /** Import picker filter: the m3u types plus what file managers actually report. */
        val IMPORT_MIME = arrayOf(
            M3U_MIME, "application/vnd.apple.mpegurl", "text/plain",
            "audio/*", "application/octet-stream"
        )

        /** Instance-state keys for an export pick still waiting on the picker. */
        const val STATE_EXPORT_ID = "export_id"
        const val STATE_EXPORT_NAME = "export_name"

        /** Panel shown -> the tab that owns it. Drives the tab strip's selected state. */
        val PANEL_TABS = mapOf(
            R.id.track_screen to R.id.tab_tracks,
            R.id.album_screen to R.id.tab_albums,
            R.id.folder_panel to R.id.tab_folders,
            R.id.playlist_home to R.id.tab_playlists,
            R.id.playlist_panel to R.id.tab_playlists,
            R.id.search_panel to R.id.tab_search,
            // Deliberately no entry for queue_panel: the queue has no tab, so showing it
            // (only ever from the player's queue pill) leaves whichever tab was lit as it
            // was. PANEL_TABS[target] is read with `?.`, so a missing key is a no-op.
            R.id.settings_panel to R.id.tab_settings
        )

        /** Alpha of the strip's hairline, over the theme's secondary text colour. */
        const val HAIRLINE_ALPHA = 0x24
    }
}