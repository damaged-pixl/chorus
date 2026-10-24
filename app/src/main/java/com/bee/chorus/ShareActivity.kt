package com.bee.chorus

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bee.chorus.data.Library
import com.bee.chorus.data.LibraryReader
import com.bee.chorus.data.Settings
import com.bee.chorus.data.Track
import com.bee.chorus.playback.PlayerHolder
import com.bee.chorus.ui.Dialogs
import com.bee.chorus.ui.chorusShow
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * "Share to Chorus": the share sheet's way in for audio files another app already holds —
 * a browser download, a messenger attachment, a file manager. Chorus never fetches
 * anything itself (no INTERNET permission, ever); this activity only ever copies a
 * [Uri] the *sending* app handed over into the library.
 *
 * Flow: copy each shared file into `Music/Chorus Shared/` (MediaStore insert on API 29+,
 * plain write + [MediaScannerConnection.scanFile] below that), resolve the fresh row to a
 * [Track], then ask what to do: **Play now** (inserted after the current item, so the
 * queue being listened to survives), **Add to queue** (append), or **Close** (the file is
 * simply kept). The window itself is transparent — only the dialog is on screen, floating
 * over whatever app shared the file.
 *
 * The copy runs on a background thread against the application context: the user backing
 * out mid-copy must not abort the import they just asked for. The dialog only shows while
 * this activity is alive; the file reaching the library never depends on it ([Library.dirty]
 * makes MainActivity re-read on its next resume either way).
 */
class ShareActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()

    /** Set across the pre-29 permission round-trip; consumed once. */
    private var pendingUris: List<Uri>? = null

    /** Instance state marker: a recreation mid-share means the first instance already
     *  claimed this intent — never import the same share twice. */
    private var claimed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Deliberately *not* CycleMode.applyTheme: that would set the opaque main theme
        // over the manifest's translucent one, and this window exists only to carry a
        // dialog floating over the app that shared. The translucent twins keep every
        // Chorus token (Dialogs reads the accent from this activity's theme for the
        // dialog's ink), cycle mode included.
        setTheme(
            if (Settings.isCycleMode(this)) {
                R.style.Theme_Chorus_Cycle_Translucent
            } else {
                R.style.Theme_Chorus_Translucent
            }
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null || claimed) {
            finish()
            return
        }
        claimed = true

        val uris = collectUris()
        if (uris.isEmpty()) {
            finish()
            return
        }

        // Below 29 a write into shared Music needs WRITE_EXTERNAL_STORAGE, which the
        // manifest caps at 28 — modern devices never see this. Ask once, here, or bail:
        // a share we cannot store is a share we must not pretend to accept.
        if (Build.VERSION.SDK_INT < 29 && !hasWritePermission()) {
            pendingUris = uris
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_WRITE)
            return
        }
        startImport(uris)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_WRITE) return
        val uris = pendingUris
        pendingUris = null
        if (grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            if (uris != null) startImport(uris) else finish()
        } else {
            Toast.makeText(
                this,
                "Storage permission is needed to save shared files",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    // ---------------------------------------------------------------- intake

    /** The stream the sending app handed over: SEND, SEND_MULTIPLE, or a bare `data`. */
    private fun collectUris(): List<Uri> {
        val list = ArrayList<Uri>()
        @Suppress("DEPRECATION") // the typed getters need an SDK check for no real gain
        val streams = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        if (!streams.isNullOrEmpty()) {
            list.addAll(streams)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { list.add(it) }
        }
        if (list.isEmpty()) intent.data?.let { list.add(it) }
        // Readable now or never: the sender's grant lives with this activity, so the
        // copy must start before anything can revoke it — probe each URI right here.
        return list.filter { uri ->
            try {
                contentResolver.openInputStream(uri)?.close()
                true
            } catch (_: Exception) {
                false
            }
        }.distinct()
    }

    private fun hasWritePermission(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.WRITE_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- import

    private fun startImport(uris: List<Uri>) {
        Toast.makeText(this, "Adding to Chorus…", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        io.execute {
            val ok = ArrayList<Track>(uris.size)
            var failed = 0
            for (uri in uris) {
                try {
                    importOne(app, uri)?.let { ok.add(it) } ?: failed++
                } catch (_: Exception) {
                    failed++
                }
            }
            runOnUiThread { onImported(ok, failed) }
        }
    }

    /**
     * One file, start to finish. Returns the fresh library row as a [Track], or null when
     * this particular file could not be stored (unreadable, not audio, insert refused).
     */
    private fun importOne(app: Context, src: Uri): Track? {
        val name = sanitiseName(queryName(app, src))
        val givenExt = name.substringAfterLast('.', "").lowercase()
        // The intent filter promises audio/*, but senders lie (download managers hand
        // out application/octet-stream) and the bare `data` fallback promises nothing —
        // so *both* signals get a vote, and a PDF named .pdf loses both. Only once the
        // file has declared itself audio (or named itself audio) do we trust it in.
        val declared = app.contentResolver.getType(src)?.lowercase()
        val declaredAudio = declared != null &&
            (declared.startsWith("audio/") || MIME_TO_EXT.containsKey(declared))
        if (!declaredAudio && givenExt !in AUDIO_EXTENSIONS) return null
        // The insert's mime must be an audio/* the audio collection accepts: an unknown
        // container's declared type (octet-stream) is replaced by the name's audio type.
        val mime = if (declaredAudio && MIME_TO_EXT.containsKey(declared)) declared
        else mimeFor(name)
        val ext = extensionFor(mime) ?: return null
        // Normalise the name onto the mime's canonical extension: a scanner re-deriving
        // type from a stray ".mp4"/".xyz" could re-classify the row out of the music
        // collection after we classified it — the container plays the same whatever the
        // three letters after the dot say (audio/mp4 in a file named .m4a is the M4A
        // case this exists for).
        val finalName = "${name.substringBeforeLast('.', name)}.$ext"

        val meta = readMetadata(app, src, finalName)
        return if (Build.VERSION.SDK_INT >= 29) {
            importModern(app, src, finalName, mime, meta)
        } else {
            importLegacy(app, src, finalName, mime, meta)
        }
    }

    /**
     * API 29+: one MediaStore insert carries name, mime and classification, so the row
     * exists exactly when the bytes do. `is_music=1` is bound here because MediaProvider's
     * own classification pass only runs at idle maintenance — a shared file invisible to
     * the app that just saved it until the phone next charges is not "saved" as far as
     * the user is concerned (this exact trap cost a whole session with adb-pushed music).
     */
    private fun importModern(
        app: Context,
        src: Uri,
        name: String,
        mime: String,
        meta: Metadata
    ): Track? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.Audio.AudioColumns.IS_MUSIC, 1)
            put(MediaStore.Audio.AudioColumns.TITLE, meta.title)
            if (meta.artist != null) put(MediaStore.Audio.AudioColumns.ARTIST, meta.artist)
            if (meta.album != null) put(MediaStore.Audio.AudioColumns.ALBUM, meta.album)
            if (meta.durationMs > 0) put(MediaStore.Audio.AudioColumns.DURATION, meta.durationMs)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_MUSIC + "/" + TARGET_DIR + "/"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val dest = app.contentResolver.insert(Library.TRACKS_URI, values) ?: return null
        return try {
            copy(app, src, dest)
            app.contentResolver.update(
                dest,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null, null
            )
            // Belt for the album link: the insert bound the album *name*; MediaProvider
            // derives ALBUM_ID (and art) during its per-file scan, which also refreshes
            // our fields from the real tags. Fire-and-forget — the row above already
            // plays, and Library.dirty makes MainActivity re-read on its next resume.
            pathOf(app, dest)?.let {
                MediaScannerConnection.scanFile(app, arrayOf(it), arrayOf(mime), null)
            }
            trackFor(app, dest)
        } catch (_: Exception) {
            // A half-written row would sit in the library as a broken track; the write
            // that failed is exactly the file we must not advertise.
            runCatching { app.contentResolver.delete(dest, null, null) }
            null
        }
    }

    /**
     * Below 29: no pending/relative-path columns, so write the file where it belongs and
     * let [MediaScannerConnection.scanFile] create and classify the row — the pre-Q
     * contract in one call. The runtime WRITE grant is checked before we get here.
     */
    private fun importLegacy(
        app: Context,
        src: Uri,
        name: String,
        mime: String,
        meta: Metadata
    ): Track? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            TARGET_DIR
        )
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val out = File(dir, name)
        try {
            app.contentResolver.openInputStream(src)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
        } catch (_: Exception) {
            out.delete()
            return null
        }
        // The row does not exist until the scan runs and the query below needs it, so
        // this is the one place waiting for the callback is not optional.
        val latch = CountDownLatch(1)
        MediaScannerConnection.scanFile(app, arrayOf(out.absolutePath), arrayOf(mime)) { _, _ ->
            latch.countDown()
        }
        runCatching { latch.await(5, TimeUnit.SECONDS) }
        return trackAt(app, out.absolutePath)
    }

    /** Shared byte copy through the insert's own descriptor; "wt" truncates any rename. */
    private fun copy(app: Context, src: Uri, dest: Uri) {
        app.contentResolver.openInputStream(src)?.use { input ->
            app.contentResolver.openOutputStream(dest, "wt")?.use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("unreadable share source")
    }

    // ---------------------------------------------------------------- outcome

    private fun onImported(tracks: List<Track>, failed: Int) {
        // The file is in the library whether or not this window survives: flag the main
        // screen to re-read on its next resume (it may be paused behind the sharing app).
        Library.dirty = true
        if (failed > 0) {
            Toast.makeText(
                this,
                if (tracks.isEmpty()) "Couldn't import that file"
                else "$failed of the shared files couldn't be imported",
                Toast.LENGTH_LONG
            ).show()
        }
        if (tracks.isEmpty() || isFinishing || isDestroyed) {
            finish()
            return
        }
        Dialogs.builder(this)
            .setTitle(
                if (tracks.size == 1) "Added “${tracks[0].title}”"
                else "${tracks.size} tracks added"
            )
            .setMessage("Saved to Music/$TARGET_DIR")
            .setPositiveButton("Play now") { _, _ ->
                // Cold process: bind first — whenReady queues the action behind the
                // controller's own async connection, so play lands when it is ready.
                // Only the *play* paths bind: a plain "Close" must not spin up the
                // playback service (and its restored-queue notification) on its own.
                PlayerHolder.bind(applicationContext)
                PlayerHolder.playNow(tracks)
                finish()
            }
            .setNeutralButton("Add to queue") { _, _ ->
                PlayerHolder.bind(applicationContext)
                PlayerHolder.enqueue(tracks)
                finish()
            }
            .setNegativeButton("Close") { _, _ -> finish() }
            .setOnDismissListener { if (!isFinishing) finish() }
            .chorusShow()
    }

    override fun onDestroy() {
        // shutdown() lets an already-running import finish (it holds the app context)
        // while refusing anything new — the activity is gone, nothing new can arrive.
        io.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- helpers

    private fun queryName(app: Context, uri: Uri): String {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i)?.let { if (it.isNotBlank()) return it }
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: DEFAULT_NAME
    }

    /** A display name is not a path: strips separators and the empties that hide them. */
    private fun sanitiseName(raw: String): String =
        raw.replace('/', '_').replace('\\', '_').trim().ifEmpty { DEFAULT_NAME }

    private data class Metadata(
        val title: String,
        val artist: String?,
        val album: String?,
        val durationMs: Long
    )

    /**
     * Title/artist/album/duration straight from the file's own tags, *before* the insert,
     * so the row lands complete instead of waiting on any scanner — and the dialog shows
     * the real title rather than a file name. Any failure degrades to the name without a
     * dot: tags are a nicety, the bytes are the contract.
     */
    private fun readMetadata(app: Context, uri: Uri, fallbackTitle: String): Metadata {
        var title = fallbackTitle.substringBeforeLast('.', fallbackTitle)
        var artist: String? = null
        var album: String? = null
        var duration = 0L
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(app, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }?.let { title = it }
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    ?.takeIf { it.isNotBlank() }
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() }
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            // Untaggable file (or a container the retriever skips): the name-based title
            // above stands, and MediaProvider's own scan may still fill the rest.
        } finally {
            runCatching { retriever.release() }
        }
        return Metadata(title, artist, album, duration)
    }

    private fun trackFor(app: Context, uri: Uri): Track? = LibraryReader(app).trackFor(uri)

    private fun trackAt(app: Context, path: String): Track? = LibraryReader(app).trackAt(path)

    private fun pathOf(app: Context, uri: Uri): String? {
        app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (i >= 0) return c.getString(i)
                }
            }
        return null
    }

    // ---------------------------------------------------------------- classification

    private fun extensionFor(mime: String): String? = MIME_TO_EXT[mime.lowercase()]

    private fun mimeFor(name: String): String =
        EXT_TO_MIME[name.substringAfterLast('.', "").lowercase()] ?: "audio/mpeg"

    companion object {
        private const val REQ_WRITE = 1
        const val TARGET_DIR = "Chorus Shared"
        private const val DEFAULT_NAME = "Shared audio"

        // Deliberately small and hand-kept: only containers Media3 (and the retriever
        // before it) can actually play are allowed through the share door. mp4/3gp are
        // absent on purpose — those extensions say *video*, and Chorus is audio only:
        // an audio-track mp4 still arrives when its declared mime is audio/mp4.
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "mka", "amr"
        )
        private val MIME_TO_EXT = mapOf(
            "audio/mpeg" to "mp3",
            "audio/mp3" to "mp3",
            "audio/flac" to "flac",
            "audio/x-flac" to "flac",
            "audio/mp4" to "m4a",
            "audio/aac" to "aac",
            "audio/ogg" to "ogg",
            "audio/opus" to "opus",
            "audio/vorbis" to "ogg",
            "audio/wav" to "wav",
            "audio/x-wav" to "wav",
            "audio/x-matroska" to "mka",
            "audio/amr" to "amr",
            "audio/3gpp" to "3gp"
        )
        private val EXT_TO_MIME = MIME_TO_EXT.entries.associate { (m, e) -> e to m }
    }
}
