package com.smartmanager.whatsappshoot.logic

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

fun cand(
    id: Long, clock: FakeClock, ageMs: Long = 60_000, folder: String? = "Pictures/Screenshots/", mime: String? = "image/png",
    size: Long = 100, takenMs: Long? = null, addedSec: Long? = null, modifiedSec: Long = 0, pending: Boolean = false, trashed: Boolean = false,
) = MediaCandidate(
    id, "content://media/external/images/media/$id", "Screenshot_$id.png", mime, size,
    takenMs ?: (clock.now - ageMs), addedSec ?: ((clock.now - ageMs) / 1000), modifiedSec, folder, pending, trashed,
)

class FakeSource(val clock: FakeClock) : ScreenshotSource {
    class Row(val c: MediaCandidate, var bytes: ByteArray?, var decodable: Boolean = true)

    var access = MediaAccess.FULL
    var throwSecurity = false
    var throwOther = false
    val rows = mutableListOf<Row>()
    val opened = mutableListOf<Long>()

    fun add(id: Long, bytes: ByteArray? = byteArrayOf(id.toByte(), 1, 2, 3), decodable: Boolean = true, c: MediaCandidate = cand(id, clock)) =
        Row(c, bytes, decodable).also { rows += it }

    override fun access() = access
    override fun candidates(folders: List<String>, limit: Int): List<MediaCandidate> {
        if (throwSecurity) throw SecurityException("denied")
        if (throwOther) throw IllegalStateException("boom")
        // Deliberately does NOT filter by folder: the selector must defend itself.
        return rows.map { it.c }.sortedByDescending { it.dateAddedSec }.take(limit)
    }
    override fun open(candidate: MediaCandidate): InputStream? {
        opened += candidate.id
        return rows.firstOrNull { it.c.id == candidate.id }?.bytes?.let { ByteArrayInputStream(it) }
    }
    override fun isDecodable(candidate: MediaCandidate) = rows.firstOrNull { it.c.id == candidate.id }?.decodable == true
}

class FakeLatestProvider(private val source: FakeSource, private val repo: ScreenshotRepository, clock: Clock) : LatestScreenshotProvider {
    val selector = ScreenshotSelector(source, clock)
    var finds = 0
    var route = GalleryRoute.ALBUM
    var plan = TilePlan(verified = false, skip = 0)
    override fun tilePlan(image: LatestImage) = plan
    var newestOverall: Boolean
        get() = route == GalleryRoute.LANDING
        set(v) { route = if (v) GalleryRoute.LANDING else GalleryRoute.ALBUM }
    override fun galleryRoute(image: LatestImage, folders: List<String>) = route
    var failStaging = false
    var hangFind = false
    private var pendingFind: ((SelectionResult) -> Unit)? = null
    fun completeHungFind() { pendingFind?.invoke(selector.selectLatest(listOf("Screenshots"))); pendingFind = null }
    override fun find(settings: AutomationSettings, callback: (SelectionResult) -> Unit) {
        finds++
        if (hangFind) { pendingFind = callback; return }
        callback(selector.selectLatest(settings.screenshotFolders, anyFolder = settings.imageSource == ImageSource.WHATSAPP_GALLERY))
    }
    override fun stage(image: LatestImage, settings: AutomationSettings, callback: (CaptureResult) -> Unit) {
        if (failStaging) { callback(CaptureResult.Failed(FailureReason.STORAGE)); return }
        val fmt = if (image.candidate.mime == "image/png") ImageFormat.PNG else ImageFormat.JPEG
        val part = repo.newTempFile(fmt)
        part.outputStream().use { out -> source.open(image.candidate)!!.use { it.copyTo(out) } }
        callback(CaptureResult.Saved(repo.commit(part, fmt, 1, 1)))
    }
}

/** Rig for the "latest screenshot" workflow. */
class LatestRig(
    tmp: File,
    behavior: SendBehavior = SendBehavior.ONCE_PER_CHAT,
    mode: CaptureMode = CaptureMode.AUTOMATIC,
    fullAuto: Boolean = false,
) {
    val clock = FakeClock()
    val scheduler = FakeScheduler(clock)
    val repo = ScreenshotRepository(File(tmp, "shots"), clock)
    val source = FakeSource(clock)
    val provider = FakeLatestProvider(source, repo, clock)
    val observer = FakeObserver()
    val launcher = FakeLauncher()
    val ledger = InMemoryLedger()
    val salt = ByteArray(16) { 7 }
    var settings = AutomationSettings(imageSource = ImageSource.LATEST_SCREENSHOT, sendBehavior = behavior, mode = mode, fullyAutomaticSend = fullAuto)
    var licensed = true
    private val engine = object : CaptureEngine {
        override fun capture(settings: AutomationSettings, callback: (CaptureResult) -> Unit) = error("a new screenshot must never be captured in latest mode")
    }
    val controller = AutomationController(
        ScreenClassifier(ownPackage = "com.smartmanager.whatsappshoot"), { settings }, engine, repo, observer, launcher, scheduler, clock,
        { licensed }, provider, ledger, salt,
    )
    val state get() = controller.view.state
    val message get() = controller.view.message
    private val hasher = IdentityHasher(salt)

    fun chatKey(title: String) = hasher.identityOf(title).key!!

    /** User opens a chat while WhatsApp stays in front and lets the settle delay pass. */
    fun open(title: String?, outgoing: Int? = null) {
        observer.screen = ScreenClassification(ScreenKind.CONVERSATION, headerText = title, outgoingMediaCount = outgoing)
        controller.onScreen(convSnapshot(title, outgoing))
        scheduler.advance(AutomationSettings.SETTLE_MS + 50)
    }

    fun leaveToChatList() {
        observer.screen = ScreenClassification(ScreenKind.CHAT_LIST)
        controller.onScreen(chatList()); scheduler.advance(10)
    }

    fun convSnapshot(title: String?, outgoing: Int? = null) =
        ScreenSnapshot("com.whatsapp", setOf("conversation_contact_name", "entry"), title, "com.whatsapp.Conversation", outgoing)
}
