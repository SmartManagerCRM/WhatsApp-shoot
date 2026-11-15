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
) : ProductRecognizer {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

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
        val text = readText(Uri.parse(image.candidate.uri)) ?: return ProductResolution.NoMatch
        val product = if (products.isEmpty()) null else ProductMatcher.match(text, products)
        val details = if (wantDetails) DetailExtractor.extract(text).takeUnless { it.isEmpty } else null
        ActivityLog.add(
            "catalogue: read ${text.length} characters, ${products.size} products, matched=${product != null}, " +
                "order=${details?.orderNumber != null} date=${details?.date != null} branch=${details?.branch != null}",
        )
        val caption = CaptionBuilder.build(product, settings.captionText, settings.captionIncludeDescription, details, labels())
        if (product == null) return if (details != null) ProductResolution.TextOnly(caption) else ProductResolution.NoMatch
        val picture = store.imageFile(product)
        if (!picture.exists()) return ProductResolution.Failed("product picture missing")
        val published = publisher.publish(product, picture) ?: return ProductResolution.Failed("could not put the product picture in the gallery as the newest photo (${publisher.lastFailure})")
        return ProductResolution.Matched(product, published, caption)
    }

    /** Recognised text of the picture at [uri], or null if the picture or the reader failed (logged without any text). */
    private fun readText(uri: Uri): String? {
        val bmp = CatalogueStore.decode(context, uri, OCR_SIDE) ?: run { ActivityLog.add("catalogue: photo unreadable"); return null }
        return try { reader.read(bmp) } catch (e: Throwable) { ActivityLog.add("catalogue: text reader failed (${e.javaClass.simpleName})"); null }
    }

    /** The words in front of each detail, in the language chosen in the app's settings. */
    fun labels(): DetailLabels {
        val c = LocaleHelper.wrap(context)
        return DetailLabels(c.getString(R.string.d_label_order), c.getString(R.string.d_label_date), c.getString(R.string.d_label_branch))
    }

    /** For the "try it" card: what the phone reads on [uri], which product it would pick and which details it finds. */
    fun test(uri: Uri, callback: (text: String?, product: Product?, details: ScannedDetails?) -> Unit) {
        worker.execute {
            val text = readText(uri)
            val product = text?.let { ProductMatcher.match(it, store.all()) }
            val details = text?.let { DetailExtractor.extract(it) }
            main.post { callback(text, product, details) }
        }
    }

    companion object { const val OCR_SIDE = 2560 // small print on a photographed receipt is lost at lower resolutions }
}
