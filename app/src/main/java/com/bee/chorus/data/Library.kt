package com.bee.chorus.data

import android.net.Uri
import android.provider.MediaStore

object Library {
    val TRACKS_URI: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val ALBUM_ART_URI: Uri = Uri.parse("content://media/external/audio/albumart")

    // Playlists are ours, stored in a private DB — the only writable state in the app.
    const val PLAYLIST_DB = "playlists.db"
}