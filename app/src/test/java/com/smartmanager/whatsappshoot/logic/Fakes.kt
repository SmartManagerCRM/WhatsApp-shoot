package com.smartmanager.whatsappshoot.logic

import java.io.File

class FakeClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMs() = now
    fun advance(ms: Long) { now += ms }
}

class FakeScheduler(private val clock: FakeClock) : Scheduler {
    private class Task(val at: Long, val block: () -> Unit, var cancelled: Boolean = false)
    private val tasks = mutableListOf<Task>()
    val pendingCount get() = tasks.count { !it.cancelled }
    override fun schedule(delayMs: Long, block: () -> Unit): Cancellable {
        val t = Task(clock.now + delayMs, block); tasks += t
        return Cancellable { t.cancelled = true }
    }
    /** Advances time, running due tasks in order. */
    fun advance(ms: Long) {
        val target = clock.now + ms
        while (true) {
            val next = tasks.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
            clock.now = maxOf(clock.now, next.at); tasks.remove(next); next.block()
        }
        clock.now = target
    }
}

class FakeEngine(private val repo: ScreenshotRepository, private val clock: Clock) : CaptureEngine {
    var calls = 0
    var next: (() -> CaptureResult)? = null           // default: save a small file
    private var waiting: ((CaptureResult) -> Unit)? = null
    var deferred = false                              // when true, result is delivered by complete()
    private var deferredResult: CaptureResult? = null

    override fun capture(settings: AutomationSettings, callback: (CaptureResult) -> Unit) {
        calls++
        val result = next?.invoke() ?: save(settings.format)
        if (deferred) { waiting = callback; deferredResult = result } else callback(result)
    }
    fun complete() { waiting!!.invoke(deferredResult!!); waiting = null }

    private fun save(fmt: ImageFormat): CaptureResult {
        val part = repo.newTempFile(fmt); part.writeBytes(ByteArray(100) { 1 })
        return CaptureResult.Saved(repo.commit(part, fmt, 1080, 2400))
    }
}

class FakeObserver : ScreenObserver {
    var screen: ScreenClassification? = null
    override fun currentScreen() = screen
}

class FakeLauncher : AttachmentLauncher {
    var calls = 0
    var lastDirect: Boolean? = null
    var result: LaunchResult = LaunchResult.Launched(directSelectPending = false)
    override fun launchShare(shot: StoredScreenshot, expected: ConversationIdentity?, hasher: IdentityHasher, tryDirectSelect: Boolean): LaunchResult {
        calls++; lastDirect = tryDirectSelect; return result
    }
}

fun wa(vararg ids: String, cls: String? = null, header: String? = null, pkg: String = "com.whatsapp") =
    ScreenSnapshot(pkg, ids.toSet(), header, cls)

fun conversation(title: String? = "Alice", pkg: String = "com.whatsapp", group: Boolean = false) =
    wa("conversation_contact_name", "entry", *(if (group) arrayOf("conversation_contact_status_group") else emptyArray()),
        cls = "com.whatsapp.Conversation", header = title, pkg = pkg)

fun chatList() = wa("conversations_row_contact_name", cls = "com.whatsapp.HomeActivity")
fun otherApp() = ScreenSnapshot("com.example.browser")

class Rig(val tmp: File, settings: AutomationSettings = AutomationSettings(imageSource = ImageSource.CAPTURE_NEW, mode = CaptureMode.AUTOMATIC), var licensed: Boolean = true) {
    val clock = FakeClock()
    val scheduler = FakeScheduler(clock)
    val repo = ScreenshotRepository(File(tmp, "shots"), clock)
    val engine = FakeEngine(repo, clock)
    val observer = FakeObserver()
    val launcher = FakeLauncher()
    var settings = settings
    val controller = AutomationController(
        ScreenClassifier(ownPackage = "com.smartmanager.whatsappshoot"),
        { this.settings }, engine, repo, observer, launcher, scheduler, clock, { licensed },
    )
    val state get() = controller.view.state
    val message get() = controller.view.message
    fun shots() = repo.list().size
}
