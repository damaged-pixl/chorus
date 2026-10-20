package com.bee.chorus.ui

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bee.chorus.R
import com.bee.chorus.data.Settings
import com.bee.chorus.data.Track
import com.bee.chorus.playback.PlayerHolder

/**
 * Folder browser: the library grouped by the directory each file lives in.
 *
 * A pure renderer over the injected library — it scans nothing, queries nothing and
 * caches nothing to disk. [setLibrary] snapshots the tracks (with their parent paths
 * precomputed once) and every navigation re-derives the current level from that in
 * memory, so a deep tree costs nothing until you walk into it.
 *
 * The list holds two sections through one [ConcatAdapter]: the current directory's
 * subfolders ([FolderAdapter]) followed by its tracks (a real [TrackAdapter], so row
 * layout, stars, taps and the now-playing cue behave exactly as on the Tracks tab —
 * ConcatAdapter maps positions and notifications per inner adapter). Navigation is the
 * breadcrumb strip: tap any ancestor to jump there; there is no other nav chrome.
 */
class FolderPanel @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : LinearLayout(ctx, attrs) {

    private val crumbBar: LinearLayout
    private val emptyView: TextView
    private val listView: RecyclerView

    private val folderAdapter = FolderAdapter(onClick = { name -> openChild(name) })
    private val trackAdapter = TrackAdapter(
        onClick = { index, list -> if (index in list.indices) PlayerHolder.play(list, index) }
    )

    /**
     * Long-press handler for track rows, installed by the host (the same add-to-playlist
     * chooser the other lists get). Forwarded into the inner TrackAdapter.
     */
    var onTrackLongClick: ((Track) -> Unit)? = null
        set(value) {
            field = value
            trackAdapter.onLongClick = value
        }

    /** (parent path, track) per library track — parent strings precomputed once. */
    private var entries: List<Pair<String, Track>> = emptyList()

    /** Top of the tree: the common parent of every track's folder. */
    private var root = ""

    /** Directory currently on screen; always [root] or below it. */
    private var current = ""

    init {
        orientation = LinearLayout.VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_folders, this, true)

        crumbBar = findViewById(R.id.folder_crumbs)
        emptyView = findViewById(R.id.folder_empty)
        listView = findViewById(R.id.folder_list)

        listView.layoutManager = LinearLayoutManager(context)
        listView.adapter = ConcatAdapter(folderAdapter, trackAdapter)

        refresh()
    }

    // ---------------------------------------------------------------- public

    /**
     * Supply the library. Rebuilds the tree from scratch and returns to the root, so
     * the host simply calls this after every scan.
     */
    fun setLibrary(all: List<Track>) {
        entries = all.map { it.parentPath to it }
        root = commonRoot(all.map { it.parentPath })
        current = root
        refresh()
    }

    /**
     * MediaStore id of the track the shared player holds, or null. Pushed down by the
     * host (the same value the track list gets); folder rows are not affected.
     */
    fun setCurrentTrackId(id: Long?) {
        trackAdapter.currentTrackId = id
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Paired with the detach below: RecyclerView's own hooks never fire on Activity
        // destroy, so the window lifecycle is the leak-safe pairing (same as SearchPanel).
        trackAdapter.observeFavourites()
    }

    override fun onDetachedFromWindow() {
        trackAdapter.stopObservingFavourites()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- levels

    /** Derive the current directory's subfolders and tracks and rebind everything. */
    private fun refresh() {
        // One pass: tracks directly in `current`, and one count per immediate child
        // folder (every deeper track counts towards its first segment under `current`,
        // which makes it a subtree total for free).
        val folders = LinkedHashMap<String, Int>()
        val here = ArrayList<Track>()
        for ((parent, t) in entries) {
            if (parent == current) {
                here.add(t)
            } else if (parent.startsWith("$current/")) {
                val child = parent.substring(current.length + 1).substringBefore('/')
                if (child.isNotEmpty()) folders[child] = (folders[child] ?: 0) + 1
            }
        }

        folderAdapter.submit(
            folders.entries
                .map { it.key to it.value }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
        )
        // Library order is title order (MediaStore scan order) — right for one folder.
        trackAdapter.submit(here)
        buildBreadcrumb()

        val empty = folders.isEmpty() && here.isEmpty()
        emptyView.text = if (entries.isEmpty()) EMPTY_LIBRARY else EMPTY_FOLDER
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
    }

    /** Descend into the child folder [name] of the current directory. */
    private fun openChild(name: String) {
        // When root is "" (no common prefix at all) the current path is the empty
        // string and children must still get their leading slash.
        current = if (current.isEmpty()) "/$name" else "$current/$name"
        refresh()
        listView.scrollToPosition(0)
    }

    /**
     * Rebuild the breadcrumb strip: the root's display name, then one tappable segment
     * per level below it. The current level is not a link to itself — tapping any
     * ancestor (including the root) jumps straight there.
     */
    private fun buildBreadcrumb() {
        crumbBar.removeAllViews()
        if (entries.isEmpty()) return

        val crumbs = ArrayList<Pair<String, String>>(8)
        crumbs.add(root to if (root.isEmpty()) "/" else root.substringAfterLast('/'))
        val rel = current.removePrefix(root).trim('/')
        if (rel.isNotEmpty()) {
            var path = root
            for (segment in rel.split('/')) {
                path = if (path.isEmpty()) "/$segment" else "$path/$segment"
                crumbs.add(path to segment)
            }
        }

        val primary = Theming.color(context, R.attr.chorusTextPrimary)
        val secondary = Theming.color(context, R.attr.chorusTextSecondary)
        for (i in crumbs.indices) {
            if (i > 0) crumbBar.addView(separator())
            val (path, label) = crumbs[i]
            val last = i == crumbs.lastIndex
            crumbBar.addView(
                crumb(label, path, if (last) primary else secondary, clickable = !last)
            )
        }

        // The crumbs are built after the host's one-shot CycleMode.apply, so in cycle
        // mode they get the gloved-hand treatment here. apply() is idempotent (it
        // scales from the first remembered original), and the Activity recreates on a
        // cycle toggle, so this mirrors the host's state exactly.
        if (Settings.isCycleMode(context)) CycleMode.apply(crumbBar, true)
    }

    private fun crumb(label: String, path: String, colour: Int, clickable: Boolean): TextView =
        TextView(context).apply {
            text = label
            textSize = CRUMB_TEXT_SP
            setTextColor(colour)
            gravity = Gravity.CENTER_VERTICAL
            // Sort-bar-style padding: the touch target needs a little room of its own.
            setPadding(Ui.dp(context, 6f).toInt(), Ui.dp(context, 12f).toInt(),
                Ui.dp(context, 6f).toInt(), Ui.dp(context, 12f).toInt())
            if (clickable) {
                // Themed press feedback — the attribute resolved to a real drawable id
                // first (passing an R.attr id straight to setBackgroundResource is not
                // a lookup Resources can make). CycleMode enlarges these to 72dp like
                // any other touch target.
                val press = TypedValue()
                if (context.theme.resolveAttribute(
                        android.R.attr.selectableItemBackground, press, true
                    ) && press.resourceId != 0
                ) {
                    setBackgroundResource(press.resourceId)
                }
                isClickable = true
                isFocusable = true
                contentDescription = "Go to $label"
                setOnClickListener {
                    current = path
                    refresh()
                    listView.scrollToPosition(0)
                }
            } else {
                contentDescription = "$label, current folder"
            }
        }

    private fun separator(): TextView = TextView(context).apply {
        text = " / "
        textSize = CRUMB_TEXT_SP
        setTextColor(Theming.color(context, R.attr.chorusTextSecondary))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // ---------------------------------------------------------------- derivation

    /**
     * Longest directory prefix shared by every track's parent folder — the sensible top
     * of the tree (`/storage/emulated/0/Music` when all music sits under Music,
     * `/storage/emulated/0` when it spans Music and Download). Starts from the first
     * parent in full and shrinks until every path sits inside it; an unknown (empty)
     * path collapses the root to "", which the levels below still handle.
     */
    private fun commonRoot(paths: List<String>): String {
        if (paths.isEmpty()) return ""
        var common = paths[0]
        for (p in paths) {
            while (common.isNotEmpty() && p != common && !p.startsWith("$common/")) {
                common = common.substringBeforeLast('/', "")
            }
            if (common.isEmpty()) return ""
        }
        return common
    }

    private companion object {
        const val CRUMB_TEXT_SP = 13f
        const val EMPTY_LIBRARY = "No music on this phone yet"
        const val EMPTY_FOLDER = "No music in this folder"
    }
}
