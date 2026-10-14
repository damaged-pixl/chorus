package com.bee.chorus.ui

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import com.bee.chorus.R
import java.util.concurrent.Callable
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Minimal album-art loader.
 *
 * Memory discipline (this app must stay light):
 *  - Decodes through a *plain* asset-file open, then subsamples with
 *    ImageDecoder.setTargetSampleSize so the full-resolution image is never allocated.
 *  - Results live in a small size-based LruCache (default ~1/8 of heap).
 *  - A tiny tag on the ImageView cancels recycled-row reuse (no wrong art).
 *
 * Why not ContentResolver.loadThumbnail():
 *  loadThumbnail() is implemented as
 *      openTypedAssetFile(uri, "image/*", {EXTRA_SIZE})
 *  and MediaProvider treats that exact shape as "generate me a thumbnail": it routes
 *  to ensureThumbnail(), whose dispatch handles codes 2/101/117/201/701/801 but not
 *  code 120 — which is `*/audio/albumart/#`, the URI Album.artUri / Track.albumArtUri
 *  produce. Unmatched codes hit `throw new FileNotFoundException()`, loadThumbnail
 *  propagates it, and every row silently loses its art.
 *  Opening the same URI *without* the size hint is served by openFileCommon(), which
 *  does handle code 120 — verified on-device against MediaProviderGoogle.apk and by
 *  reading real JPEG bytes back through `adb shell content read`.
 */
object Art {
    private const val TAG = "ChorusArt"

    /**
     * One shared, bounded pool for the whole app: at most 2 concurrent decodes, a
     * small backlog, newest-first (so a fling drops the rows that scrolled away).
     */
    private val io = ThreadPoolExecutor(
        2, 2, 30L, TimeUnit.SECONDS,
        LinkedBlockingDeque<Runnable>(64),
        { r -> Thread(r, "chorus-art").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    private val cache: LruCache<String, Bitmap> by lazy {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
        object : LruCache<String, Bitmap>(maxKb) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }
    }

    /** Keys already known to have no art, so we stop re-hitting the provider for them. */
    private val noArt = HashSet<String>()

    /** Load [uri] into [iv] at roughly [targetPx]; cancels via the view's tag.
     *
     *  [lowMem] is for list thumbnails: it decodes as RGB_565 (half the bitmap RAM of
     *  ARGB_8888) on both decode paths, and it is part of the cache key so the
     *  full-quality player art never shares a low-mem entry. */
    fun load(ctx: Context, iv: ImageView, uri: Uri, targetPx: Int = 256, lowMem: Boolean = false) {
        val key = "$uri@$targetPx" + if (lowMem) "#low" else ""
        iv.setTag(R.id.art_tag, key)

        val hit = cache.get(key)
        if (hit != null) {
            iv.setImageBitmap(hit)
            return
        }

        // Neutral tile while decoding, and permanently if this album has no art.
        iv.setImageResource(R.drawable.art_placeholder)
        if (synchronized(noArt) { noArt.contains(key) }) return

        val appContext = ctx.applicationContext
        io.execute {
            val bmp = decode(appContext, uri, targetPx, lowMem)
            if (bmp == null) {
                synchronized(noArt) { noArt.add(key) }
            } else {
                cache.put(key, bmp)
            }
            iv.post {
                // Only apply if this view still wants this image (row may have recycled).
                if (iv.getTag(R.id.art_tag) != key) return@post
                if (bmp != null) iv.setImageBitmap(bmp) else iv.setImageResource(R.drawable.art_placeholder)
            }
        }
    }

    private fun decode(ctx: Context, uri: Uri, targetPx: Int, lowMem: Boolean): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // A plain open — deliberately NOT loadThumbnail(). With no EXTRA_SIZE
                // bundle MediaProvider serves this via openFileCommon(), which handles
                // albumart URIs; ImageDecoder then subsamples without ever allocating
                // the full-size bitmap. The Source owns (and closes) the descriptor.
                val source = ImageDecoder.createSource(Callable<AssetFileDescriptor> {
                    ctx.contentResolver.openTypedAssetFileDescriptor(uri, "image/*", null)
                })
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    // LOW_RAM lets the decoder use RGB_565 for opaque images — the
                    // memory budget for list thumbnails (full quality stays default).
                    if (lowMem) decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
                    var sample = 1
                    while (info.size.width / sample > targetPx || info.size.height / sample > targetPx) {
                        sample *= 2
                    }
                    if (sample > 1) decoder.setTargetSampleSize(sample)
                }
            } else {
                // Pre-28: bounded manual decode.
                val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opt)
                }
                var sample = 1
                while (opt.outWidth / sample > targetPx || opt.outHeight / sample > targetPx) {
                    sample *= 2
                }
                val opt2 = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    if (lowMem) inPreferredConfig = Bitmap.Config.RGB_565
                }
                ctx.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opt2)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "no art for $uri", e)
            null // missing art / revoked URI — caller falls back to the placeholder
        }
    }
}