package com.smartmanager.whatsappshoot.platform

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.smartmanager.whatsappshoot.logic.ProductPhotos
import java.util.concurrent.Executors

/**
 * Versions 1.4.0-1.4.2 put a temporary copy of a product picture into the gallery. Later versions send the newest photo itself and make no copies;
 * this removes any copy an earlier version left behind (only files this app created, recognised by their name).
 */
object LegacyProductCopies {
    private val io = Executors.newSingleThreadExecutor()

    fun removeAsync(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val app = context.applicationContext
        io.execute {
            try {
                app.contentResolver.delete(
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                    "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("${ProductPhotos.PREFIX}%"),
                )
            } catch (e: Exception) { }
        }
    }
}
