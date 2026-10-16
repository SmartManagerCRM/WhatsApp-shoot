package com.smartmanager.whatsappshoot.logic

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepositoryAndGuardTest {
    @get:Rule val tmp = TemporaryFolder()
    private val clock = FakeClock()

    private fun repo() = ScreenshotRepository(File(tmp.root, "shots"), clock)

    private fun store(r: ScreenshotRepository, fmt: ImageFormat = ImageFormat.PNG): StoredScreenshot {
        val part = r.newTempFile(fmt); part.writeBytes(byteArrayOf(1, 2, 3))
        return r.commit(part, fmt, 10, 20)
    }

    @Test fun commitPublishesAtomicallyWithRightExtension() {
        val r = repo()
        val part = r.newTempFile(ImageFormat.JPEG)
        assertTrue(part.name.endsWith(".jpg.part"))
        part.writeBytes(byteArrayOf(9))
        val shot = r.commit(part, ImageFormat.JPEG, 1, 1)
        assertTrue(shot.file.name.endsWith(".jpg")); assertTrue(shot.file.exists()); assertFalse(part.exists())
    }

    @Test fun deleteAndDeleteAll() {
        val r = repo(); val a = store(r); store(r)
        assertTrue(r.delete(a)); assertFalse(a.file.exists())
        assertEquals(1, r.deleteAll()); assertEquals(0, r.list().size)
    }

    @Test fun retentionCleanupOnlyRemovesOldOwnedFiles() {
        val r = repo(); val old = store(r); val fresh = store(r)
        val foreign = File(File(tmp.root, "shots"), "holiday.jpg").apply { writeBytes(byteArrayOf(1)) }
        old.file.setLastModified(clock.now - 2 * 3_600_000)
        foreign.setLastModified(clock.now - 10 * 3_600_000)
        assertEquals(1, r.cleanupOlderThan(3_600_000))
        assertFalse(old.file.exists()); assertTrue(fresh.file.exists())
        assertTrue("unrelated files must never be deleted", foreign.exists())
        r.deleteAll(); assertTrue(foreign.exists())
    }

    @Test fun cannotDeleteOrCommitFilesOutsideTheDirectory() {
        val r = repo()
        val outside = tmp.newFile("precious.png")
        assertFalse(r.delete(StoredScreenshot(outside, ImageFormat.PNG, 0, 1, 1)))
        assertTrue(outside.exists())
        val fake = File(tmp.root, "wss_x.png.part").apply { writeBytes(byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { r.commit(fake, ImageFormat.PNG, 1, 1) }
    }

    @Test fun storageFailureIsReportedAsIOException() {
        val blocker = tmp.newFile("notadir")   // a file where the directory should be
        val r = ScreenshotRepository(File(blocker, "shots"), clock)
        assertThrows(java.io.IOException::class.java) { r.newTempFile(ImageFormat.PNG) }
    }

    // ---- guard ----
    @Test fun guardBlocksParallelAndRepeatedAutomaticCaptures() {
        val g = CaptureGuard(clock)
        assertTrue(g.tryBegin(false)); assertFalse(g.tryBegin(false)); assertFalse(g.tryBegin(true)) // in flight
        g.finish()
        clock.advance(10_000)
        assertFalse("same visit already handled", g.tryBegin(false))
        g.conversationLeft()
        assertTrue(g.tryBegin(false))
    }

    @Test fun guardCooldownAndUserOverride() {
        val g = CaptureGuard(clock)
        assertTrue(g.tryBegin(true)); g.finish(); g.conversationLeft()
        clock.advance(100)
        assertFalse("cooldown", g.tryBegin(false))
        assertFalse("double tap", g.tryBegin(true))
        clock.advance(1_000)
        assertTrue("deliberate re-capture allowed", g.tryBegin(true))
    }
}
