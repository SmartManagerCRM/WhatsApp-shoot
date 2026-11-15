package com.smartmanager.whatsappshoot.platform

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * Reads Arabic text (and the Latin letters and digits mixed into it) from a picture entirely on the phone, with Tesseract and the Arabic language data
 * bundled in the app (assets/tessdata/ara.traineddata, Apache License 2.0). No download, no network. One engine instance per call.
 */
class TesseractTextReader(private val context: Context, private val language: String = "ara") : TextReader {
    @Volatile private var current: TessBaseAPI? = null

    private fun dataRoot(): File {
        val root = File(context.filesDir, "ocr")
        val dir = File(root, "tessdata").apply { mkdirs() }
        val target = File(dir, "$language.traineddata")
        if (!target.exists() || target.length() == 0L) {
            val tmp = File(dir, "$language.part")
            context.assets.open("tessdata/$language.traineddata").use { i -> tmp.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            if (!tmp.renameTo(target)) throw IllegalStateException("cannot store language data")
        }
        return root
    }

    override fun read(bitmap: Bitmap): String {
        val api = TessBaseAPI()
        current = api
        try {
            if (!api.init(dataRoot().absolutePath, language, TessBaseAPI.OEM_LSTM_ONLY)) throw IllegalStateException("text reader did not start")
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            api.setImage(bitmap)
            return api.getUTF8Text() ?: ""
        } finally {
            current = null
            api.recycle()
        }
    }

    override fun cancel() {
        try { current?.stop() } catch (e: Throwable) { }
    }
}
