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
            val r = try { compute(image, settings) } catch (e: Exception) { ProductResolution.Failed("unexpected ${e.javaClass.simpleName}") }
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
        val picture = store.imageFile(product)
        if (!picture.exists()) return ProductResolution.Failed("product picture missing")
        val published = publisher.publish(product, picture) ?: return ProductResolution.Failed("could not put the product picture in the gallery as the newest photo")
        return ProductResolution.Matched(product, published, CaptionBuilder.build(product, settings.captionText, settings.captionIncludeDescription))
    }

    /** Recognised text of the picture at [uri], or null if the picture or the reader failed (logged without any text). */
    private fun readText(uri: Uri): String? {
        val bmp = CatalogueStore.decode(context, uri, OCR_SIDE) ?: run { ActivityLog.add("catalogue: photo unreadable"); return null }
        return try { reader.read(bmp) } catch (e: Exception) { ActivityLog.add("catalogue: text reader failed (${e.javaClass.simpleName})"); null }
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
