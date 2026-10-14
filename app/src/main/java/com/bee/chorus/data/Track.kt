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
    val year: Int
) : Parcelable {
    val uri: Uri get() = ContentUris.withAppendedId(Library.TRACKS_URI, id)
    val albumArtUri: Uri get() = ContentUris.withAppendedId(Library.ALBUM_ART_URI, albumId)
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