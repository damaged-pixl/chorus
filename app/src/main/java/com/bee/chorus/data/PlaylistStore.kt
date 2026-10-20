package com.bee.chorus.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local playlists. Plain SQLite (no Room) to keep the build and runtime light.
 * Tracks are referenced by their stable MediaStore id, so nothing is copied.
 *
 * Also holds the app's small writable state (`favourites` from schema v2,
 * `play_stats` from v3, the saved queue from v4, and `playlist_origin` from v5 — where an
 * imported playlist came from, so re-importing an updated .m3u updates it instead of
 * duplicating it). Note that several instances of this helper may exist at
 * once (MainActivity owns one, the [Favourites] facade and PlaybackService each open
 * their own with the application context) — Android's SQLiteDatabase pools connections
 * per file path, so that is safe.
 */
class PlaylistStore(context: Context) :
    SQLiteOpenHelper(context, Library.PLAYLIST_DB, null, 6) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE playlists(
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                created_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE playlist_tracks(
                playlist_id INTEGER NOT NULL,
                track_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY(playlist_id, track_id))"""
        )
        db.execSQL("CREATE INDEX idx_ptracks_pid ON playlist_tracks(playlist_id, position)")
        db.execSQL("CREATE TABLE favourites(track_id INTEGER PRIMARY KEY)")
        db.execSQL(
            """CREATE TABLE play_stats(
                track_id INTEGER PRIMARY KEY,
                plays INTEGER NOT NULL,
                last_played INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE playlist_origin(
                playlist_id INTEGER PRIMARY KEY,
                source TEXT NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE queue(
                position INTEGER PRIMARY KEY,
                media_id INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE queue_state(
                _id INTEGER PRIMARY KEY CHECK(_id = 0),
                current_index INTEGER NOT NULL,
                position_ms INTEGER NOT NULL,
                shuffle INTEGER NOT NULL,
                repeat_mode INTEGER NOT NULL)"""
        )
    }

    /**
     * Real migrations, one per schema bump:
     *  v1 -> v2 added the `favourites` table.
     *  v2 -> v3 added the `play_stats` table.
     *  v3 -> v4 added the queue tables (`queue` + `queue_state`).
     *  v4 -> v5 added the `playlist_origin` table (Phase E).
     *  v5 -> v6 re-creates the queue tables. Needed because the v5 build shipped with an
     *       empty v3→v4 branch (queue was still in development), so a phone that already
     *       upgraded to v5 would never run the v4 branch — an already-shipped migration
     *       must never be edited, the fix is always a new branch. `IF NOT EXISTS` makes
     *       every starting version converge on the same schema.
     * An oldVersion of 1 falls through every branch, so every historical version
     * reaches the current schema. Add the next bump as a new `if (old < N)` branch.
     */
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) db.execSQL("CREATE TABLE IF NOT EXISTS favourites(track_id INTEGER PRIMARY KEY)")
        if (old < 3) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS play_stats(
                    track_id INTEGER PRIMARY KEY,
                    plays INTEGER NOT NULL,
                    last_played INTEGER NOT NULL)"""
            )
        }
        if (old < 4) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS queue(
                    position INTEGER PRIMARY KEY,
                    media_id INTEGER NOT NULL)"""
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS queue_state(
                    _id INTEGER PRIMARY KEY CHECK(_id = 0),
                    current_index INTEGER NOT NULL,
                    position_ms INTEGER NOT NULL,
                    shuffle INTEGER NOT NULL,
                    repeat_mode INTEGER NOT NULL)"""
            )
        }
        if (old < 5) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS playlist_origin(
                    playlist_id INTEGER PRIMARY KEY,
                    source TEXT NOT NULL)"""
            )
        }
        if (old < 6) {
            // Catch-up for installs that reached v5 while the queue tables were still
            // gated behind the (then empty) v4 branch. See the KDoc above.
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS queue(
                    position INTEGER PRIMARY KEY,
                    media_id INTEGER NOT NULL)"""
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS queue_state(
                    _id INTEGER PRIMARY KEY CHECK(_id = 0),
                    current_index INTEGER NOT NULL,
                    position_ms INTEGER NOT NULL,
                    shuffle INTEGER NOT NULL,
                    repeat_mode INTEGER NOT NULL)"""
            )
        }
    }

    fun createPlaylist(name: String): Long {
        val cv = ContentValues().apply {
            put("name", name)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("playlists", null, cv)
    }

    fun renamePlaylist(id: Long, name: String) {
        val cv = ContentValues().apply { put("name", name) }
        writableDatabase.update("playlists", cv, "_id=?", arrayOf(id.toString()))
    }

    fun deletePlaylist(id: Long) {
        val db = writableDatabase
        db.delete("playlist_tracks", "playlist_id=?", arrayOf(id.toString()))
        db.delete("playlists", "_id=?", arrayOf(id.toString()))
        // Drop the origin too: a later re-import of the same file must create a fresh
        // playlist, not "update" an id that no longer exists.
        db.delete("playlist_origin", "playlist_id=?", arrayOf(id.toString()))
    }

    fun listPlaylists(): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        readableDatabase.query(
            "playlists", arrayOf("_id", "name"), null, null, null, null, "created_at ASC"
        ).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0) to c.getString(1))
        }
        return out
    }

    fun addTracks(playlistId: Long, trackIds: List<Long>) {
        if (trackIds.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val pos = nextPosition(playlistId)
            var p = pos
            val cv = ContentValues()
            for (tid in trackIds) {
                cv.clear()
                cv.put("playlist_id", playlistId)
                cv.put("track_id", tid)
                cv.put("position", p++)
                // Ignore duplicates.
                db.insertWithOnConflict(
                    "playlist_tracks", null, cv, SQLiteDatabase.CONFLICT_IGNORE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun nextPosition(playlistId: Long): Int {
        readableDatabase.rawQuery(
            "SELECT IFNULL(MAX(position),-1)+1 FROM playlist_tracks WHERE playlist_id=?",
            arrayOf(playlistId.toString())
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun removeTrack(playlistId: Long, trackId: Long) {
        writableDatabase.delete(
            "playlist_tracks", "playlist_id=? AND track_id=?",
            arrayOf(playlistId.toString(), trackId.toString())
        )
    }

    fun trackIdsIn(playlistId: Long): List<Long> {
        val out = ArrayList<Long>()
        readableDatabase.query(
            "playlist_tracks", arrayOf("track_id"), "playlist_id=?",
            arrayOf(playlistId.toString()), null, null, "position ASC"
        ).use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
        return out
    }

    fun countIn(playlistId: Long): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM playlist_tracks WHERE playlist_id=?", arrayOf(playlistId.toString())
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    // ------------------------------------------------------- import / export support

    /** Swap a playlist's contents wholesale — used when re-importing an updated .m3u. */
    fun replaceTracks(playlistId: Long, trackIds: List<Long>) {
        writableDatabase.delete("playlist_tracks", "playlist_id=?", arrayOf(playlistId.toString()))
        addTracks(playlistId, trackIds)
    }

    /**
     * Record where a playlist came from (currently a SAF document URI from an M3U
     * import), so the next import of the same file updates it instead of duplicating it.
     */
    fun setOrigin(playlistId: Long, source: String) {
        val cv = ContentValues().apply {
            put("playlist_id", playlistId)
            put("source", source)
        }
        writableDatabase.insertWithOnConflict(
            "playlist_origin", null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /** Playlist previously imported from [source], or null when there is none. */
    fun playlistIdByOrigin(source: String): Long? {
        readableDatabase.query(
            "playlist_origin", arrayOf("playlist_id"), "source=?",
            arrayOf(source), null, null, null
        ).use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return null
    }

    // ---------------------------------------------------------------- favourites

    /** Starred track ids, ascending MediaStore id.
     *
     *  `track_id INTEGER PRIMARY KEY` makes it the rowid, so `rowid ASC` is `track_id ASC`.
     *  There is no timestamp column, so this is not a star-time order: un-star and re-star
     *  leave a track exactly where it was.
     */
    fun favouriteIds(): List<Long> {
        val out = ArrayList<Long>()
        readableDatabase.query(
            "favourites", arrayOf("track_id"), null, null, null, null, "rowid ASC"
        ).use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
        return out
    }

    /** Add or drop a track from the favourites set. */
    fun setFavourite(trackId: Long, on: Boolean) {
        if (on) {
            val cv = ContentValues().apply { put("track_id", trackId) }
            // Ignore an existing row — favourites are a set, not a list.
            writableDatabase.insertWithOnConflict(
                "favourites", null, cv, SQLiteDatabase.CONFLICT_IGNORE
            )
        } else {
            writableDatabase.delete("favourites", "track_id=?", arrayOf(trackId.toString()))
        }
    }

    // ---------------------------------------------------------------- play statistics

    /**
     * One play of [trackId] at time [atMs]: bump the counter and stamp last_played,
     * creating the row on first play. Written as UPDATE-then-INSERT rather than an
     * ON CONFLICT upsert because upsert needs SQLite 3.24 (API 30) and minSdk is 26.
     * Callers own the threading — PlaybackService does this on its own worker.
     */
    fun recordPlay(trackId: Long, atMs: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        val update = db.compileStatement(
            "UPDATE play_stats SET plays = plays + 1, last_played = ? WHERE track_id = ?"
        )
        update.use {
            it.bindLong(1, atMs)
            it.bindLong(2, trackId)
            if (it.executeUpdateDelete() == 0) {
                val cv = ContentValues().apply {
                    put("track_id", trackId)
                    put("plays", 1)
                    put("last_played", atMs)
                }
                db.insertWithOnConflict(
                    "play_stats", null, cv, SQLiteDatabase.CONFLICT_IGNORE
                )
            }
        }
    }

    /** Play counts and last-played stamps, keyed by MediaStore track id. */
    fun playStats(): Map<Long, PlayStat> {
        val out = HashMap<Long, PlayStat>()
        readableDatabase.query(
            "play_stats", arrayOf("track_id", "plays", "last_played"),
            null, null, null, null, null
        ).use { c ->
            val idC = c.getColumnIndexOrThrow("track_id")
            val playsC = c.getColumnIndexOrThrow("plays")
            val lastC = c.getColumnIndexOrThrow("last_played")
            while (c.moveToNext()) {
                out[c.getLong(idC)] = PlayStat(c.getInt(playsC), c.getLong(lastC))
            }
        }
        return out
    }

    /** One track's play statistics. */
    data class PlayStat(val plays: Int, val lastPlayed: Long)

    // ---------------------------------------------------------------- queue (schema v4)

    /**
     * The queue as last handed to the player: ordered MediaStore ids plus where playback
     * had got to and the two playback modes. Written by PlaybackService only.
     *
     * The ordered ids and the scalar state get two tables rather than one key/value table:
     * the state is a typed singleton row (`_id` is pinned to 0) so a save is one
     * INSERT OR REPLACE with no parsing, and the order lives in `position` — a single
     * ordered read restores it.
     */
    data class QueueSnapshot(
        val mediaIds: List<Long>,
        val currentIndex: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: Int
    )

    /** Replace the saved queue wholesale — it is a snapshot, never edited in place. */
    fun saveQueue(snapshot: QueueSnapshot) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("queue", null, null)
            val insert = db.compileStatement("INSERT INTO queue(position, media_id) VALUES(?, ?)")
            insert.use { st ->
                snapshot.mediaIds.forEachIndexed { i, id ->
                    st.clearBindings()
                    st.bindLong(1, i.toLong())
                    st.bindLong(2, id)
                    st.executeInsert()
                }
            }
            val cv = ContentValues().apply {
                put("_id", 0)
                put("current_index", snapshot.currentIndex)
                put("position_ms", snapshot.positionMs)
                put("shuffle", if (snapshot.shuffle) 1 else 0)
                put("repeat_mode", snapshot.repeatMode)
            }
            db.insertWithOnConflict("queue_state", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** The saved queue, or null when nothing usable was stored. */
    fun savedQueue(): QueueSnapshot? {
        val db = readableDatabase
        var index = 0
        var positionMs = 0L
        var shuffle = false
        var repeatMode = 0
        db.query(
            "queue_state",
            arrayOf("current_index", "position_ms", "shuffle", "repeat_mode"),
            null, null, null, null, null
        ).use { c ->
            if (!c.moveToFirst()) return null
            index = c.getInt(0)
            positionMs = c.getLong(1)
            shuffle = c.getInt(2) != 0
            repeatMode = c.getInt(3)
        }
        val ids = ArrayList<Long>()
        db.query("queue", arrayOf("media_id"), null, null, null, null, "position ASC")
            .use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        if (ids.isEmpty()) return null
        return QueueSnapshot(ids, index.coerceIn(0, ids.size - 1), positionMs, shuffle, repeatMode)
    }

    /** Forget the saved queue (the player's queue was emptied). */
    fun clearQueue() {
        val db = writableDatabase
        db.delete("queue", null, null)
        db.delete("queue_state", null, null)
    }
}