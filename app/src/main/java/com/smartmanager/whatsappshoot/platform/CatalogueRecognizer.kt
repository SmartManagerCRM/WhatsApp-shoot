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
import com.smartmanager.whatsappshoot.logic.NoteIconLocator
import com.smartmanager.whatsappshoot.logic.NoteLocator
import com.smartmanager.whatsappshoot.logic.OcrBox
import com.smartmanager.whatsappshoot.logic.OrderPlan
import com.smartmanager.whatsappshoot.logic.OrderSplitter
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

    private class Cached(val key: String, val scan: Scan, val atMs: Long)
    @Volatile private var cache: Cached? = null

    /** The reading of [image], from the read made ahead of time when there is one for exactly this photo and these settings. Call on [worker]. */
    private fun cachedScan(image: LatestImage, arabic: Boolean, products: List<Product>, wantDetails: Boolean): Scan? {
        val key = "${image.key}|$arabic|$wantDetails|${products.hashCode()}"
        val hit = cache?.takeIf { it.key == key && android.os.SystemClock.elapsedRealtime() - it.atMs < CACHE_MS }
        if (hit != null) { ActivityLog.add("catalogue: using the reading made ahead of time"); return hit.scan }
        val s = scan(Uri.parse(image.candidate.uri), arabic, products, wantDetails)
        cache = s?.let { Cached(key, it, android.os.SystemClock.elapsedRealtime()) }
        return s
    }

    override fun prefetch(image: LatestImage, settings: AutomationSettings) {
        worker.execute {
            val products = store.all()
            if (products.isEmpty() && !settings.captionIncludeDetails) return@execute
            val t0 = android.os.SystemClock.elapsedRealtime()
            try { cachedScan(image, settings.arabicReading, products, settings.captionIncludeDetails) } catch (e: Throwable) { ActivityLog.add("catalogue: reading ahead failed (${e.javaClass.simpleName})") }
            ActivityLog.add("catalogue: read ahead of time in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        }
    }

    override fun publish(product: Product, callback: (LatestImage?) -> Unit) {
        worker.execute {
            val r = try {
                val picture = store.imageFile(product)
                if (picture.exists()) publisher.publish(product, picture) else null
            } catch (e: Throwable) { ActivityLog.add("catalogue: publishing failed (${e.javaClass.simpleName})"); null }
            if (r == null) ActivityLog.add("catalogue: could not publish the product picture (${publisher.lastFailure})")
            main.post { callback(r) }
        }
    }

    private fun compute(image: LatestImage, settings: AutomationSettings): ProductResolution {
        val products = store.all()
        val wantDetails = settings.captionIncludeDetails
        if (products.isEmpty() && !wantDetails) return ProductResolution.Disabled
        val scan = cachedScan(image, settings.arabicReading, products, wantDetails) ?: return ProductResolution.NoMatch
        if (scan.bands.isNotEmpty()) return computeOrders(scan, products, settings)
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

    /** A photo with several orders: every order whose product is in the catalogue gets its own picture and its own full message; the first is prepared now. */
    private fun computeOrders(scan: Scan, products: List<Product>, settings: AutomationSettings): ProductResolution {
        val labels = labels()
        val plans = ArrayList<OrderPlan>()
        for ((i, b) in scan.bands.withIndex()) {
            val product = ProductMatcher.match(b.text, products)
            val details = if (settings.captionIncludeDetails) b.details.takeUnless { it.isEmpty } else null
            ActivityLog.add("catalogue: order ${i + 1}/${scan.bands.size}: matched=${product != null} order=${details?.orderNumber != null} date=${details?.date != null} branch=${details?.branch != null} notes=${details?.notes != null}")
            if (product == null) continue
            plans += OrderPlan(product, CaptionBuilder.build(product, settings.captionText, settings.captionIncludeDescription, details, labels))
        }
        if (plans.isEmpty()) { ActivityLog.add("catalogue: ${scan.bands.size} orders on the photo, none names a catalogue product"); return ProductResolution.NoMatch }
        val first = plans.first()
        val picture = store.imageFile(first.product)
        if (!picture.exists()) return ProductResolution.Failed("product picture missing")
        val published = publisher.publish(first.product, picture) ?: return ProductResolution.Failed("could not put the product picture in the gallery as the newest photo (${publisher.lastFailure})")
        val matched = ProductResolution.Matched(first.product, published, first.caption)
        return if (plans.size == 1) matched else ProductResolution.Multi(matched, plans.drop(1))
    }

    /** One order of a multi-order photo, read on its own band of the picture. */
    private class BandRead(val text: String, val details: ScannedDetails)

    /** Everything read on a picture: the text, and whether a notes heading/icon was found (then [note] is the note there, which may be null if that box is empty). [bands] is non-empty for a multi-order photo. */
    private class Scan(val text: String, val noteHeadingFound: Boolean, val note: String?, val bands: List<BandRead> = emptyList())

    /** The details of [scan]: found in the text, except the note when a notes heading was located on the picture (the strip under it is more reliable than neighbouring text). */
    private fun detailsOf(scan: Scan): ScannedDetails {
        val d = DetailExtractor.extract(scan.text)
        return if (scan.noteHeadingFound) d.copy(notes = scan.note) else d
    }

    private class NoteOutcome(val found: Boolean, val note: String?)

    /**
     * The note on [bmp] (a whole photo or one order's band): found under a "Notes" heading in [lines], or, when the word is not printed, next to the notes icon. The strip is
     * cropped and enlarged, then read by the Arabic reader [noteReader]. Not thread-safe: use from one thread at a time.
     */
    private fun noteFor(bmp: android.graphics.Bitmap, lines: List<OcrBox>, arabic: Boolean): NoteOutcome {
        val noteScan = lines.takeIf { it.isNotEmpty() }?.let { NoteLocator.locate(it, bmp.width, bmp.height) }
        val t0 = android.os.SystemClock.elapsedRealtime()
        if (noteScan != null) {
            val firstStrip = noteScan.region
            val fullStrip = noteScan.fullRegion
            var block: String? = null
            var reads = 0
            if (arabic && (firstStrip != null || fullStrip != null)) {
                fun arabicLetters(t: String?) = t?.count { it in '\u0600'..'\u06FF' && it.isLetter() } ?: 0
                if (firstStrip != null) { block = NoteLocator.arabicBlock(noteReader.read(noteStrip(bmp, firstStrip))); reads++ }
                // Nothing (or too little) in the strip that ends at the next section heading: try the generous window under the heading.
                if (arabicLetters(block) < 6 && fullStrip != null && (firstStrip == null || fullStrip.bottom - firstStrip.bottom > 20)) {
                    block = NoteLocator.arabicBlock(noteReader.read(noteStrip(bmp, fullStrip))) ?: block; reads++
                }
            }
            val arabicNote = block?.let { NoteLocator.cleanNote(it) }
            val note = if (arabicNote != null && NoteLocator.hasArabic(arabicNote)) arabicNote else noteScan.latinBody
            ActivityLog.add(
                "catalogue: notes heading found, ${android.os.SystemClock.elapsedRealtime() - t0} ms in $reads pass(es), strip=${noteScan.region?.let { it.bottom - it.top } ?: 0}px " +
                    "window=${noteScan.fullRegion?.let { it.bottom - it.top } ?: 0}px arabic-note=${arabicNote != null} latin-note=${noteScan.latinBody != null} note-used=${note != null}",
            )
            return NoteOutcome(true, note)
        }
        if (!arabic) return NoteOutcome(false, null)
        // No "Notes" word: look for the notes icon (a small document outline with three bars).
        val icon = findIcon(bmp) ?: return NoteOutcome(false, null)
        val boundaries = lines.filter { NoteLocator.isSectionHeader(it.text) }.map { it.top }
        val region = NoteIconLocator.region(icon, boundaries, bmp.width, bmp.height)
        var note: String? = null
        if (region != null) {
            val raw = NoteLocator.arabicBlock(noteReader.read(noteStrip(bmp, region)))
            val cleaned = raw?.let { NoteLocator.cleanNote(it) }
            note = cleaned?.takeIf { NoteLocator.hasArabic(it) }
        }
        ActivityLog.add("catalogue: notes icon found (${icon.width}x${icon.height}px), ${android.os.SystemClock.elapsedRealtime() - t0} ms, region=${region?.let { it.bottom - it.top } ?: 0}px note-used=${note != null}")
        return NoteOutcome(true, note)
    }

    /** Looks for the notes icon on a copy of [bmp] no wider than [ICON_SIDE] pixels; the result is in the coordinates of [bmp]. */
    private fun findIcon(bmp: android.graphics.Bitmap): NoteIconLocator.Icon? {
        val k = Math.min(1f, ICON_SIDE.toFloat() / bmp.width)
        val small = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true) else bmp
        val w = small.width; val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val lum = ByteArray(w * h) { i -> val c = px[i]; ((((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000).toByte() }
        val ic = NoteIconLocator.find(lum, w, h) ?: return null
        val back = 1f / k
        return NoteIconLocator.Icon((ic.left * back).toInt(), (ic.top * back).toInt(), (ic.right * back).toInt(), (ic.bottom * back).toInt())
    }

    /**
     * Reads the picture at [uri]: the Latin reader (text and line positions) and, when [arabic] is on, the Arabic reader on the whole picture (stopped early when the Latin
     * text already answers everything needed), plus the Arabic reader on the strip of the notes (cropped and enlarged, because notes are small print). A photo with several
     * orders is read order by order. All Arabic work is stopped if it takes too long. Null if the picture could not be read at all. Failures are logged without any text.
     */
    private fun scan(uri: Uri, arabic: Boolean, products: List<Product>, wantDetails: Boolean): Scan? {
        val bmp = CatalogueStore.decode(context, uri, OCR_SIDE) ?: run { ActivityLog.add("catalogue: photo unreadable"); return null }
        val started = android.os.SystemClock.elapsedRealtime()
        val wholeRun = if (arabic) arabicWorker.submit(java.util.concurrent.Callable<String> {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val out = arabicReader.read(smaller(bmp, ARABIC_SIDE))
            ActivityLog.add("catalogue: arabic reading ${android.os.SystemClock.elapsedRealtime() - t0} ms, ${out.length} characters")
            out
        }) else null
        val latin = try { reader.readDetailed(bmp) } catch (e: Throwable) { ActivityLog.add("catalogue: text reader failed (${e.javaClass.simpleName})"); null }

        // Several orders on one photo: each is read on its own band.
        val bands = if (products.isNotEmpty() && latin != null) OrderSplitter.split(latin.lines, bmp.height) else emptyList()
        if (bands.isNotEmpty()) {
            if (wholeRun != null) { arabicReader.cancel(); wholeRun.cancel(true) }
            ActivityLog.add("catalogue: ${bands.size} orders found on the photo")
            val reads = ArrayList<BandRead>()
            for ((i, band) in bands.withIndex()) {
                val outOfTime = android.os.SystemClock.elapsedRealtime() - started > ARABIC_TIMEOUT_MS
                val crop = android.graphics.Bitmap.createBitmap(bmp, 0, band.top.coerceIn(0, bmp.height - 1), bmp.width, (band.bottom - band.top).coerceIn(1, bmp.height - band.top.coerceIn(0, bmp.height - 1)))
                val outcome = try {
                    noteFor(crop, band.lines, arabic && !outOfTime)
                } catch (e: Throwable) { ActivityLog.add("catalogue: notes of order ${i + 1} failed (${e.javaClass.simpleName})"); NoteOutcome(false, null) }
                if (outOfTime) ActivityLog.add("catalogue: order ${i + 1}: out of time, Arabic notes not read")
                var d = DetailExtractor.extract(band.text)
                if (outcome.found) d = d.copy(notes = outcome.note)
                reads += BandRead(band.text, d)
            }
            return Scan(latin?.text ?: "", false, null, reads)
        }

        val needWhole = arabic && run {
            val t = latin?.text ?: ""
            val matched = products.isEmpty() || (t.isNotBlank() && ProductMatcher.match(t, products) != null)
            val d = DetailExtractor.extract(t)
            !(matched && (!wantDetails || (d.orderNumber != null && d.date != null)))
        }
        if (wholeRun != null && !needWhole) { arabicReader.cancel(); wholeRun.cancel(true); ActivityLog.add("catalogue: Latin text is enough, whole-photo Arabic reading skipped") }
        val activeWhole = if (needWhole) wholeRun else null

        val noteRun = if (latin != null) noteWorker.submit(java.util.concurrent.Callable<NoteOutcome> { noteFor(bmp, latin.lines, arabic) }) else null

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
        val outcome = await(noteRun, noteReader, "notes reading")
        val wholeText = await(activeWhole, arabicReader, "arabic reading") ?: ""
        if (latin == null && wholeText.isBlank()) return null
        val text = listOfNotNull(latin?.text?.takeIf { it.isNotBlank() }, wholeText.takeIf { it.isNotBlank() }).joinToString("\n")
        return Scan(text, outcome?.found == true, outcome?.note)
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
            val scan = scan(uri, arabic, store.all(), true)
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
        /** The notes icon is looked for on a copy of the picture no wider than this. */
        const val ICON_SIDE = 1080
        /** A reading made ahead of time is kept this long. */
        const val CACHE_MS = 10 * 60_000L
    }
}
