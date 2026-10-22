package com.bee.chorus.ui

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
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

    /** Callbacks for [bitmap] land here, so callers get their result on the main thread. */
    private val main = Handler(Looper.getMainLooper())

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

    /**
     * Bounded decode for callers that are not an [ImageView] — the home-screen widget's
     * RemoteViews. Same pool, same subsampled decode and same cache as [load]; a cache hit
     * answers before this returns, otherwise [onResult] arrives on the main thread. The
     * widget never gets a full-size bitmap, and a missing album art costs one negative
     * lookup for the rest of the process.
     */
    fun bitmap(ctx: Context, uri: Uri, targetPx: Int, onResult: (Bitmap?) -> Unit) {
        val key = "$uri@$targetPx"
        cache.get(key)?.let {
            onResult(it)
            return
        }
        if (synchronized(noArt) { noArt.contains(key) }) {
            onResult(null)
            return
        }
        val appContext = ctx.applicationContext
        io.execute {
            val bmp = decode(appContext, uri, targetPx, false)
            if (bmp == null) {
                synchronized(noArt) { noArt.add(key) }
            } else {
                cache.put(key, bmp)
            }
            main.post { onResult(bmp) }
        }
    }

    /**
     * A square cover built from up to four album-art URIs — the playlist's own look, so a
     * playlist reads as a mix rather than as whichever album its first track happens to
     * belong to.
     *
     * Blocking, and deliberately so: it runs on the library-load worker while a playlist
     * row's cover is being rebuilt, where there is nothing to wait for but the decodes.
     * Nothing on a bind path may call it — rows still get their art through [load].
     *
     * Four tiles lay out 2x2, three leave the fourth quadrant to the first tile again,
     * two split the square, one fills it. Each tile is centre-cropped into its quadrant,
     * so a tall cover is not squashed into a square. The tiles are thrown away afterwards:
     * they never touch the cache, so nothing else can be holding them.
     */
    fun mosaicBlocking(ctx: Context, uris: List<Uri>, tilePx: Int, outPx: Int): Bitmap? {
        val appContext = ctx.applicationContext
        val tiles = uris.take(4).mapNotNull { decode(appContext, it, tilePx, true, software = true) }
        if (tiles.isEmpty()) return null
        return try {
            val out = Bitmap.createBitmap(outPx, outPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            // One pixel of overlap between quadrants: two tiles meeting on an exact
            // boundary leave a hairline seam if the scale lands either side of it.
            val half = outPx / 2f + 1f
            // One tile fills the square, not a corner of it — that case is a playlist
            // whose tracks all share an album, and it is the common one.
            val quad = if (tiles.size == 1) outPx.toFloat() else half
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val origins = when (tiles.size) {
                1 -> listOf(0f to 0f)
                2 -> listOf(0f to 0f, half to 0f)
                3 -> listOf(0f to 0f, half to 0f, 0f to half, half to half)
                else -> listOf(0f to 0f, half to 0f, 0f to half, half to half)
            }
            origins.forEachIndexed { i, (left, top) ->
                val tile = tiles[i % tiles.size]
                val dst = RectF(left, top, minOf(left + quad, outPx.toFloat()), minOf(top + quad, outPx.toFloat()))
                val scale = maxOf(dst.width() / tile.width, dst.height() / tile.height)
                val w = tile.width * scale
                val h = tile.height * scale
                val dx = left + (dst.width() - w) / 2f
                val dy = top + (dst.height() - h) / 2f
                canvas.drawBitmap(tile, null, RectF(dx, dy, dx + w, dy + h), paint)
            }
            out
        } finally {
            // Tiles are private to this call (never cached), so dropping them here is the
            // only thing that keeps a rebuild from leaking four bitmaps per playlist.
            tiles.forEach { it.recycle() }
        }
    }

    /**
     * [software] forces a software-configured bitmap out of ImageDecoder. Only the mosaic
     * needs that: a hardware bitmap cannot be drawn onto a software Canvas, and compositing
     * tiles into one square is exactly that. List and widget paths leave it off — they hand
     * the bitmap straight to a view, which is where hardware bitmaps belong.
     */
    private fun decode(
        ctx: Context, uri: Uri, targetPx: Int, lowMem: Boolean, software: Boolean = false
    ): Bitmap? {
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
                    if (software) decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
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