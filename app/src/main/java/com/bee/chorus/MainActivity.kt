package com.bee.chorus

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bee.chorus.data.Album
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
        PlayerHolder.bind(this)

        wireLists()
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
                trackAdapter.submit(tracks)
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

    // ---------- wiring ----------

    private fun wireLists() {
        trackAdapter = TrackAdapter { index, list -> PlayerHolder.play(list, index) }
        albumAdapter = AlbumAdapter { album ->
            val tracks = library.filter { it.albumId == album.id }
                .sortedBy { it.trackNo }
            PlayerHolder.play(tracks, 0)
        }
        playlistAdapter = PlaylistAdapter { id, name -> openPlaylist(id, name) }

        findViewById<RecyclerView>(R.id.track_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = trackAdapter
        }
        findViewById<RecyclerView>(R.id.album_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = albumAdapter
        }
        findViewById<RecyclerView>(R.id.playlist_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = playlistAdapter
        }

        playlistPanel = findViewById(R.id.playlist_panel)
        searchPanel = findViewById(R.id.search_panel)
        queuePanel = findViewById(R.id.queue_panel)
        miniPlayer = findViewById(R.id.mini_player)

        // Tapping the mini player opens the full-screen player.
        miniPlayer.setOnClickListener { PlayerActivity.start(this) }

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

    private fun wireTabs() {
        val map = mapOf(
            R.id.tab_tracks to R.id.track_list,
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
            R.id.track_list, R.id.album_list, R.id.playlist_home, R.id.playlist_panel,
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
}