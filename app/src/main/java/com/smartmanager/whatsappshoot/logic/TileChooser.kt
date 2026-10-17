package com.smartmanager.whatsappshoot.logic

/**
 * What the phone's media library says about the newest picture, so the right tile of WhatsApp's gallery can be chosen without ever sending a video.
 * @param verified video dates could be read (video permission granted)
 * @param skip number of videos at least as new as the newest photo: they come before the photo in WhatsApp's newest-first gallery
 */
data class TilePlan(val verified: Boolean, val skip: Int)

sealed class TileChoice {
    /** Tap tile [index] (reading order). [byPosition] = chosen by counting newer videos because no tile showed a video marker. */
    data class Pick(val index: Int, val byPosition: Boolean) : TileChoice()

    /** Every visible tile is a video. */
    object NotAPhoto : TileChoice()

    /** The app cannot tell whether the tile is a photo: nothing is sent. */
    object Unverifiable : TileChoice()
    object None : TileChoice()
}

/**
 * Picks the newest PHOTO tile of WhatsApp's landing gallery.
 *  1. If any visible tile shows a video marker (duration / "video"), the first tile without one is the newest photo.
 *  2. Otherwise, with video dates readable: the newest item is a photo when no video is newer (tile 0); else the photo sits after the newer videos (tile [TilePlan.skip]).
 *  3. Otherwise (no markers seen and videos cannot be checked) nothing is sent: a video would look the same.
 */
object TileChooser {
    fun choose(isVideo: List<Boolean>, plan: TilePlan): TileChoice {
        if (isVideo.isEmpty()) return TileChoice.None
        if (isVideo.any { it }) {
            val i = isVideo.indexOfFirst { !it }
            return if (i < 0) TileChoice.NotAPhoto else TileChoice.Pick(i, byPosition = false)
        }
        if (!plan.verified) return TileChoice.Unverifiable
        if (plan.skip <= 0) return TileChoice.Pick(0, byPosition = false)
        return if (plan.skip < isVideo.size) TileChoice.Pick(plan.skip, byPosition = true) else TileChoice.Unverifiable
    }
}
