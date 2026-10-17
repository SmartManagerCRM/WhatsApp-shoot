package com.smartmanager.whatsappshoot.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class TileChooserTest {
    private val verifiedNone = TilePlan(verified = true, skip = 0)

    @Test fun noTiles_isNone() = assertEquals(TileChoice.None, TileChooser.choose(emptyList(), verifiedNone))

    @Test fun markersVisible_firstTileWithoutAVideoMarkerIsTheNewestPhoto() {
        assertEquals(TileChoice.Pick(2, false), TileChooser.choose(listOf(true, true, false, false), verifiedNone))
        assertEquals("even without video access the markers are decisive", TileChoice.Pick(1, false), TileChooser.choose(listOf(true, false, false), TilePlan(false, 0)))
    }

    @Test fun everyVisibleTileAVideo_isNotAPhoto() = assertEquals(TileChoice.NotAPhoto, TileChooser.choose(listOf(true, true, true), verifiedNone))

    @Test fun noMarkers_newestItemIsAPhoto_whenNoVideoIsNewer() =
        assertEquals(TileChoice.Pick(0, false), TileChooser.choose(listOf(false, false, false), verifiedNone))

    @Test fun noMarkers_newerVideosExist_photoIsFoundByPosition() =
        assertEquals(TileChoice.Pick(2, true), TileChooser.choose(listOf(false, false, false, false), TilePlan(true, 2)))

    @Test fun noMarkers_andVideosCannotBeChecked_nothingIsSent() =
        assertEquals(TileChoice.Unverifiable, TileChooser.choose(listOf(false, false), TilePlan(false, 0)))

    @Test fun noMarkers_butPhotoIsBeyondTheVisibleTiles_nothingIsSent() =
        assertEquals(TileChoice.Unverifiable, TileChooser.choose(listOf(false, false), TilePlan(true, 5)))
}
