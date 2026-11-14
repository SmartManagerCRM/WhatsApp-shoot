package com.smartmanager.whatsappshoot.platform

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.smartmanager.whatsappshoot.logic.LatestImage
import com.smartmanager.whatsappshoot.logic.MediaCandidate
import com.smartmanager.whatsappshoot.logic.Product
import com.smartmanager.whatsappshoot.logic.ProductPhotos
import java.io.File
import java.util.concurrent.Executors

/**
 * Puts a product's registered picture into the phone's gallery as a brand-new photo, so WhatsApp's own gallery shows it as the newest picture and
 * the normal "first tile" flow sends it. The copy is created by this app (no storage permission needed, Android 10+), is only used if the phone
 * confirms it is the newest image by DATE_ADDED, DATE_MODIFIED and DATE_TAKEN, and is deleted again a couple of minutes after sending.
 */
class ProductPhotoPublisher(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val main = Handler(Looper.getMainLooper())
    private val cleaner = Executors.newSingleThreadExecutor()

    private fun collection() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** Returns the published picture, or null if it could not be created or is not provably the newest image. Call off the main thread. */
    fun publish(product: Product, picture: File): LatestImage? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        sweep(minAgeSec = 60)
        val now = System.currentTimeMillis()
        val name = "${ProductPhotos.PREFIX}${product.id}_$now.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, ProductPhotos.ALBUM_PATH)
            put(MediaStore.Images.Media.DATE_TAKEN, now)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection(), values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out -> picture.inputStream().use { it.copyTo(out, 64 * 1024) } } ?: throw IllegalStateException("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            val c = row(uri, name) ?: throw IllegalStateException("row missing")
            if (!isNewestByEveryDate(c)) throw IllegalStateException("not the newest image")
            main.postDelayed({ cleaner.execute { sweep(minAgeSec = 100) } }, CLEANUP_DELAY_MS)
            return LatestImage(c, key = "product-${product.id}-$now", skippedNewer = false)
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (x: Exception) { }
            return null
        }
    }

    private fun row(uri: android.net.Uri, name: String): MediaCandidate? {
        val cols = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.RELATIVE_PATH,
        )
        resolver.query(uri, cols, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            return MediaCandidate(
                id = c.getLong(0), uri = uri.toString(), displayName = name, mime = c.getString(1), sizeBytes = c.getLong(2), dateTakenMs = c.getLong(3),
                dateAddedSec = c.getLong(4), dateModifiedSec = c.getLong(5), folder = c.getString(6), pending = false, trashed = false,
            )
        }
        return null
    }

    /** True only if no other image is as new or newer than [c] by any date WhatsApp's gallery could sort by. */
    private fun isNewestByEveryDate(c: MediaCandidate): Boolean {
        for ((col, mine) in listOf(
            MediaStore.Images.Media.DATE_ADDED to c.dateAddedSec,
            MediaStore.Images.Media.DATE_MODIFIED to c.dateModifiedSec,
            MediaStore.Images.Media.DATE_TAKEN to c.dateTakenMs,
        )) {
            val args = android.os.Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.IS_PENDING} = 0")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(col))
                putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 2)
            }
            val top = ArrayList<Pair<Long, Long>>()
            resolver.query(collection(), arrayOf(MediaStore.Images.Media._ID, col), args, null)?.use { cur ->
                while (cur.moveToNext()) top += cur.getLong(0) to cur.getLong(1)
            }
            if (top.isEmpty() || top[0].first != c.id) return false
            if (top.size > 1 && top[1].second >= mine) return false // a tie: the gallery's order would be a guess
        }
        return true
    }

    /** [sweep] on the publisher's own thread, so a slow media-library call can never delay anything else (licence check, lookups). */
    fun sweepAsync(minAgeSec: Long) { cleaner.execute { sweep(minAgeSec) } }

    /** Deletes this app's temporary gallery copies older than [minAgeSec]. */
    fun sweep(minAgeSec: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val limit = System.currentTimeMillis() / 1000 - minAgeSec
            resolver.delete(collection(), "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Images.Media.DATE_ADDED} < ?", arrayOf("${ProductPhotos.PREFIX}%", limit.toString()))
        } catch (e: Exception) { }
    }

    companion object { const val CLEANUP_DELAY_MS = 150_000L }
}
