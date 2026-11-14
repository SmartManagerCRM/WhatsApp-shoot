package com.smartmanager.whatsappshoot.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.smartmanager.whatsappshoot.logic.CatalogueCodec
import com.smartmanager.whatsappshoot.logic.Product
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The catalogue: a small text file and one JPEG per product in the app's private storage (never shared, never backed up, never uploaded).
 * Pictures are scaled down to [MAX_SIDE] px when added.
 */
class CatalogueStore(private val context: Context) {
    private val dir = File(context.filesDir, "catalogue").apply { mkdirs() }
    private val file = File(dir, "products.txt")
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val _products = MutableStateFlow(load())
    val products: StateFlow<List<Product>> = _products

    /** Bumped when a picture file changes, so thumbnails reload. */
    val pictureVersion = MutableStateFlow(0)

    fun all(): List<Product> = _products.value
    fun imageFile(p: Product): File = File(dir, "${p.id}.jpg")

    private fun load(): List<Product> = try { if (file.exists()) CatalogueCodec.decode(file.readText()) else emptyList() } catch (e: Exception) { emptyList() }

    private fun persist(list: List<Product>) {
        val tmp = File(dir, "products.tmp")
        tmp.writeText(CatalogueCodec.encode(list))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    /** Adds ([existingId] null) or updates a product. [newPicture] is required for a new product; null keeps the current picture. */
    fun save(existingId: String?, name: String, price: String, description: String, newPicture: Uri?, done: (Boolean) -> Unit) {
        io.execute {
            val ok = try {
                val id = existingId ?: UUID.randomUUID().toString().take(8)
                val target = File(dir, "$id.jpg")
                if (newPicture != null) {
                    val bmp = decode(context, newPicture, MAX_SIDE) ?: throw IllegalStateException("picture unreadable")
                    val tmp = File(dir, "$id.tmp")
                    FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    if (!tmp.renameTo(target)) throw IllegalStateException("cannot store picture")
                } else if (!target.exists()) throw IllegalStateException("no picture")
                val p = Product(id, name.trim(), price.trim(), description.trim())
                val next = if (existingId == null) _products.value + p else _products.value.map { if (it.id == id) p else it }
                persist(next)
                main.post { _products.value = next; if (newPicture != null) pictureVersion.value += 1 }
                true
            } catch (e: Exception) { false }
            main.post { done(ok) }
        }
    }

    fun delete(id: String) {
        io.execute {
            try {
                val next = _products.value.filterNot { it.id == id }
                persist(next)
                File(dir, "$id.jpg").delete()
                main.post { _products.value = next }
            } catch (e: Exception) { }
        }
    }

    companion object {
        const val MAX_SIDE = 1600

        /** Decodes [uri] scaled so its longest side is at most [maxSide], upright according to its EXIF rotation. */
        fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? = try {
            val resolver = context.contentResolver
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth <= 0 || o.outHeight <= 0) null else {
                var sample = 1
                while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
                val d = BitmapFactory.Options().apply { inSampleSize = sample }
                val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, d) }
                val rotation = try {
                    resolver.openInputStream(uri)?.use { s ->
                        when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                            else -> 0f
                        }
                    } ?: 0f
                } catch (e: Exception) { 0f }
                val scaled = raw?.let { b ->
                    val k = maxSide.toFloat() / Math.max(b.width, b.height)
                    if (k < 1f) Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true) else b
                }
                if (scaled != null && rotation != 0f) Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, Matrix().apply { postRotate(rotation) }, true) else scaled
            }
        } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }

        /** Small decode of a stored picture for list thumbnails. */
        fun thumbnail(file: File, maxSide: Int): Bitmap? = try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, o)
            var sample = 1
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) { null }
    }
}
