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
) : ProductRecognizer {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val arabicWorker = Executors.newSingleThreadExecutor()

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
        val text = readText(Uri.parse(image.candidate.uri), settings.arabicReading) ?: return ProductResolution.NoMatch
        val product = if (products.isEmpty()) null else ProductMatcher.match(text, products)
        val details = if (wantDetails) DetailExtractor.extract(text).takeUnless { it.isEmpty } else null
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

    /**
     * Text of the picture at [uri]: the Latin reader's text plus, when [arabic] is on, the Arabic reader's text (run at the same time; it is stopped if it takes too long).
     * Null if the picture could not be read at all. Failures are logged without any text.
     */
    private fun readText(uri: Uri, arabic: Boolean): String? {
        val bmp = CatalogueStore.decode(context, uri, OCR_SIDE) ?: run { ActivityLog.add("catalogue: photo unreadable"); return null }
        val arabicRun = if (arabic) arabicWorker.submit(java.util.concurrent.Callable<String> {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val out = arabicReader.read(smaller(bmp, ARABIC_SIDE))
            ActivityLog.add("catalogue: arabic reading ${android.os.SystemClock.elapsedRealtime() - t0} ms, ${out.length} characters")
            out
        }) else null
        val latin = try { reader.read(bmp) } catch (e: Throwable) { ActivityLog.add("catalogue: text reader failed (${e.javaClass.simpleName})"); null }
        var arabicText = ""
        if (arabicRun != null) {
            try {
                arabicText = arabicRun.get(ARABIC_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                arabicReader.cancel(); arabicRun.cancel(true); ActivityLog.add("catalogue: arabic reading stopped after ${ARABIC_TIMEOUT_MS / 1000}s")
            } catch (e: Throwable) {
                ActivityLog.add("catalogue: arabic reader failed (${(e.cause ?: e).javaClass.simpleName})")
            }
        }
        if (latin == null && arabicText.isBlank()) return null
        return listOfNotNull(latin?.takeIf { it.isNotBlank() }, arabicText.takeIf { it.isNotBlank() }).joinToString("\n")
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
            val text = readText(uri, arabic)
            val product = text?.let { ProductMatcher.match(it, store.all()) }
            val details = text?.let { DetailExtractor.extract(it) }
            main.post { callback(text, product, details) }
        }
    }

    companion object {
        /** Small print on a photographed receipt is lost at lower resolutions. */
        const val OCR_SIDE = 2560
        /** The Arabic reader works on a smaller copy (its time grows with the number of pixels). */
        const val ARABIC_SIDE = 1600
        const val ARABIC_TIMEOUT_MS = 14_000L
    }
}
