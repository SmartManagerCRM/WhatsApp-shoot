package com.smartmanager.whatsappshoot.platform

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.smartmanager.whatsappshoot.logic.ActivityLog
import com.smartmanager.whatsappshoot.logic.AutomationSettings
import com.smartmanager.whatsappshoot.logic.CaptionBuilder
import com.smartmanager.whatsappshoot.logic.LatestImage
import com.smartmanager.whatsappshoot.logic.Product
import com.smartmanager.whatsappshoot.logic.ProductMatcher
import com.smartmanager.whatsappshoot.logic.ProductRecognizer
import com.smartmanager.whatsappshoot.logic.ProductResolution
import java.util.concurrent.Executors

/** Reads the newest photo and finds the catalogue product it names. Everything happens on the phone. */
class CatalogueRecognizer(
    private val context: Context,
    private val store: CatalogueStore,
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
        if (products.isEmpty()) return ProductResolution.Disabled
        val text = readText(Uri.parse(image.candidate.uri)) ?: return ProductResolution.NoMatch
        val product = ProductMatcher.match(text, products)
        ActivityLog.add("catalogue: read ${text.length} characters, ${products.size} products, matched=${product != null}")
        if (product == null) return ProductResolution.NoMatch
        return ProductResolution.Matched(product, CaptionBuilder.build(product, settings.captionText))
    }

    /** Recognised text of the picture at [uri], or null if the picture or the reader failed (logged without any text). */
    private fun readText(uri: Uri): String? {
        val bmp = CatalogueStore.decode(context, uri, OCR_SIDE) ?: run { ActivityLog.add("catalogue: photo unreadable"); return null }
        return try { reader.read(bmp) } catch (e: Throwable) { ActivityLog.add("catalogue: text reader failed (${e.javaClass.simpleName})"); null }
    }

    /** For the "try it" card: what the phone reads on [uri] and which product it would pick. */
    fun test(uri: Uri, callback: (text: String?, product: Product?) -> Unit) {
        worker.execute {
            val text = readText(uri)
            val product = text?.let { ProductMatcher.match(it, store.all()) }
            main.post { callback(text, product) }
        }
    }

    companion object { const val OCR_SIDE = 1600 }
}
