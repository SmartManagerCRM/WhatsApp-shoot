package com.smartmanager.whatsappshoot.platform

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/** Reads text from a picture entirely on the phone (Google ML Kit, Latin script, model bundled in the app: no download, no network). */
/** What a reader found: the whole text, and (when the reader knows them) its lines with their positions. */
data class OcrResult(val text: String, val lines: List<com.smartmanager.whatsappshoot.logic.OcrBox> = emptyList())

interface TextReader {
    /** Blocking; call off the main thread. Throws if the reader fails. */
    fun read(bitmap: Bitmap): String

    /** Like [read], with the position of every line when the reader provides it. */
    fun readDetailed(bitmap: Bitmap): OcrResult = OcrResult(read(bitmap))

    /** Asks a running [read] (on another thread) to stop as soon as it can. */
    fun cancel() {}
}

class MlKitTextReader(@Suppress("unused") private val context: Context) : TextReader {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override fun read(bitmap: Bitmap): String = readDetailed(bitmap).text

    override fun readDetailed(bitmap: Bitmap): OcrResult {
        val t = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 6, TimeUnit.SECONDS)
        val lines = t.textBlocks.flatMap { b -> b.lines }.mapNotNull { l ->
            l.boundingBox?.let { r -> com.smartmanager.whatsappshoot.logic.OcrBox(l.text, r.left, r.top, r.right, r.bottom) }
        }
        return OcrResult(t.text, lines)
    }
}
