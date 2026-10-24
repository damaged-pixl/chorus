package com.bee.chorus.data

import android.net.Uri
import android.provider.MediaStore

object Library {
    val TRACKS_URI: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val ALBUM_ART_URI: Uri = Uri.parse("content://media/external/audio/albumart")

    // Playlists are ours, stored in a private DB — the only writable state in the app.
    const val PLAYLIST_DB = "playlists.db"

    /**
     * Something outside MainActivity changed the library — today, a completed share
     * import ([com.bee.chorus.ShareActivity]). Set it and the next `onResume` re-runs
     * `loadLibrary()`, so a file shared while another app was in front appears in the
     * lists the moment the user comes back, without a manual rescan. Read-and-clear by
     * the one screen that owns the lists; plain (non-volatile) because both setter and
     * reader are on the main thread.
     */
    var dirty = false
}