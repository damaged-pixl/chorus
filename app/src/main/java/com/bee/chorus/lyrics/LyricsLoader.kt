package com.bee.chorus.lyrics

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

/**
 * Finds lyrics for one track, off the main thread.
 *
 * Source order, first hit wins:
 *  1. an ID3v2 USLT/SYLT frame inside the audio file itself,
 *  2. a `.lrc` sidecar next to it (same basename),
 *  3. a `.txt` sidecar next to it.
 *
 * Sidecar caveat: the path comes from MediaStore's DATA column, and reading a *non-media*
 * file by path needs plain-file access, which scoped storage only grants below API 29.
 * On newer releases the read throws SecurityException and the loader quietly falls back
 * to the embedded tag. Opening a picker for it would cost the app its one-tap library
 * scan, so the limitation is accepted and documented rather than worked around.
 *
 * Results are cached per track (a handful of entries), and the callback always arrives on
 * the main thread. Negative results are not cached: a sidecar is cheap to re-check, and a
 * cached "no" would hide a file the user has just copied in.
 */
object LyricsLoader {

    private const val TAG = "ChorusLyrics"

    /** A lyrics sidecar this big is not lyrics. */
    private const val MAX_SIDECAR_BYTES = 512 * 1024

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "chorus-lyrics").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY + 1
        }
    }

    private val main = Handler(Looper.getMainLooper())

    /** Only a few tracks matter in practice: the one playing and the one before it. */
    private val cache = object : LruCache<String, Lyrics>(4) {
        override fun sizeOf(key: String, value: Lyrics) = 1
    }

    /**
     * Load lyrics for [trackUri], keyed by [key] (the caller's track identity). [onResult]
     * runs on the main thread with null when the track has none; a result that is no longer
     * wanted is the caller's to discard (it compares [key]).
     */
    fun load(ctx: Context, trackUri: Uri, key: String, onResult: (Lyrics?) -> Unit) {
        cache.get(key)?.let {
            onResult(it)
            return
        }
        val app = ctx.applicationContext
        io.execute {
            val lyrics = try {
                read(app, trackUri)
            } catch (e: Exception) {
                // A tag or sidecar may be truncated, unreadable or revoked mid-read; a
                // missing lyric is never worth a crash or a visible error.
                Log.w(TAG, "no lyrics for $trackUri", e)
                null
            }
            if (lyrics != null) cache.put(key, lyrics)
            main.post { onResult(lyrics) }
        }
    }

    private fun read(ctx: Context, trackUri: Uri): Lyrics? {
        // Embedded first: it travels with the file, so it is always the freshest copy.
        id3(ctx, trackUri)?.let { return it }
        val path = filePath(ctx, trackUri) ?: return null
        // Same basename, extension swapped: /Music/Song.mp3 -> /Music/Song.lrc, never
        // /Music/Song.mp3.lrc.
        val base = sidecarStem(path)
        for (ext in SIDECAR_EXTENSIONS) {
            val text = readText(File(base + ext)) ?: continue
            LyricsParser.parse(text)?.let { return it }
        }
        return null
    }

    private fun id3(ctx: Context, trackUri: Uri): Lyrics? {
        val pfd = ctx.contentResolver.openFileDescriptor(trackUri, "r") ?: return null
        // AutoCloseInputStream owns the descriptor; pfd.close() below is then a no-op
        // (ParcelFileDescriptor.close is idempotent), so the double close is deliberate.
        return pfd.use { ParcelFileDescriptor.AutoCloseInputStream(it).use(Id3Lyrics::read) }
    }

    /**
     * The audio file's path. DATA is deprecated but still served to callers holding a read
     * permission, which is the only way to find a sidecar by basename.
     */
    private fun filePath(ctx: Context, trackUri: Uri): String? =
        ctx.contentResolver.query(
            trackUri,
            arrayOf(MediaStore.Audio.Media.DATA),
            null,
            null,
            null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        }

    private fun readText(file: File): String? = try {
        if (file.isFile && file.length() in 1..MAX_SIDECAR_BYTES.toLong()) {
            file.readText()
        } else {
            null
        }
    } catch (e: SecurityException) {
        null // scoped storage: see the class comment
    } catch (e: Exception) {
        null
    }

    /** Tried in order; the uppercase variants catch cards formatted on a Windows PC. */
    private val SIDECAR_EXTENSIONS = listOf(".lrc", ".LRC", ".txt", ".TXT")
}
