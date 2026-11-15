package com.smartmanager.whatsappshoot.platform

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.smartmanager.whatsappshoot.logic.ActivityLog
import com.smartmanager.whatsappshoot.logic.AutomationSettings
import com.smartmanager.whatsappshoot.R
import com.smartmanager.whatsappshoot.logic.CaptionBuilder
import com.smartmanager.whatsappshoot.logic.DetailExtractor
import com.smartmanager.whatsappshoot.logic.DetailLabels
import com.smartmanager.whatsappshoot.logic.ScannedDetails
import com.smartmanager.whatsappshoot.logic.LatestImage
import com.smartmanager.whatsappshoot.logic.NoteLocator
import com.smartmanager.whatsappshoot.logic.NoteRegion
import com.smartmanager.whatsappshoot.logic.Product
import com.smartmanager.whatsappshoot.logic.ProductMatcher
import com.smartmanager.whatsappshoot.logic.ProductRecognizer
import com.smartmanager.whatsappshoot.logic.ProductResolution
import java.util.concurrent.Executors

/** Reads the newest photo, finds the catalogue product it names, and prepares that product's picture. Everything happens on the phone. */
class CatalogueRecognizer(
    private val context: Context,
    private val store: CatalogueStore,
    private val publisher: ProductPhotoPublisher,
    private val reader: TextReader,
    /** Reads Arabic script (the Latin [reader] ignores it). */
    private val arabicReader: TextReader,
    /** A second Arabic reader instance, so the notes strip can be read while the whole picture is still being read. */
    private val noteReader: TextReader,
) : ProductRecognizer {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val arabicWorker = Executors.newSingleThreadExecutor()
    private val noteWorker = Executors.newSingleThreadExecutor()

    override fun resolve(image: LatestImage, settings: AutomationSettings, callback: (ProductResolution) -> Unit) {
        worker.execute {
            // Throwable, not Exception: a native-library or memory error in the text reader must still end in an answer, never a silent dead thread.
            val r = try { compute(image, settings) } catch (e: Throwable) { ActivityLog.add("catalogue: failed (${e.javaClass.simpleName})"); ProductResolution.NoMatch }
            main.post { callback(r) }
        }
    }

    private fun compute(image: LatestImage, settings: AutomationSettings): ProductResolution {
        val products = store.all()
        val wantDetails = settings.captionIncludeDetails
        if (products.isEmpty() && !wantDetails) return ProductResolution.Disabled
        val scan = scan(Uri.parse(image.candidate.uri), settings.arabicReading) ?: return ProductResolution.NoMatch
        val text = scan.text
        val product = if (products.isEmpty()) null else ProductMatcher.match(text, products)
        val details = if (wantDetails) detailsOf(scan).takeUnless { it.isEmpty } else null
        // Only the make-up of the text is logged (counts), never the text itself.
        val latin = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        val arabic = text.count { it in '\u0600'..'\u06FF' }
        ActivityLog.add("catalogue: text make-up latin=$latin arabic-script=$arabic digits=${text.count { it.isDigit() }} lines=${text.lines().size}")
        ActivityLog.add(
            "catalogue: read ${text.length} characters, ${products.size} products, matched=${product != null}, " +
                "order=${details?.orderNumber != null} date=${details?.date != null} branch=${details?.branch != null} notes=${details?.notes != null}",
        )
        val caption = CaptionBuilder.build(product, settings.captionText, settings.captionIncludeDescription, details, labels())
        if (product == null) return if (details != null) ProductResolution.TextOnly(caption) else ProductResolution.NoMatch
        val picture = store.imageFile(product)
        if (!picture.exists()) return ProductResolution.Failed("product picture missing")
        val published = publisher.publish(product, picture) ?: return ProductResolution.Failed("could not put the product picture in the gallery as the newest photo (${publisher.lastFailure})")
        return ProductResolution.Matched(product, published, caption)
    }

    /** Everything read on a picture: the text, and whether a notes heading was found (then [note] is the note under it, which may be null if that box is empty). */
    private class Scan(val text: String, val noteHeadingFound: Boolean, val note: String?)

    /** The details of [scan]: found in the text, except the note when a notes heading was located on the picture (the strip under it is more reliable than neighbouring text). */
    private fun detailsOf(scan: Scan): ScannedDetails {
        val d = DetailExtractor.extract(scan.text)
        return if (scan.noteHeadingFound) d.copy(notes = scan.note) else d
    }

    /**
     * Reads the picture at [uri]: the Latin reader (text and line positions) and, when [arabic] is on, the Arabic reader on the whole picture, plus the Arabic reader on the
     * strip under a "Notes" heading (cropped and enlarged, because notes are small print). All Arabic work is stopped if it takes too long. Null if the picture could not
     * be read at all. Failures are logged without any text.
     */
    private fun scan(uri: Uri, arabic: Boolean): Scan? {
        val bmp = CatalogueStore.decode(context, uri, OCR_SIDE) ?: run { ActivityLog.add("catalogue: photo unreadable"); return null }
        val started = android.os.SystemClock.elapsedRealtime()
        val wholeRun = if (arabic) arabicWorker.submit(java.util.concurrent.Callable<String> {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val out = arabicReader.read(smaller(bmp, ARABIC_SIDE))
            ActivityLog.add("catalogue: arabic reading ${android.os.SystemClock.elapsedRealtime() - t0} ms, ${out.length} characters")
            out
        }) else null
        val latin = try { reader.readDetailed(bmp) } catch (e: Throwable) { ActivityLog.add("catalogue: text reader failed (${e.javaClass.simpleName})"); null }
        val noteScan = latin?.lines?.takeIf { it.isNotEmpty() }?.let { NoteLocator.locate(it, bmp.width, bmp.height) }
        val stripRegion = noteScan?.region
        val noteRun = if (arabic && stripRegion != null) noteWorker.submit(java.util.concurrent.Callable<String> {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val out = noteReader.read(noteStrip(bmp, stripRegion))
            ActivityLog.add("catalogue: notes strip read ${android.os.SystemClock.elapsedRealtime() - t0} ms, ${out.length} characters")
            out
        }) else null

        fun <T> await(run: java.util.concurrent.Future<T>?, reader: TextReader, what: String): T? {
            if (run == null) return null
            val left = ARABIC_TIMEOUT_MS - (android.os.SystemClock.elapsedRealtime() - started)
            return try {
                run.get(Math.max(left, 500L), java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                reader.cancel(); run.cancel(true); ActivityLog.add("catalogue: $what stopped after ${ARABIC_TIMEOUT_MS / 1000}s"); null
            } catch (e: Throwable) {
                ActivityLog.add("catalogue: $what failed (${(e.cause ?: e).javaClass.simpleName})"); null
            }
        }
        val noteRaw = await(noteRun, noteReader, "notes reading")
        val wholeText = await(wholeRun, arabicReader, "arabic reading") ?: ""
        if (latin == null && wholeText.isBlank()) return null
        val text = listOfNotNull(latin?.text?.takeIf { it.isNotBlank() }, wholeText.takeIf { it.isNotBlank() }).joinToString("\n")

        var note: String? = null
        if (noteScan != null) {
            val arabicNote = noteRaw?.let { NoteLocator.cleanNote(it) }
            note = if (arabicNote != null && NoteLocator.hasArabic(arabicNote)) arabicNote else noteScan.latinBody
            ActivityLog.add(
                "catalogue: notes heading found, strip=${noteScan.region?.let { it.bottom - it.top } ?: 0}px arabic-note=${arabicNote != null} " +
                    "latin-note=${noteScan.latinBody != null} note-used=${note != null}",
            )
        }
        return Scan(text, noteScan != null, note)
    }

    /** The strip of [bmp] under a notes heading, enlarged so small Arabic print is big enough to be read. */
    private fun noteStrip(bmp: android.graphics.Bitmap, r: NoteRegion): android.graphics.Bitmap {
        val top = r.top.coerceIn(0, bmp.height - 1)
        val h = (r.bottom - top).coerceIn(1, bmp.height - top)
        val crop = android.graphics.Bitmap.createBitmap(bmp, 0, top, bmp.width, h)
        val k = (NOTE_TARGET_WIDTH.toFloat() / bmp.width).coerceIn(1f, 3f)
        return if (k < 1.05f) crop else android.graphics.Bitmap.createScaledBitmap(crop, (bmp.width * k).toInt(), (h * k).toInt().coerceAtLeast(1), true)
    }

    private fun smaller(b: android.graphics.Bitmap, side: Int): android.graphics.Bitmap {
        val k = side.toFloat() / Math.max(b.width, b.height)
        return if (k >= 1f) b else android.graphics.Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
    }

    /** The words in front of each detail, in the language chosen in the app's settings. */
    fun labels(): DetailLabels {
        val c = LocaleHelper.wrap(context)
        return DetailLabels(c.getString(R.string.d_label_order), c.getString(R.string.d_label_date), c.getString(R.string.d_label_branch), c.getString(R.string.d_label_notes))
    }

    /** For the "try it" card: what the phone reads on [uri], which product it would pick and which details it finds. */
    fun test(uri: Uri, arabic: Boolean, callback: (text: String?, product: Product?, details: ScannedDetails?) -> Unit) {
        worker.execute {
            val scan = scan(uri, arabic)
            val product = scan?.let { ProductMatcher.match(it.text, store.all()) }
            val details = scan?.let { detailsOf(it) }
            main.post { callback(scan?.text, product, details) }
        }
    }

    companion object {
        /** Small print on a photographed receipt is lost at lower resolutions. */
        const val OCR_SIDE = 2560
        /** The Arabic reader works on a smaller copy (its time grows with the number of pixels). */
        const val ARABIC_SIDE = 1600
        const val ARABIC_TIMEOUT_MS = 14_000L
        /** The notes strip is enlarged (up to 3x) towards this width before it is read. */
        const val NOTE_TARGET_WIDTH = 2400
    }
}
