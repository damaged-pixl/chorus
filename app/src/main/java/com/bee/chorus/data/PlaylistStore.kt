package com.bee.chorus.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local playlists. Plain SQLite (no Room) to keep the build and runtime light.
 * Tracks are referenced by their stable MediaStore id, so nothing is copied.
 */
class PlaylistStore(context: Context) :
    SQLiteOpenHelper(context, Library.PLAYLIST_DB, null, 1) {

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
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit

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
}