package com.smartmanager.whatsappshoot.platform

import android.graphics.Bitmap
import com.smartmanager.whatsappshoot.logic.CaptureResult
import com.smartmanager.whatsappshoot.logic.FailureReason
import com.smartmanager.whatsappshoot.logic.ImageFormat
import com.smartmanager.whatsappshoot.logic.ScreenshotRepository
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Encodes a full-resolution bitmap into app-private temporary storage. Never crops or rescales. */
object ScreenshotWriter {
    private const val JPEG_QUALITY = 92

    fun write(bitmap: Bitmap, format: ImageFormat, repo: ScreenshotRepository): CaptureResult {
        var part: File? = null
        return try {
            val p = repo.newTempFile(format).also { part = it }
            FileOutputStream(p).use { out ->
                val fmt = if (format == ImageFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                if (!bitmap.compress(fmt, JPEG_QUALITY, out)) throw IOException("encode failed")
                out.fd.sync()
            }
            CaptureResult.Saved(repo.commit(p, format, bitmap.width, bitmap.height))
        } catch (e: IOException) {
            part?.let(repo::discardPart)
            CaptureResult.Failed(FailureReason.STORAGE)
        } catch (e: OutOfMemoryError) {
            part?.let(repo::discardPart)
            CaptureResult.Failed(FailureReason.MEMORY)
        } finally {
            bitmap.recycle()
        }
    }
}
