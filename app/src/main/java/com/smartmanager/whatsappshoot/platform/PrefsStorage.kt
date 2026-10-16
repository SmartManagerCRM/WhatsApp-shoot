package com.smartmanager.whatsappshoot.platform

import android.content.Context
import com.smartmanager.whatsappshoot.logic.LedgerStorage
import java.security.SecureRandom
import java.util.Base64

/** Sent-status ledger text, kept in private preferences. Contains only keyed hashes, never names. */
class PrefsLedgerStorage(context: Context) : LedgerStorage {
    private val prefs = context.getSharedPreferences("sent_ledger", Context.MODE_PRIVATE)
    override fun read(): String? = prefs.getString("entries", null)
    override fun write(text: String) { prefs.edit().putString("entries", text).apply() }
}

/**
 * Per-install random key for conversation hashes: makes ledger keys stable across restarts but meaningless on another
 * device, and prevents recovering names from the ledger without also having this key.
 */
object IdentitySalt {
    fun get(context: Context): ByteArray {
        val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
        prefs.getString("salt", null)?.let { s -> try { return Base64.getDecoder().decode(s) } catch (e: IllegalArgumentException) { } }
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString("salt", Base64.getEncoder().encodeToString(salt)).commit()
        return salt
    }
}
