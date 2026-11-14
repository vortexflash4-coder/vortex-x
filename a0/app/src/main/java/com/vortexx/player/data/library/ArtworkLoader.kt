package com.vortexx.player.data.library

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/** What to draw: embedded/album art for audio, a frame for video. */
sealed interface ArtworkRequest {
    val uri: String

    data class Audio(override val uri: String) : ArtworkRequest
    data class VideoFrame(override val uri: String) : ArtworkRequest
}

/**
 * Loads and memory-caches thumbnails for audio and video.
 * Android 10+: ContentResolver.loadThumbnail (MediaProvider's own cache).
 * Older releases: MediaMetadataRetriever (embedded picture / video frame).
 * Concurrency is capped so fast scrolling does not saturate I/O.
 */
@Singleton
class ArtworkLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(cacheSizeBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val misses: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val permits = Semaphore(4)

    fun cached(request: ArtworkRequest, sizePx: Int): Bitmap? = cache.get(key(request, bucket(sizePx)))

    suspend fun load(request: ArtworkRequest, sizePx: Int): Bitmap? {
        val size = bucket(sizePx)
        val key = key(request, size)
        cache.get(key)?.let { return it }
        if (key in misses) return null
        return permits.withPermit {
            withContext(Dispatchers.IO) {
                cache.get(key) ?: decode(request, size).also { bitmap ->
                    if (bitmap != null) cache.put(key, bitmap) else misses.add(key)
                }
            }
        }
    }

    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            cache.evictAll()
        } else {
            cache.trimToSize(cache.maxSize() / 2)
        }
    }

    fun clear() {
        cache.evictAll()
        misses.clear()
    }

    private fun decode(request: ArtworkRequest, size: Int): Bitmap? {
        val uri = Uri.parse(request.uri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val thumbnail = runCatching { context.contentResolver.loadThumbnail(uri, Size(size, size), null) }.getOrNull()
            if (thumbnail != null) return thumbnail
        }
        return when (request) {
            is ArtworkRequest.Audio -> embeddedPicture(uri, size)
            is ArtworkRequest.VideoFrame -> videoFrame(uri, size)
        }
    }

    private fun embeddedPicture(uri: Uri, size: Int): Bitmap? = withRetriever(uri) { retriever ->
        val bytes = retriever.embeddedPicture ?: return@withRetriever null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, size) }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun videoFrame(uri: Uri, size: Int): Bitmap? = withRetriever(uri) { retriever ->
        val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: return@withRetriever null
        val scale = size.toFloat() / maxOf(frame.width, frame.height).toFloat()
        if (scale >= 1f) {
            frame
        } else {
            Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true)
        }
    }

    private inline fun withRetriever(uri: Uri, block: (MediaMetadataRetriever) -> Bitmap?): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            block(retriever)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        if (width <= 0 || height <= 0) return sample
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2
        return sample
    }

    private fun key(request: ArtworkRequest, size: Int): String {
        val kind = when (request) {
            is ArtworkRequest.Audio -> "a"
            is ArtworkRequest.VideoFrame -> "v"
        }
        return "$kind|${request.uri}|$size"
    }

    /** Buckets requested sizes so the cache is not fragmented by tiny layout differences. */
    private fun bucket(sizePx: Int): Int = when {
        sizePx <= 160 -> 160
        sizePx <= 320 -> 320
        sizePx <= 640 -> 640
        else -> 1024
    }

    private companion object {
        fun cacheSizeBytes(): Int {
            val maxMemory = Runtime.getRuntime().maxMemory()
            return (maxMemory / 8).coerceAtMost(64L * 1024 * 1024).toInt()
        }
    }
}
