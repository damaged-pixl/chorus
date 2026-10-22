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
            MediaStore.Audio.Media.DATE_ADDED,
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
        val addedC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
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
                    dateAdded = c.getLong(addedC),
                    path = c.getString(pathC).orEmpty()
                )
            )
        }
    }

    /**
     * Group tracks into albums.
     *
     * Two things the first cut got wrong: it labelled an album with whichever *track*
     * arrived first (so a compilation showed one of its artists), and it had no date for
     * "recently added".
     *
     *  - **Album artist.** MediaStore's albums table carries an album-level artist where
     *    MediaProvider could work one out — one small query, a row per album, not per
     *    track. Where it only says `<unknown>`, the first track's artist still stands in,
     *    which is the old behaviour and better than showing `<unknown>`.
     *  - **Date.** The album's date is the newest file in it: adding one track to an old
     *    album is what makes it turn up under "recently added".
     *
     * The *order* is left to the caller (MainActivity's sort bar); this returns the
     * artist ordering, which was the old default.
     */
    fun loadAlbums(tracks: List<Track>): List<Album> {
        val albumArtist = albumArtistById()
        val byId = LinkedHashMap<Long, Album>()
        for (t in tracks) {
            val existing = byId[t.albumId]
            if (existing == null) {
                byId[t.albumId] = Album(
                    id = t.albumId,
                    title = t.album,
                    artist = albumArtist[t.albumId]?.takeIf { usableAlbumArtist(it) } ?: t.artist,
                    year = t.year,
                    trackCount = 1,
                    dateAdded = t.dateAdded
                )
            }
        }
        // Recount properly (the map above only seeded entries), and carry the newest
        // file's date across the whole album.
        val counts = tracks.groupingBy { it.albumId }.eachCount()
        val newest = tracks.groupingBy { it.albumId }.reduce { _, a, b ->
            if (b.dateAdded > a.dateAdded) b else a
        }
        return byId.values.map {
            it.copy(
                trackCount = counts[it.id] ?: 0,
                dateAdded = newest[it.id]?.dateAdded ?: 0L
            )
        }.sortedWith(compareBy({ it.artist.lowercase() }, { it.year }))
    }

    /**
     * MediaStore's album-level artist per album id. One query over the albums table
     * (a row per album, so ~69 for a 1,500-file library), not a file read per track.
     * Rows MediaProvider could not work out come back blank and are ignored by the
     * caller.
     */
    /**
     * Whether MediaStore's album-level artist is worth showing. It says `<unknown>` when
     * MediaProvider could not work one out — and showing that above the real track artist
     * would be worse than the old behaviour, not better (it also sorts to the very top of
     * an alphabetical list, because "<" comes before every letter).
     */
    private fun usableAlbumArtist(artist: String): Boolean =
        artist.isNotBlank() && artist != "<unknown>" && artist != "Unknown artist"

    private fun albumArtistById(): Map<Long, String> {
        val out = HashMap<Long, String>(128)
        try {
            context.contentResolver.query(
                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Audio.Albums._ID,
                    MediaStore.Audio.Albums.ARTIST
                ), null, null, null
            )?.use { c ->
                val idC = c.getColumnIndexOrThrow(MediaStore.Audio.Albums._ID)
                val artistC = c.getColumnIndexOrThrow(MediaStore.Audio.Albums.ARTIST)
                while (c.moveToNext()) {
                    val artist = c.getString(artistC)
                    if (!artist.isNullOrBlank()) out[c.getLong(idC)] = artist
                }
            }
        } catch (_: Exception) {
            // Album-level artist is a refinement; never let it fail the album list.
        }
        return out
    }
}