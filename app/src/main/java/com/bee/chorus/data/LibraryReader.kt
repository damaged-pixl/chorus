package com.bee.chorus.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.MediaStore

/**
 * Reads the on-device audio library from MediaStore.
 * All work happens once, off the main thread, and the result is cached in memory —
 * the app never touches the network and never re-scans while you browse.
 */
class LibraryReader(private val context: Context) {

    // DATA is deprecated but still populated; the suppression is only for reading it
    // (see the projection comment — folder browse and M3U need real file paths).
    @Suppress("DEPRECATION")
    fun loadTracks(): List<Track> {
        val out = ArrayList<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            // DATA (file path). Deprecated since API 29 but still populated by
            // MediaProvider for every row, and the folder browser plus M3U
            // import/export need the real location (matching entries by path, writing
            // them out again). Playback deliberately still uses the content:// URI.
            MediaStore.Audio.Media.DATA
        )
        // Only real music files, skip podcasts/short clips/ringtones.
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val order = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        context.contentResolver.query(
            Library.TRACKS_URI, projection, selection, null, order
        )?.use { c -> readTracks(c, out) }
        return out
    }

    /** Reads DATA alongside the live columns — see the [loadTracks] suppression note. */
    @Suppress("DEPRECATION")
    private fun readTracks(c: Cursor, out: MutableList<Track>) {
        val idC = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumIdC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val durC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val trackC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
        val yearC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
        val pathC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

        val UNKNOWN_ARTIST = "<unknown>"

        while (c.moveToNext()) {
            val duration = c.getInt(durC)
            // Skip anything under 30s — not worth a row in a music library.
            if (duration < 30_000) continue

            var title = c.getString(titleC)
            if (title.isNullOrBlank()) title = c.getString(albumC).orEmpty()
            if (title.isBlank()) continue

            var artist = c.getString(artistC)
            if (artist.isNullOrBlank() || artist == UNKNOWN_ARTIST) artist = "Unknown artist"

            out.add(
                Track(
                    id = c.getLong(idC),
                    title = title,
                    artist = artist,
                    album = c.getString(albumC).orEmpty(),
                    albumId = c.getLong(albumIdC),
                    durationMs = duration,
                    // TRACK encodes disc in the thousands (1002 = disc 1, track 2).
                    trackNo = (c.getInt(trackC) % 1000).coerceAtLeast(0),
                    year = c.getInt(yearC),
                    path = c.getString(pathC).orEmpty()
                )
            )
        }
    }

    /** Group tracks into albums, preserving first-seen artist. */
    fun loadAlbums(tracks: List<Track>): List<Album> {
        val byId = LinkedHashMap<Long, Album>()
        for (t in tracks) {
            val existing = byId[t.albumId]
            if (existing == null) {
                byId[t.albumId] = Album(
                    id = t.albumId,
                    title = t.album,
                    artist = t.artist,
                    year = t.year,
                    trackCount = 1
                )
            } else if (existing.trackCount == 0) {
                // placeholder replaced below
            }
        }
        // Recount properly (the map above only seeded entries).
        val counts = tracks.groupingBy { it.albumId }.eachCount()
        return byId.values.map { it.copy(trackCount = counts[it.id] ?: 0) }
            .sortedWith(compareBy({ it.artist.lowercase() }, { it.year }))
    }
}