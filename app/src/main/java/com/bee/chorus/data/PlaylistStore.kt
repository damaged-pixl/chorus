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
 * `play_stats` from v3, and `playlist_origin` from v5 — where an imported playlist
 * came from, so re-importing an updated .m3u updates it instead of duplicating it).
 * Note that several instances of this helper may exist at
 * once (MainActivity owns one, the [Favourites] facade and PlaybackService each open
 * their own with the application context) — Android's SQLiteDatabase pools connections
 * per file path, so that is safe.
 */
class PlaylistStore(context: Context) :
    SQLiteOpenHelper(context, Library.PLAYLIST_DB, null, 5) {

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
    }

    /**
     * Real migrations, one per schema bump:
     *  v1 -> v2 added the `favourites` table.
     *  v2 -> v3 added the `play_stats` table.
     *  v3 -> v4 adds the `queue` table (Phase D — lands on merge; empty here so this
     *       branch's migrations stay self-contained).
     *  v4 -> v5 added the `playlist_origin` table (Phase E).
     * An oldVersion of 1 falls through every branch, so every historical version
     * reaches the current schema.
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
            // Reserved for Phase D's `queue` table, which is being added on a parallel
            // branch — deliberately empty here. Merge the real branch to fill it in.
        }
        if (old < 5) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS playlist_origin(
                    playlist_id INTEGER PRIMARY KEY,
                    source TEXT NOT NULL)"""
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
}