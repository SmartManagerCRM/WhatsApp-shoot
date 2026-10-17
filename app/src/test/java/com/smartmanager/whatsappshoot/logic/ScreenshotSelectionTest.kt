package com.smartmanager.whatsappshoot.logic

import org.junit.Assert.*
import org.junit.Test

class ScreenshotSelectionTest {
    private val clock = FakeClock()
    private val src = FakeSource(clock)
    private val sel = ScreenshotSelector(src, clock)
    private fun latest(folders: List<String> = listOf("Screenshots")) = sel.selectLatest(folders)
    private fun found(r: SelectionResult) = (r as SelectionResult.Found).image

    @Test fun folderMatcherIsExactSegmentCaseInsensitive() {
        val n = listOf("Screenshots")
        assertTrue(ScreenshotFolderMatcher.matches("Pictures/Screenshots/", n))
        assertTrue(ScreenshotFolderMatcher.matches("DCIM/screenshots", n))
        assertTrue(ScreenshotFolderMatcher.matches("/storage/emulated/0/Pictures/Screenshots", n))
        assertFalse(ScreenshotFolderMatcher.matches("Pictures/NotScreenshots/", n))
        assertFalse(ScreenshotFolderMatcher.matches("Pictures/Screenshots/Sub/", n))
        assertFalse(ScreenshotFolderMatcher.matches("DCIM/Camera/", n))
        assertFalse(ScreenshotFolderMatcher.matches(null, n))
        assertFalse(ScreenshotFolderMatcher.matches("Pictures/Screenshots/", listOf("", "../Screenshots", "a/b")))
    }

    @Test fun emptyFolder() = assertEquals(SelectionResult.NoScreenshots, latest())

    @Test fun pickNewestByCaptureDate() {
        src.add(1, c = cand(1, clock, ageMs = 300_000)); src.add(2, c = cand(2, clock, ageMs = 60_000)); src.add(3, c = cand(3, clock, ageMs = 120_000))
        assertEquals(2L, found(latest()).candidate.id)
        assertFalse(found(latest()).skippedNewer)
    }

    @Test fun dateFallbackOrder_takenThenAddedThenModified() {
        val a = cand(1, clock, takenMs = 0, addedSec = 0, modifiedSec = 5000)
        assertEquals(5_000_000L, a.effectiveDateMs)
        val b = cand(2, clock, takenMs = 0, addedSec = 9000, modifiedSec = 5000)
        assertEquals(9_000_000L, b.effectiveDateMs)
        val c = cand(3, clock, takenMs = 123456789, addedSec = 9000)
        assertEquals(123456789L, c.effectiveDateMs)
        // ordering uses the effective date even if only the fallback exists
        src.add(10, c = cand(10, clock, takenMs = 0, addedSec = (clock.now - 500_000) / 1000))
        src.add(11, c = cand(11, clock, takenMs = 0, addedSec = 0, modifiedSec = (clock.now - 100_000) / 1000))
        assertEquals(11L, found(latest()).candidate.id)
    }

    @Test fun neverSelectsOutsideTheScreenshotsSource() {
        src.add(1, c = cand(1, clock, ageMs = 600_000))
        src.add(2, c = cand(2, clock, ageMs = 1_000, folder = "DCIM/Camera/"))              // newer, but a camera photo
        src.add(3, c = cand(3, clock, ageMs = 2_000, folder = "Pictures/NotScreenshots/"))
        src.add(4, c = cand(4, clock, ageMs = 3_000, folder = "Pictures/Screenshots/Private/"))
        src.add(5, c = cand(5, clock, ageMs = 4_000, folder = null))
        assertEquals(1L, found(latest()).candidate.id)
        assertEquals("only the chosen file is ever opened", listOf(1L), src.opened.distinct())
    }

    @Test fun configurableFolderNames() {
        src.add(1, c = cand(1, clock, folder = "Pictures/Captures/"))
        assertEquals(SelectionResult.NoScreenshots, latest())
        assertEquals(1L, found(latest(listOf("Screenshots", "Captures"))).candidate.id)
        assertEquals(SelectionResult.NoScreenshots, latest(emptyList()))
    }

    @Test fun supportedFormatsOnly() {
        src.add(1, c = cand(1, clock, ageMs = 5_000, mime = "image/gif"))
        src.add(2, c = cand(2, clock, ageMs = 6_000, mime = "video/mp4"))
        src.add(3, c = cand(3, clock, ageMs = 7_000, mime = null))
        assertEquals(SelectionResult.NoScreenshots, latest())
        for ((i, m) in listOf("image/jpeg", "image/png", "image/webp", "IMAGE/PNG").withIndex()) {
            val s = FakeSource(clock); s.add(20L + i, c = cand(20L + i, clock, mime = m))
            assertTrue(m, ScreenshotSelector(s, clock).selectLatest(listOf("Screenshots")) is SelectionResult.Found)
        }
    }

    @Test fun excludesPendingTrashedEmptyAndStillBeingWritten() {
        src.add(1, c = cand(1, clock, ageMs = 900_000))
        src.add(2, c = cand(2, clock, ageMs = 1_000, pending = true))
        src.add(3, c = cand(3, clock, ageMs = 2_000, trashed = true))
        src.add(4, c = cand(4, clock, ageMs = 3_000, size = 0))
        src.add(5, c = cand(5, clock, ageMs = 100))                       // younger than the minimum age
        assertEquals(1L, found(latest()).candidate.id)
    }

    @Test fun deletedLatestFallsBackToOlderAndFlagsIt() {
        src.add(1, c = cand(1, clock, ageMs = 600_000)); src.add(2, bytes = null, c = cand(2, clock, ageMs = 10_000)) // row exists, file gone
        val r = found(latest())
        assertEquals(1L, r.candidate.id); assertTrue(r.skippedNewer)
    }

    @Test fun corruptLatestIsSkipped() {
        src.add(1, c = cand(1, clock, ageMs = 600_000)); src.add(2, decodable = false, c = cand(2, clock, ageMs = 10_000))
        assertTrue(found(latest()).skippedNewer)
    }

    @Test fun everythingUnreadable() {
        src.add(1, bytes = null); src.add(2, decodable = false); src.add(3, bytes = ByteArray(0))
        assertEquals(SelectionResult.AllUnreadable, latest())
    }

    @Test fun permissionStates() {
        src.add(1)
        src.access = MediaAccess.NONE; assertEquals(SelectionResult.PermissionDenied, latest())
        src.access = MediaAccess.PARTIAL; assertEquals(SelectionResult.PartialAccess, latest())
        src.access = MediaAccess.FULL; src.throwSecurity = true; assertEquals(SelectionResult.PermissionDenied, latest())
        src.throwSecurity = false; src.throwOther = true; assertEquals(SelectionResult.Failed, latest())
        assertTrue("no file may be read without access", src.opened.isEmpty())
    }

    @Test fun identicalFilesShareOneIdentity_differentFilesDoNot() {
        val same = byteArrayOf(9, 9, 9)
        src.add(1, bytes = same, c = cand(1, clock, ageMs = 90_000))
        val first = found(latest()).key
        src.add(2, bytes = same, c = cand(2, clock, ageMs = 30_000))
        assertEquals(first, found(latest()).key)
        src.add(3, bytes = byteArrayOf(1), c = cand(3, clock, ageMs = 10_000))
        assertNotEquals(first, found(latest()).key)
    }

    @Test fun fullPathEntries_pinTheFolder_andGiveTheAlbumName() {
        val p = listOf("/storage/emulated/0/Pictures/Screenshots")
        assertEquals(listOf("Pictures/Screenshots"), ScreenshotFolderMatcher.cleanNames(p))
        assertEquals(listOf("Screenshots"), ScreenshotFolderMatcher.leafNames(p))
        assertTrue(ScreenshotFolderMatcher.matches("Pictures/Screenshots/", p))
        assertFalse("same name in another parent is a different folder", ScreenshotFolderMatcher.matches("DCIM/Screenshots/", p))
        assertTrue(ScreenshotFolderMatcher.matches("/storage/emulated/0/Pictures/Screenshots", p))
        assertEquals(listOf("DCIM/Screenshots"), ScreenshotFolderMatcher.cleanNames(listOf("/sdcard/DCIM/Screenshots/")))
        assertEquals(listOf("Screenshots"), ScreenshotFolderMatcher.cleanNames(listOf("Screenshots")))
    }
}
