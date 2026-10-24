package com.bee.chorus

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import com.bee.chorus.data.Id3Tags
import com.bee.chorus.data.Library
import com.bee.chorus.data.LibraryReader
import com.bee.chorus.data.Settings
import com.bee.chorus.playback.PlayerHolder
import com.bee.chorus.ui.CycleMode
import com.bee.chorus.ui.Dialogs
import com.bee.chorus.ui.PlayerPanel
import com.bee.chorus.ui.chorusShow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Full-screen player. Hosts a single [PlayerPanel]; all playback state comes from the shared
 * [PlayerHolder], so entering or leaving this screen never changes what is playing.
 *
 * The screen is kept awake only while audio is actually playing — the flag is driven by the
 * player's isPlaying, so pausing from the notification also drops it.
 *
 * Theming: with cycle mode off this screen follows the same DayNight theme as the rest of
 * the app (it used to be pinned dark so the artwork backdrop would read; the artwork now
 * sits on its own dark card, so the page can be light). With cycle mode on it takes the
 * bright high-contrast theme and the 72dp touch targets, exactly as MainActivity does.
 */
class PlayerActivity : AppCompatActivity() {

    private var panel: PlayerPanel? = null
    private var cycle = false
    private var pendingTagEdit: PendingTagEdit? = null

    private data class PendingTagEdit(
        val mediaId: Long,
        val path: String,
        val uri: android.net.Uri,
        val title: String,
        val artist: String,
        val album: String,
        val year: String
    )

    private val writeConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pending = pendingTagEdit
        pendingTagEdit = null
        if (result.resultCode != RESULT_OK) {
            Toast.makeText(this, "Tag edit cancelled", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        if (pending == null) {
            // Rotation while the system consent sheet was up: this instance never saw
            // the edit. Fail loudly rather than silently doing nothing.
            Toast.makeText(this, "Tag edit lost — please try again", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        applyTagEdit(pending)
    }

    private val keepAwakeListener: (Player) -> Unit = { player ->
        if (player.isPlaying) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Cycle mode can only be flipped on the Setup tab (there is deliberately no appearance
     * control on this screen). If the player is open when that happens, [Settings] fires this
     * and a `recreate()` re-themes it — the theme is chosen before anything inflates, so the
     * running views cannot be re-themed in place.
     *
     * Held strongly by [Settings] for the life of the process: paired with
     * `removeListener` in `onDestroy` below.
     */
    private val settingsListener: (Boolean) -> Unit = { on ->
        if (on != cycle) recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Cycle mode swaps the Activity theme, so it has to be chosen before anything
        // inflates — before super.onCreate(), certainly before setContentView().
        cycle = Settings.isCycleMode(this)
        CycleMode.applyTheme(this, cycle)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        // Edge-to-edge is forced at targetSdk 36; inset so controls clear the
        // status and navigation bars.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // 72dp touch targets for gloved hands, posted because the panel's rows only exist
        // once the layout pass has run — same discipline as MainActivity.
        findViewById<View>(android.R.id.content).post {
            CycleMode.apply(findViewById(android.R.id.content), cycle)
        }

        // Back, only when this screen is the task's root: the media notification's tap on
        // a cold task starts the player with no library behind it, and the default
        // finish() there drops the user at the launcher — "back" should show the library.
        // Opened from the app (the usual case) the callback stays disabled, so the system
        // finishes the screen itself, predictive-back animation and all.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(isTaskRoot) {
                override fun handleOnBackPressed() {
                    startActivity(Intent(this@PlayerActivity, MainActivity::class.java))
                    finish()
                }
            },
        )

        // Added after the first read of the setting so restoring it on create is not
        // mistaken for the user changing it.
        Settings.addListener(settingsListener)

        panel = findViewById<PlayerPanel>(R.id.player_panel)
        // Long-pressing the title asks for that track's file details (see showTrackInfo).
        panel?.onTrackInfo = { mediaId -> showTrackInfo(mediaId) }
        panel?.show()
    }

    override fun onStart() {
        super.onStart()
        PlayerHolder.addPlayerListener(keepAwakeListener)
    }

    override fun onStop() {
        PlayerHolder.removePlayerListener(keepAwakeListener)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    // ------------------------------------------------------------------ track info

    /**
     * One thread for the info sheet's single-row MediaStore read, shut down with the screen
     * (it never does anything else, so it costs one idle thread for as long as the player
     * is open).
     */
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Run [block] on that thread, ignoring the call once this screen is gone — the same
     * guard as MainActivity's library thread. A `runOnUiThread` posted from a read can
     * land after onDestroy, and letting a shut-down pool reject it turns a finished screen
     * into a crash.
     */
    private fun runIo(block: () -> Unit) {
        if (io.isShutdown) return
        io.execute { block() }
    }

    /**
     * The info sheet: everything MediaStore knows about the file behind the current item,
     * on the one screen where you are already looking at it.
     *
     * Long-press the title to get here. Its single action — "Show in folder" — hands the
     * path back to [MainActivity], which owns the Folders tab: the player has no folder
     * tree of its own and should not grow one to host this.
     */
    private fun showTrackInfo(mediaId: String) {
        val id = mediaId.toLongOrNull() ?: return
        runIo {
            val facts = LibraryReader(this).trackFacts(id)
            runOnUiThread {
                if (facts == null) {
                    Toast.makeText(
                        this, "That file is no longer in the library", Toast.LENGTH_SHORT
                    ).show()
                    return@runOnUiThread
                }
                val rows = ArrayList<String>()
                if (facts.artist.isNotBlank()) rows += facts.artist
                if (facts.album.isNotBlank()) rows += facts.album
                if (facts.year > 0) rows += "Year ${facts.year}"
                // MediaStore packs the disc into the high digits — verified against a file's
                // own tag: track 5 of disc 1 reads 1005 here, and a disc-5 album's track 20
                // reads 5020. So over 999 is unpacked for display; a plain number is shown
                // as it is (the column has been a bare track number on other releases).
                when {
                    facts.trackNo >= 1000 ->
                        rows += "Track ${facts.trackNo % 1000} of disc ${facts.trackNo / 1000}"
                    facts.trackNo > 0 -> rows += "Track ${facts.trackNo}"
                }
                rows += "Length ${formatDuration(facts.durationMs)}"
                if (facts.bitrate > 0) rows += "${facts.bitrate / 1000} kbps"
                formatLine(facts.mime, facts.path)?.let { rows += "Format $it" }
                if (facts.sizeBytes > 0) rows += "Size ${formatSize(facts.sizeBytes)}"
                if (facts.dateAddedSec > 0) rows += "Added ${formatDate(facts.dateAddedSec)}"
                if (facts.dateModifiedSec > 0) {
                    rows += "Changed ${formatDate(facts.dateModifiedSec)}"
                }
                // The path is the one thing here that can be long, so it goes on a line
                // of its own where a wrapping TextView can show all of it.
                rows += ""
                rows += facts.path.ifEmpty { "Unknown location" }

                Dialogs.builder(this)
                    .setTitle(facts.title.ifEmpty { getString(R.string.app_name) })
                    .setMessage(rows.joinToString("\n"))
                    .setPositiveButton("Edit tags") { _, _ ->
                        openTagEditDialog(id, facts)
                    }
                    .setNeutralButton("Show in folder") { _, _ ->
                        // openPath takes a *directory* (the album row passes parentPath too,
                        // and a file path there lists an empty folder) — so the sheet's file
                        // path loses its last component before it travels.
                        val folder = facts.path.substringBeforeLast('/', "")
                        if (folder.isEmpty()) return@setNeutralButton
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_OPEN_FOLDER, folder)
                                // Land back on the existing main screen rather than stacking
                                // a second one behind the player — the queue pill's rule.
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                )
                        )
                    }
                    .setNegativeButton("Close", null)
                    .chorusShow()
            }
        }
    }

    private fun openTagEditDialog(mediaId: Long, facts: com.bee.chorus.data.TrackFacts) {
        val path = facts.path
        if (path.isBlank()) {
            Toast.makeText(this, "Tag editing is supported for MP3 files only", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Id3Tags.isMp3(path)) {
            Toast.makeText(this, "Tag editing is supported for MP3 files only", Toast.LENGTH_SHORT).show()
            return
        }
        val parsed = Id3Tags.readTags(path)
        val ctx = this
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val tTitle = EditText(ctx).apply { hint = "Title"; setText(parsed?.title?.ifEmpty { facts.title } ?: facts.title) }
        val tArtist = EditText(ctx).apply { hint = "Artist"; setText(parsed?.artist?.ifEmpty { facts.artist } ?: facts.artist) }
        val tAlbum = EditText(ctx).apply { hint = "Album"; setText(parsed?.album?.ifEmpty { facts.album } ?: facts.album) }
        val tYear = EditText(ctx).apply { hint = "Year"; setText(parsed?.year?.ifEmpty { if (facts.year > 0) facts.year.toString() else "" } ?: if (facts.year > 0) facts.year.toString() else "") }
        layout.addView(tTitle)
        layout.addView(tArtist)
        layout.addView(tAlbum)
        layout.addView(tYear)

        Dialogs.builder(this)
            .setTitle("Edit tags")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val uri = android.content.ContentUris.withAppendedId(Library.TRACKS_URI, mediaId)
                val pending = PendingTagEdit(
                    mediaId = mediaId,
                    path = path,
                    uri = uri,
                    title = tTitle.text.toString(),
                    artist = tArtist.text.toString(),
                    album = tAlbum.text.toString(),
                    year = tYear.text.toString()
                )
                pendingTagEdit = pending
                try {
                    val pi = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        MediaStore.createWriteRequest(contentResolver, listOf(uri))
                    } else {
                        // For older APIs, try direct write if we have permission/context
                        null
                    }
                    if (pi == null) {
                        pendingTagEdit = null
                        applyTagEdit(pending)
                    } else {
                        writeConsentLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    }
                } catch (e: Exception) {
                    pendingTagEdit = null
                    Toast.makeText(this, "Tag edit cancelled", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .chorusShow()
    }

    /**
     * One tag write, whichever door led here (consent result, or the pre-R direct path).
     * After a successful write the MediaStore row is *stale* — its TITLE/ARTIST/ALBUM were
     * extracted from the old bytes — so Library.dirty alone would re-read old text; the
     * per-file scanner is what pulls the new tags into MediaStore (and with it the lists,
     * the queue rows and the notification). scanFile is async and does not block the toast.
     */
    private fun applyTagEdit(pending: PendingTagEdit) {
        val refused = Id3Tags.writeTagsInPlace(
            pending.path,
            Id3Tags.Tags(pending.title, pending.artist, pending.album, pending.year)
        )
        if (refused.isEmpty()) {
            MediaScannerConnection.scanFile(
                this, arrayOf(pending.path), arrayOf("audio/mpeg"), null
            )
            Library.dirty = true
            Toast.makeText(this, "Tags updated", Toast.LENGTH_SHORT).show()
        } else {
            // All four refused at once means the file could not be opened for writing
            // (permission/parse) rather than one value overflowing its frame.
            Toast.makeText(
                this,
                if (refused.size == 4) "Couldn't update the tags"
                else "New value too long to write in place",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 3:45, 12:03, 1:02:03 — a track's length as a player says it. */
    private fun formatDuration(ms: Int): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** One unit that fits a row: 7.1 MB, 640 KB, 12345 bytes. */
    private fun formatSize(bytes: Long): String = when {
        // toDouble() on the division is load-bearing: Long / Long is an integer, and
        // "%.1f" with a Long argument throws IllegalFormatConversionException — which
        // took the process down on the main thread the first time this sheet was opened.
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes bytes"
    }

    /**
     * The file as a format: "MP3 · audio/mpeg" when both are known and add to each other,
     * either one alone when only one is, and nothing when there is nothing to say.
     */
    private fun formatLine(mime: String?, path: String): String? {
        val ext = path.substringAfterLast('.', "").uppercase(Locale.US)
            .takeIf { it.isNotEmpty() && it.length <= 5 }
        return when {
            ext != null && !mime.isNullOrBlank() -> "$ext \u00b7 $mime"
            ext != null -> ext
            !mime.isNullOrBlank() -> mime
            else -> null
        }
    }

    /** "14 Oct 2026" — British order, because the rest of the app says it that way. */
    private fun formatDate(epochSeconds: Long): String =
        SimpleDateFormat("d MMM yyyy", Locale.UK).format(Date(epochSeconds * 1000))

    override fun onDestroy() {
        // Settings holds its listeners strongly and process-wide: anything left behind here
        // leaks this Activity for good, including across the recreate() from the toggle.
        Settings.removeListener(settingsListener)
        panel?.hide()
        panel = null
        // The read thread has no work left once the sheet is gone; the guard in runIo
        // covers anything still in flight.
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        /** Open the full-screen player. */
        fun start(context: Context) {
            context.startActivity(Intent(context, PlayerActivity::class.java))
        }
    }
}