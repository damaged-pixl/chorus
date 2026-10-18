package com.bee.chorus.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * App-wide liked-songs (favourites) set, backed by the `favourites` table in
 * playlists.db.
 *
 * One in-memory set is shared by every screen — track rows, the full-screen player and
 * the Favourites pseudo-playlist — so a star toggled anywhere is reflected everywhere.
 * Call [init] once from an Activity's `onCreate` (idempotent); the initial load runs on
 * a background thread and broadcasts [ALL] when the real set arrives.
 *
 * [set] updates the cache and notifies listeners immediately (both on the calling
 * thread's data, notification posted to the main thread) and writes to the database
 * asynchronously — a star tap never blocks the UI. All listeners are held strongly, and
 * [listeners] is static, so **every registration must be removed** or it is leaked for the
 * life of the process. Pair add/remove with the host's own lifecycle: an Activity's
 * onCreate/onDestroy (`MainActivity`, `PlayerActivity`) or a view's
 * `onAttachedToWindow`/`onDetachedFromWindow` (`PlayerPanel`, `PlaylistPanel`,
 * `SearchPanel`, and `TrackAdapter` driven by whichever of those hosts it).
 * `onAttachedToRecyclerView`/`onDetachedFromRecyclerView` are NOT such a pair —
 * RecyclerView only calls those on an adapter swap, never on Activity destroy.
 */
object Favourites {

    /** trackId passed to listeners meaning "the whole set changed, re-query everything". */
    const val ALL = -1L

    private val main = Handler(Looper.getMainLooper())

    /** One daemon writer thread; also serialises the initial load. */
    private val io = Executors.newSingleThreadExecutor(DaemonThreadFactory)

    /** Held strongly; every registration site pairs add/remove with a view lifecycle. */
    private val listeners = CopyOnWriteArrayList<(Long, Boolean) -> Unit>()

    @Volatile private var store: PlaylistStore? = null

    /** The current set. Replaced wholesale on load, incrementally on every toggle. */
    @Volatile private var ids: Set<Long> = emptySet()

    /** Load the stored set in the background. Idempotent; safe from any thread. */
    fun init(ctx: Context) {
        if (store != null) return
        store = PlaylistStore(ctx.applicationContext)
        io.execute {
            val s = store ?: return@execute
            // LinkedHashSet so the set has a stable iteration order for callers (the
            // Favourites pseudo-playlist renders in it). That order is *not* a star-time
            // order: favouriteIds() reads `ORDER BY rowid ASC` and track_id IS the rowid
            // (INTEGER PRIMARY KEY, no AUTOINCREMENT), so a fresh load yields ascending
            // MediaStore id. Starring later appends at the end, and un-star/re-star never
            // reorders anything — there is no starred_at column to order by.
            ids = LinkedHashSet(s.favouriteIds())
            // "Everything changed" broadcast: any list already on screen re-binds its stars.
            main.post { for (l in listeners) l(ALL, false) }
        }
    }

    /** True if [trackId] is starred. Cheap — reads the in-memory set only. */
    fun isFavourite(trackId: Long): Boolean = ids.contains(trackId)

    /** The whole set. Snapshot copy semantics: iterate freely.
     *
     *  Iteration order is the in-memory LinkedHashSet's: ascending MediaStore id straight
     *  after the initial load (see [init]), then whatever order stars have since been
     *  appended in. It is never a star-time order, and nothing here re-sorts on toggle.
     */
    fun ids(): Set<Long> = ids

    /** Star or un-star [trackId] and broadcast the change. Main thread only (UI action). */
    fun set(trackId: Long, on: Boolean) {
        ids = if (on) ids + trackId else ids - trackId
        main.post { for (l in listeners) l(trackId, on) }
        store?.let { s -> io.execute { s.setFavourite(trackId, on) } }
    }

    /** Convenience: flip the current state of [trackId]. */
    fun toggle(trackId: Long) = set(trackId, !isFavourite(trackId))

    /** Register a change listener. Adding the same instance twice is a no-op. */
    fun addListener(l: (Long, Boolean) -> Unit) {
        listeners.addIfAbsent(l)
    }

    /** Unregister [l]. Safe to call when it was never added. */
    fun removeListener(l: (Long, Boolean) -> Unit) {
        listeners.remove(l)
    }

    private object DaemonThreadFactory : ThreadFactory {
        override fun newThread(r: Runnable): Thread =
            Thread(r, "chorus-favourites").apply { isDaemon = true }
    }
}
