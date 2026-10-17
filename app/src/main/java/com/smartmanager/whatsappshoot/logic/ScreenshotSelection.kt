package com.smartmanager.whatsappshoot.logic

import java.io.InputStream
import java.security.MessageDigest

/** One MediaStore image row, reduced to what selection needs. */
data class MediaCandidate(
    val id: Long,
    val uri: String,
    val displayName: String?,
    val mime: String?,
    val sizeBytes: Long,
    /** MediaStore DATE_TAKEN, milliseconds; 0 when unknown. */
    val dateTakenMs: Long,
    /** MediaStore DATE_ADDED, seconds. */
    val dateAddedSec: Long,
    /** MediaStore DATE_MODIFIED, seconds. */
    val dateModifiedSec: Long,
    /** RELATIVE_PATH (Android 10+) or the parent directory of the file path (older versions). */
    val folder: String?,
    val pending: Boolean,
    val trashed: Boolean,
) {
    /**
     * Documented date rule: capture time (DATE_TAKEN) if present, else the time the file was added to the
     * media library (DATE_ADDED), else the modification time (DATE_MODIFIED).
     */
    val effectiveDateMs: Long
        get() = when {
            dateTakenMs > 0 -> dateTakenMs
            dateAddedSec > 0 -> dateAddedSec * 1000
            else -> dateModifiedSec * 1000
        }
}

enum class MediaAccess { FULL, PARTIAL, NONE }

/** Port to the device's media library (MediaStore on Android). */
interface ScreenshotSource {
    fun access(): MediaAccess

    /** Newest-first candidates from the given folders, at most [limit]. May throw [SecurityException]. */
    fun candidates(folders: List<String>, limit: Int): List<MediaCandidate>

    /** Opens the image bytes; null if the file is gone or inaccessible. */
    fun open(candidate: MediaCandidate): InputStream?

    /** True if the file decodes as an image (not truncated/corrupt). */
    fun isDecodable(candidate: MediaCandidate): Boolean
}

/**
 * Only direct children of a configured folder qualify (case-insensitive). An entry is either a bare folder name ("Screenshots") or a
 * path ("/storage/emulated/0/Pictures/Screenshots", "Pictures/Screenshots"): the storage prefix is ignored and the remaining
 * segments must be the END of the image's folder, so a path pins the exact folder and a bare name matches the last segment.
 */
object ScreenshotFolderMatcher {
    private fun segments(raw: String): List<String> = raw.trim().replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() }

    private fun withoutStoragePrefix(seg: List<String>): List<String> {
        var s = seg
        if (s.firstOrNull().equals("storage", true)) {
            s = s.drop(1)
            if (s.firstOrNull().equals("emulated", true)) s = s.drop(2) else s = s.drop(1) // volume id (SD card)
        } else if (s.firstOrNull().equals("sdcard", true)) s = s.drop(1)
        return s
    }

    /** Normalised entries ("Screenshots" or "Pictures/Screenshots"); invalid ones are dropped. */
    fun cleanNames(names: Collection<String>): List<String> =
        names.map { withoutStoragePrefix(segments(it)) }
            .filter { it.isNotEmpty() && it.none { seg -> seg == ".." || seg == "." } }
            .map { it.joinToString("/") }.distinct()

    /** The last segment of each entry: the album name WhatsApp's own gallery shows, and a coarse database filter. */
    fun leafNames(names: Collection<String>): List<String> = cleanNames(names).map { it.substringAfterLast('/') }.distinct()

    fun matches(folder: String?, names: Collection<String>): Boolean {
        val f = segments(folder ?: return false)
        if (f.isEmpty()) return false
        return cleanNames(names).any { spec ->
            val sp = spec.split('/')
            f.size >= sp.size && f.takeLast(sp.size).zip(sp).all { (x, y) -> x.equals(y, ignoreCase = true) }
        }
    }
}

object ImageFormats {
    val SUPPORTED_MIME = setOf("image/jpeg", "image/png", "image/webp")
    fun extensionFor(mime: String?): String = when (mime) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }
}

data class LatestImage(val candidate: MediaCandidate, val key: String, val skippedNewer: Boolean) {
    val dateMs: Long get() = candidate.effectiveDateMs
}

sealed class SelectionResult {
    data class Found(val image: LatestImage) : SelectionResult()
    object NoScreenshots : SelectionResult()
    object PermissionDenied : SelectionResult()

    /** Android 14+ "selected photos" access: the newest screenshot cannot be determined reliably. */
    object PartialAccess : SelectionResult()
    object AllUnreadable : SelectionResult()
    object Failed : SelectionResult()
}

/**
 * Finds the newest eligible screenshot. Eligible = in a configured Screenshots folder (re-checked here even though the
 * query already filters), supported image type, not pending, not trashed, non-empty, old enough to be complete, readable and
 * decodable. Unreadable newer files are skipped (and reported through [LatestImage.skippedNewer]).
 * Only the chosen file is read (to compute its identity hash); other files are never opened.
 */
class ScreenshotSelector(
    private val source: ScreenshotSource,
    private val clock: Clock,
    private val minAgeMs: Long = MIN_AGE_MS,
    private val maxCandidates: Int = MAX_CANDIDATES,
) {
    fun selectLatest(folders: List<String>): SelectionResult {
        val names = ScreenshotFolderMatcher.cleanNames(folders)
        if (names.isEmpty()) return SelectionResult.NoScreenshots
        when (source.access()) {
            MediaAccess.NONE -> return SelectionResult.PermissionDenied
            MediaAccess.PARTIAL -> return SelectionResult.PartialAccess
            MediaAccess.FULL -> Unit
        }
        val rows = try {
            source.candidates(names, maxCandidates)
        } catch (e: SecurityException) {
            return SelectionResult.PermissionDenied
        } catch (e: Exception) {
            return SelectionResult.Failed
        }
        val now = clock.nowMs()
        val eligible = rows.asSequence()
            .filter { ScreenshotFolderMatcher.matches(it.folder, names) }
            .filter { it.mime?.lowercase() in ImageFormats.SUPPORTED_MIME }
            .filter { !it.pending && !it.trashed && it.sizeBytes > 0 }
            .filter { it.effectiveDateMs <= now - minAgeMs }
            .sortedWith(compareByDescending<MediaCandidate> { it.effectiveDateMs }.thenByDescending { it.dateModifiedSec }.thenByDescending { it.id })
            .toList()
        if (eligible.isEmpty()) return SelectionResult.NoScreenshots
        eligible.forEachIndexed { index, c ->
            val hash = try {
                if (source.isDecodable(c)) hashOf(c) else null
            } catch (e: Exception) {
                null
            }
            if (hash != null) return SelectionResult.Found(LatestImage(c, hash, skippedNewer = index > 0))
        }
        return SelectionResult.AllUnreadable
    }

    private fun hashOf(c: MediaCandidate): String? {
        val md = MessageDigest.getInstance("SHA-256")
        val input = source.open(c) ?: return null
        var total = 0L
        input.use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n); total += n
            }
        }
        if (total == 0L) return null
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MIN_AGE_MS = 1_000L
        const val MAX_CANDIDATES = 30
    }
}
