package com.bee.chorus.data

import android.content.ContentUris
import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Int,
    val trackNo: Int,
    val year: Int,
    // Absolute file path from MediaStore's DATA column. Playback still goes through the
    // content:// URI ([uri]); the path is kept only for the folder browser and M3U
    // import/export, which need to know (and match) where a file actually lives.
    val path: String
) : Parcelable {
    val uri: Uri get() = ContentUris.withAppendedId(Library.TRACKS_URI, id)
    val albumArtUri: Uri get() = ContentUris.withAppendedId(Library.ALBUM_ART_URI, albumId)

    /** Directory holding the file — what the folder browser groups by. */
    val parentPath: String get() = path.substringBeforeLast('/', "")
}

@Parcelize
data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val trackCount: Int
) : Parcelable {
    val artUri: Uri get() = ContentUris.withAppendedId(Library.ALBUM_ART_URI, id)
}