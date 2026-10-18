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
 * asynchronously — a star tap never blocks the UI. All listeners are held strongly:
 * they are removed by view lifecycle hooks (`onAttachedToRecyclerView`,
 * `onDetachedFromWindow`), so **anything registering one outside those hooks must
 * remove it in the Activity's onDestroy** to avoid leaking it across `recreate()`.
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
            // LinkedHashSet so iteration order is "order first starred", matching a
            // star-toggle order (appending keeps it; removal preserves it).
            ids = LinkedHashSet(s.favouriteIds())
            // "Everything changed" broadcast: any list already on screen re-binds its stars.
            main.post { for (l in listeners) l(ALL, false) }
        }
    }

    /** True if [trackId] is starred. Cheap — reads the in-memory set only. */
    fun isFavourite(trackId: Long): Boolean = ids.contains(trackId)

    /** The whole set, ordered by first-starred. Snapshot copy semantics: iterate freely. */
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
