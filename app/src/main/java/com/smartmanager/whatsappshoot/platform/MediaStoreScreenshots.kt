package com.smartmanager.whatsappshoot.platform

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.smartmanager.whatsappshoot.logic.AutomationSettings
import com.smartmanager.whatsappshoot.logic.CaptureResult
import com.smartmanager.whatsappshoot.logic.Clock
import com.smartmanager.whatsappshoot.logic.FailureReason
import com.smartmanager.whatsappshoot.logic.GalleryRoute
import com.smartmanager.whatsappshoot.logic.ImageFormat
import com.smartmanager.whatsappshoot.logic.ImageFormats
import com.smartmanager.whatsappshoot.logic.LatestImage
import com.smartmanager.whatsappshoot.logic.ScreenshotFolderMatcher
import com.smartmanager.whatsappshoot.logic.LatestScreenshotProvider
import com.smartmanager.whatsappshoot.logic.MediaAccess
import com.smartmanager.whatsappshoot.logic.MediaCandidate
import com.smartmanager.whatsappshoot.logic.ScreenshotRepository
import com.smartmanager.whatsappshoot.logic.ScreenshotSelector
import com.smartmanager.whatsappshoot.logic.ScreenshotSource
import com.smartmanager.whatsappshoot.logic.SelectionResult
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * Reads existing screenshots from MediaStore. Only image rows whose folder is one of the configured Screenshots folders are
 * requested, at most [MAX] metadata rows are read, and only the one chosen file's bytes are opened. Nothing is uploaded.
 */
class MediaStoreScreenshotSource(private val context: Context) : ScreenshotSource {
    private val resolver: ContentResolver get() = context.contentResolver

    override fun access(): MediaAccess {
        fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> when {
                granted(Manifest.permission.READ_MEDIA_IMAGES) -> MediaAccess.FULL
                Build.VERSION.SDK_INT >= 34 && granted("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") -> MediaAccess.PARTIAL
                else -> MediaAccess.NONE
            }
            else -> if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) MediaAccess.FULL else MediaAccess.NONE
        }
    }

    private fun collection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    @Suppress("DEPRECATION")
    override fun candidates(folders: List<String>, limit: Int): List<MediaCandidate> {
        val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val cols = mutableListOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATE_MODIFIED,
        )
        if (q) { cols += MediaStore.Images.Media.RELATIVE_PATH; cols += MediaStore.Images.Media.IS_PENDING } else cols += MediaStore.Images.Media.DATA
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) cols += MediaStore.Images.Media.IS_TRASHED

        // Coarse SQL filter; the selector re-checks the exact folder segment on every row.
        val pathCol = if (q) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        val leaves = ScreenshotFolderMatcher.leafNames(folders)
        if (leaves.isEmpty()) return emptyList()
        val like = leaves.joinToString(" OR ") { "$pathCol LIKE ?" }
        val args = leaves.map { if (q) "%$it/" else "%/$it/%" }
        var selection = "($like)"
        if (q) selection += " AND ${MediaStore.Images.Media.IS_PENDING} = 0"
        val out = ArrayList<MediaCandidate>()
        val bundle = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        resolver.query(collection(), cols.toTypedArray(), bundle, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                fun str(n: String) = c.getColumnIndex(n).let { if (it >= 0 && !c.isNull(it)) c.getString(it) else null }
                fun lng(n: String) = c.getColumnIndex(n).let { if (it >= 0 && !c.isNull(it)) c.getLong(it) else 0L }
                val folder = if (q) str(MediaStore.Images.Media.RELATIVE_PATH) else str(MediaStore.Images.Media.DATA)?.let { File(it).parent }
                out += MediaCandidate(
                    id = id, uri = ContentUris.withAppendedId(collection(), id).toString(),
                    displayName = str(MediaStore.Images.Media.DISPLAY_NAME), mime = str(MediaStore.Images.Media.MIME_TYPE),
                    sizeBytes = lng(MediaStore.Images.Media.SIZE), dateTakenMs = lng(MediaStore.Images.Media.DATE_TAKEN),
                    dateAddedSec = lng(MediaStore.Images.Media.DATE_ADDED), dateModifiedSec = lng(MediaStore.Images.Media.DATE_MODIFIED),
                    folder = folder,
                    pending = q && lng(MediaStore.Images.Media.IS_PENDING) != 0L,
                    trashed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && lng(MediaStore.Images.Media.IS_TRASHED) != 0L,
                )
            }
        }
        return out
    }

    override fun open(candidate: MediaCandidate): InputStream? = try {
        resolver.openInputStream(Uri.parse(candidate.uri))
    } catch (e: Exception) {
        null // deleted, revoked or otherwise inaccessible
    }

    override fun isDecodable(candidate: MediaCandidate): Boolean = try {
        resolver.openInputStream(Uri.parse(candidate.uri))?.use { s ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(s, null, o)
            o.outWidth > 0 && o.outHeight > 0
        } ?: false
    } catch (e: Exception) {
        false
    }

    companion object {
        const val MAX = ScreenshotSelector.MAX_CANDIDATES
    }
}

/** Runs the (disk/content-provider) work on a background thread and answers on the main thread. */
class MediaStoreLatestProvider(
    private val context: Context,
    private val repository: ScreenshotRepository,
    clock: Clock,
) : LatestScreenshotProvider {
    private val source = MediaStoreScreenshotSource(context)
    private val selector = ScreenshotSelector(source, clock)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    val access: MediaAccess get() = source.access()

    override fun find(settings: AutomationSettings, callback: (SelectionResult) -> Unit) {
        worker.execute {
            val r = try { selector.selectLatest(settings.screenshotFolders) } catch (e: Exception) { SelectionResult.Failed }
            main.post { callback(r) }
        }
    }

    /** Reading the newest video's DATE only: needs the video permission (or the legacy storage permission below Android 13). */
    private fun videoPermitted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun newestDates(uri: Uri, idCol: String, dateCol: String, pendingCol: String?, trashedCol: String?, extraSelection: String?, extraArgs: Array<String>?, count: Int): List<Pair<Long, Long>> {
        val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val parts = ArrayList<String>()
        extraSelection?.let { parts += it }
        if (q && pendingCol != null) parts += "$pendingCol = 0"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && trashedCol != null) parts += "$trashedCol = 0"
        val args = Bundle().apply {
            if (parts.isNotEmpty()) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, parts.joinToString(" AND "))
            extraArgs?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(dateCol))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, count)
        }
        val rows = ArrayList<Pair<Long, Long>>()
        context.contentResolver.query(uri, arrayOf(idCol, dateCol), args, null)?.use { c ->
            while (c.moveToNext() && rows.size < count) rows += c.getLong(0) to c.getLong(1)
        }
        return rows
    }

    /**
     * Decides how WhatsApp's gallery may be used so that a PHOTO (never a video) is sent:
     *  - LANDING only if the screenshot is the newest image AND (video access granted and) no video is as new or newer;
     *  - BLOCKED_VIDEO if a video in the screenshots folder is as new or newer than the screenshot;
     *  - otherwise ALBUM.
     */
    override fun galleryRoute(image: LatestImage, folders: List<String>): GalleryRoute = try {
        val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val imgUri = if (q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val imgs = newestDates(imgUri, MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.IS_PENDING, "is_trashed", null, null, 2)
        val shotAdded = image.candidate.dateAddedSec
        val newestImage = imgs.isNotEmpty() && imgs[0].first == image.candidate.id && (imgs.size < 2 || imgs[1].second < imgs[0].second)
        if (!videoPermitted()) {
            GalleryRoute.ALBUM
        } else {
            val vidUri = if (q) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val newestVideo = newestDates(vidUri, MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.IS_PENDING, "is_trashed", null, null, 1).firstOrNull()
            if (newestImage && (newestVideo == null || newestVideo.second < shotAdded)) {
                GalleryRoute.LANDING
            } else {
                // A video inside the screenshots folder that is as new as the screenshot could become the first album tile.
                val leaves = ScreenshotFolderMatcher.leafNames(folders)
                val pathCol = if (q) MediaStore.Video.Media.RELATIVE_PATH else MediaStore.Video.Media.DATA
                val like = leaves.joinToString(" OR ") { "$pathCol LIKE ?" }
                val folderVideo = if (leaves.isEmpty()) null else
                    newestDates(vidUri, MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.IS_PENDING, "is_trashed", "($like)", leaves.map { if (q) "%$it/" else "%/$it/%" }.toTypedArray(), 1).firstOrNull()
                if (folderVideo != null && folderVideo.second >= shotAdded) GalleryRoute.BLOCKED_VIDEO else GalleryRoute.ALBUM
            }
        }
    } catch (e: Exception) {
        GalleryRoute.ALBUM
    }

    /** Copies the chosen image into private temporary storage (shared with WhatsApp through FileProvider). */
    override fun stage(image: LatestImage, settings: AutomationSettings, callback: (CaptureResult) -> Unit) {
        worker.execute {
            var part: File? = null
            val r: CaptureResult = try {
                val fmt = if (ImageFormats.extensionFor(image.candidate.mime) == "png") ImageFormat.PNG else ImageFormat.JPEG
                val p = repository.newTempFile(fmt).also { part = it }
                val input = source.open(image.candidate) ?: throw IOException("gone")
                input.use { i -> FileOutputStream(p).use { o -> i.copyTo(o); o.fd.sync() } }
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(p.path, b)
                CaptureResult.Saved(repository.commit(p, fmt, b.outWidth, b.outHeight))
            } catch (e: IOException) {
                part?.let(repository::discardPart); CaptureResult.Failed(FailureReason.STORAGE)
            } catch (e: Exception) {
                part?.let(repository::discardPart); CaptureResult.Failed(FailureReason.UNKNOWN)
            }
            main.post { callback(r) }
        }
    }
}

/** Notices new images in the media library (debounced so a half-written screenshot is not acted on). */
class NewScreenshotWatcher(private val context: Context, private val onNew: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val fire = Runnable { onNew() }
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            main.removeCallbacks(fire)
            main.postDelayed(fire, SETTLE_MS)
        }
    }
    private var registered = false

    fun start() {
        if (registered) return
        try {
            context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            registered = true
        } catch (e: SecurityException) { }
    }

    fun stop() {
        if (registered) context.contentResolver.unregisterContentObserver(observer)
        registered = false
        main.removeCallbacks(fire)
    }

    companion object { const val SETTLE_MS = 1_500L }
}
