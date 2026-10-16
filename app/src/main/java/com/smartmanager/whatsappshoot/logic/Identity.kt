package com.smartmanager.whatsappshoot.logic

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale

/**
 * Short-lived, in-memory representation of "which conversation was on screen". Only a salted hash of
 * the visible header text is kept - never the text - and the salt is per-workflow, so the value cannot be
 * correlated across workflows or recovered from memory dumps by simple lookup. It is NOT persisted or logged.
 *
 * Limitation (documented in docs/WHATSAPP_COMPATIBILITY.md): two chats can display the same title, and
 * accessibility text is not a unique identifier. A matching fingerprint therefore means "probably the same",
 * and the user always confirms the recipient in WhatsApp before sending.
 */
class ConversationIdentity internal constructor(private val fingerprint: String?) {
    val isKnown: Boolean get() = fingerprint != null

    /** Opaque, keyed hash usable as a ledger key; null when the conversation could not be identified reliably. */
    val key: String? get() = fingerprint
    fun matches(other: ConversationIdentity?): Boolean = fingerprint != null && other?.fingerprint == fingerprint
    override fun toString() = if (fingerprint == null) "ConversationIdentity(unknown)" else "ConversationIdentity(known)"
    override fun equals(other: Any?) = other is ConversationIdentity && other.fingerprint == fingerprint
    override fun hashCode() = fingerprint.hashCode()
}

/** The salt is secret key material for the keyed hash; a per-install salt makes ledger keys stable across restarts. */
class IdentityHasher(private val salt: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }) {
    fun identityOf(headerText: String?): ConversationIdentity = ConversationIdentity(fingerprintOf(headerText))

    fun fingerprintOf(text: String?): String? {
        val norm = normalize(text) ?: return null
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        return md.digest(norm.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun normalize(text: String?): String? {
            val t = text?.let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
                ?.replace(Regex("\\s+"), " ")?.trim()?.lowercase(Locale.ROOT)
            // A title shorter than 2 characters is not a usable identity (placeholders, icons, partial renders).
            return if (t == null || t.length < 2) null else t
        }
    }
}

enum class DestinationCheck { VERIFIED, CHANGED, UNCERTAIN }

object DestinationVerifier {
    /**
     * @param expected identity captured with the screenshot
     * @param current  what is on screen now (null if nothing observable, e.g. WhatsApp not in front)
     */
    fun check(expected: ConversationIdentity?, current: ScreenClassification?, hasher: IdentityHasher): DestinationCheck {
        if (expected == null || !expected.isKnown) return DestinationCheck.UNCERTAIN
        if (current == null || !current.isWhatsApp) return DestinationCheck.UNCERTAIN
        if (current.kind != ScreenKind.CONVERSATION) return DestinationCheck.CHANGED
        val now = hasher.identityOf(current.headerText)
        if (!now.isKnown) return DestinationCheck.UNCERTAIN
        return if (expected.matches(now)) DestinationCheck.VERIFIED else DestinationCheck.CHANGED
    }
}

/** Picks the row to tap in WhatsApp's share list, only when exactly one row matches. */
object ShareTargetSelector {
    sealed class Choice {
        data class Single(val index: Int) : Choice()
        object None : Choice()
        object Ambiguous : Choice()
    }

    fun choose(candidateTitles: List<String?>, expected: ConversationIdentity?, hasher: IdentityHasher): Choice {
        if (expected == null || !expected.isKnown) return Choice.None
        val hits = candidateTitles.mapIndexedNotNull { i, t -> if (expected.matches(hasher.identityOf(t))) i else null }
        return when (hits.size) {
            0 -> Choice.None
            1 -> Choice.Single(hits[0])
            else -> Choice.Ambiguous
        }
    }
}
