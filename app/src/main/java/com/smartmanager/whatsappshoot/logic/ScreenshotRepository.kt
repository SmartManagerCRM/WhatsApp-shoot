package com.smartmanager.whatsappshoot.logic

import java.io.File
import java.io.IOException

data class StoredScreenshot(val file: File, val format: ImageFormat, val createdAtMs: Long, val widthPx: Int, val heightPx: Int)

/**
 * Screenshot files live in one dedicated directory inside the app's private cache.
 * This class only ever touches files in that directory whose names carry our prefix, so unrelated
 * user files can never be deleted by cleanup.
 */
class ScreenshotRepository(private val directory: File, private val clock: Clock) {

    /** Creates a unique, empty ".part" file; write the image into it, then [commit] it. */
    @Throws(IOException::class)
    fun newTempFile(format: ImageFormat): File {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Cannot create screenshot directory")
        }
        val f = File(directory, "$PREFIX${java.util.UUID.randomUUID()}.${format.extension}.part")
        if (!f.createNewFile()) throw IOException("Cannot create screenshot file")
        return f
    }

    /** Atomically publishes a finished ".part" file under its final name. */
    @Throws(IOException::class)
    fun commit(part: File, format: ImageFormat, width: Int, height: Int): StoredScreenshot {
        require(part.parentFile?.canonicalFile == directory.canonicalFile && part.name.endsWith(".part")) { "not a repository temp file" }
        val finalFile = File(directory, part.name.removeSuffix(".part"))
        if (!part.renameTo(finalFile)) throw IOException("Cannot finalize screenshot")
        return StoredScreenshot(finalFile, format, clock.nowMs(), width, height)
    }

    fun discardPart(part: File) {
        if (part.name.startsWith(PREFIX) && part.parentFile?.canonicalFile == directory.canonicalFile) part.delete()
    }

    fun delete(shot: StoredScreenshot): Boolean = owned(shot.file) && (shot.file.delete() || !shot.file.exists())

    fun deleteAll(): Int = ownedFiles().count { it.delete() }

    /** Deletes our files older than [retentionMs]; returns how many were removed. */
    fun cleanupOlderThan(retentionMs: Long): Int {
        val cutoff = clock.nowMs() - retentionMs
        return ownedFiles().filter { it.lastModified() < cutoff }.count { it.delete() }
    }

    fun list(): List<File> = ownedFiles().sortedBy { it.lastModified() }

    private fun owned(f: File): Boolean =
        f.name.startsWith(PREFIX) && f.parentFile?.canonicalFile == directory.canonicalFile

    private fun ownedFiles(): List<File> =
        directory.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) }?.toList().orEmpty()

    companion object {
        const val PREFIX = "wss_"
    }
}
