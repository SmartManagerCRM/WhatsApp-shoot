package com.smartmanager.whatsappshoot.platform

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/** Reads text from a picture entirely on the phone (Google ML Kit, Latin script, model bundled in the app: no download, no network). */
interface TextReader {
    /** Blocking; call off the main thread. Throws if the reader fails. */
    fun read(bitmap: Bitmap): String

    /** Asks a running [read] (on another thread) to stop as soon as it can. */
    fun cancel() {}
}

class MlKitTextReader(@Suppress("unused") private val context: Context) : TextReader {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override fun read(bitmap: Bitmap): String =
        Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 6, TimeUnit.SECONDS).text
}
