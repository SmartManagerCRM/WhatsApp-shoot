package com.smartmanager.whatsappshoot.logic

enum class SendBehavior {
    /** A: prepare the latest screenshot whenever a new conversation is opened. */
    EVERY_CHAT_OPEN,

    /** B: once per identifiable conversation until the user resets its status. */
    ONCE_PER_CHAT,

    /** C: only when the latest screenshot has not been handled for this conversation (and is newer than the last one). */
    NEW_SCREENSHOT_ONLY,
}

enum class LedgerStatus { PREPARED, SENT_UNCONFIRMED, SENT_CONFIRMED }

/** Record of what was handed to which conversation. Keys are opaque hashes; no names are stored. */
interface SentLedger {
    fun chatHasAny(chat: String): Boolean
    fun hasHandled(chat: String, image: String): Boolean
    fun newestImageDateMs(chat: String): Long?
    fun record(chat: String, image: String, imageDateMs: Long, status: LedgerStatus, atMs: Long)
    fun resetChat(chat: String)
    fun resetAll()
    fun size(): Int
}

enum class SkipReason { ALREADY_HANDLED_CHAT, SAME_IMAGE_ALREADY_HANDLED, NOT_NEWER }
enum class ManualReason { IDENTITY_UNRELIABLE }

sealed class SendDecision {
    object Proceed : SendDecision()
    data class Skip(val reason: SkipReason) : SendDecision()
    data class NeedManual(val reason: ManualReason) : SendDecision()
}

object SendPolicy {
    /**
     * @param chat identity of the conversation that is open; unreliable/unknown identities never get an automatic
     *   decision in modes B and C (different chats must not be treated as the same chat).
     */
    fun decide(behavior: SendBehavior, chat: ConversationIdentity?, image: LatestImage, ledger: SentLedger): SendDecision {
        if (behavior == SendBehavior.EVERY_CHAT_OPEN) return SendDecision.Proceed
        val key = chat?.key ?: return SendDecision.NeedManual(ManualReason.IDENTITY_UNRELIABLE)
        return when (behavior) {
            SendBehavior.ONCE_PER_CHAT ->
                if (ledger.chatHasAny(key)) SendDecision.Skip(SkipReason.ALREADY_HANDLED_CHAT) else SendDecision.Proceed
            SendBehavior.NEW_SCREENSHOT_ONLY -> when {
                ledger.hasHandled(key, image.key) -> SendDecision.Skip(SkipReason.SAME_IMAGE_ALREADY_HANDLED)
                ledger.newestImageDateMs(key)?.let { image.dateMs <= it } == true -> SendDecision.Skip(SkipReason.NOT_NEWER)
                else -> SendDecision.Proceed
            }
            SendBehavior.EVERY_CHAT_OPEN -> SendDecision.Proceed
        }
    }
}

/** Simple in-memory ledger (tests, and the base of the persistent one). */
open class InMemoryLedger(private val capacity: Int = 500) : SentLedger {
    protected class Entry(val chat: String, val image: String, val imageDateMs: Long, var status: LedgerStatus, val atMs: Long)

    protected val entries = ArrayList<Entry>()

    override fun chatHasAny(chat: String) = entries.any { it.chat == chat }
    override fun hasHandled(chat: String, image: String) = entries.any { it.chat == chat && it.image == image }
    override fun newestImageDateMs(chat: String) = entries.filter { it.chat == chat }.maxOfOrNull { it.imageDateMs }
    override fun size() = entries.size

    override fun record(chat: String, image: String, imageDateMs: Long, status: LedgerStatus, atMs: Long) {
        val existing = entries.firstOrNull { it.chat == chat && it.image == image }
        if (existing != null) { if (status.ordinal > existing.status.ordinal) existing.status = status } else entries += Entry(chat, image, imageDateMs, status, atMs)
        while (entries.size > capacity) entries.removeAt(0) // oldest first
        changed()
    }

    override fun resetChat(chat: String) { entries.removeAll { it.chat == chat }; changed() }
    override fun resetAll() { entries.clear(); changed() }
    protected open fun changed() {}
}

/** Ledger persisted as text lines through [storage]; capped, newest kept. */
class PersistentLedger(private val storage: LedgerStorage, capacity: Int = 500) : InMemoryLedger(capacity) {
    init {
        storage.read()?.lines()?.forEach { line ->
            val p = line.split(',')
            if (p.size == 5) {
                val st = LedgerStatus.values().firstOrNull { it.name == p[3] }
                val d = p[2].toLongOrNull(); val t = p[4].toLongOrNull()
                if (st != null && d != null && t != null && p[0].isNotEmpty() && p[1].isNotEmpty()) entries += Entry(p[0], p[1], d, st, t)
            }
        }
    }

    override fun changed() {
        storage.write(entries.joinToString("\n") { "${it.chat},${it.image},${it.imageDateMs},${it.status.name},${it.atMs}" })
    }
}

interface LedgerStorage {
    fun read(): String?
    fun write(text: String)
}
