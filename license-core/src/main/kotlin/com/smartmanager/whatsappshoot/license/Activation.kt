package com.smartmanager.whatsappshoot.license

import java.security.SecureRandom
import java.security.Signature

/**
 * The installation's own asymmetric identity. On Android this is an Android Keystore EC P-256 key
 * whose private half never leaves secure hardware / the Keystore process.
 */
interface InstallKeyProvider {
    /** X.509 public key, or null if this installation has no key (yet / any more). */
    fun publicKeyX509(): ByteArray?

    /** Creates the key if absent and returns its X.509 public key. Throws [InstallKeyException] on failure. */
    fun ensureKey(): ByteArray

    /** Signs with the private key; null if the key is missing/unusable. */
    fun sign(data: ByteArray): ByteArray?

    fun deleteKey()
}

class InstallKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Two redundant slots protect against a single corrupted write. */
interface ActivationStore {
    fun read(slot: Int): String?
    fun write(slot: Int, value: String)
    fun clear()

    /** Administrator revocations received so far, one `licenseId,highestRevokedRevision` per line. */
    fun readControls(): String? = null
    fun writeControls(value: String) {}

    /** Highest wall-clock time (epoch seconds) ever seen: a phone clock set back cannot revive an expired license. */
    fun readTimeMark(): Long = 0L
    fun writeTimeMark(value: Long) {}

    companion object {
        const val SLOTS = 2
    }
}

sealed class ActivationStatus {
    object NotActivated : ActivationStatus()
    data class Active(val license: LicensePayload) : ActivationStatus()

    /** A term license whose end date has passed (renew with a new code from the seller). */
    data class Expired(val license: LicensePayload) : ActivationStatus()

    /** The seller withdrew this license (suspended / cancelled). */
    data class Revoked(val license: LicensePayload) : ActivationStatus()

    /** Stored activation data is unreadable or fails its integrity check. */
    object RecordCorrupt : ActivationStatus()

    /** Record exists but this installation's key is missing/different (e.g. data copied to another phone). */
    object InstallKeyMissing : ActivationStatus()
    data class LicenseInvalid(val error: LicenseError) : ActivationStatus()
}

sealed class ActivationResult {
    data class Success(val license: LicensePayload) : ActivationResult()
    data class Failure(val error: LicenseError) : ActivationResult()
    object KeystoreUnavailable : ActivationResult()
    object StorageFailed : ActivationResult()

    /** An administrator command was accepted. [affectsInstalled] is true when it stops the license installed on this phone. */
    data class ControlApplied(val licenseId: String, val revokedUpTo: Int, val affectsInstalled: Boolean) : ActivationResult()
}

/**
 * Offline, installation-bound activation (see docs/LICENSING.md for the security model and its limits).
 *
 * Activation record = license token + installation public key + activation time, signed by the
 * installation key. On every check the engine also proves *possession* of that key by signing a fresh
 * random challenge, so a record copied to another phone (which lacks the Keystore key) is rejected.
 */
class ActivationEngine(
    private val verifier: LicenseVerifier,
    private val keys: InstallKeyProvider,
    private val store: ActivationStore,
    private val appId: String,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val random: SecureRandom = SecureRandom(),
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    /** Short, content-free reason of the last status() result (for diagnostics only). */
    @Volatile var lastDetail: String = ""
        private set

    /** Creates this installation's identity (if needed) and returns the code to send to the seller. */
    fun requestCode(): String? = try {
        LicenseFormat.encodeRequest(keys.ensureKey())
    } catch (e: InstallKeyException) {
        null
    }

    /**
     * First launch: creates this installation's key pair (Android Keystore) if it does not exist yet, so that a license entered later is bound
     * without the customer ever creating or sending a request code. Safe to call on every start. Returns false if the Keystore is unusable.
     */
    fun prepareInstallation(): Boolean = try {
        keys.ensureKey(); true
    } catch (e: InstallKeyException) {
        false
    }

    fun activate(token: String): ActivationResult {
        val token1 = token.filter { !it.isWhitespace() }
        if (token1.startsWith(LicenseFormat.CONTROL_PREFIX + ".")) return applyControl(token1)
        // Verify before creating any key, so garbage input has no side effects. For a device-bound license
        // the key must already exist (created by requestCode()).
        val existing = keys.publicKeyX509()
        val pre = verifier.verify(token1, existing?.let(LicenseFormat::installFingerprint))
        val license = when (pre) {
            is VerifyResult.Valid -> pre.license
            is VerifyResult.Invalid -> return ActivationResult.Failure(pre.error)
        }
        // Withdrawn / superseded / already ended licenses are refused before anything is stored.
        val revokedUpTo = revoked()[license.licenseId]
        if (revokedUpTo != null && license.revision <= revokedUpTo) return ActivationResult.Failure(LicenseError.REVOKED)
        val expiry = license.expiresAtEpochSeconds
        if (expiry != null && effectiveNow() > expiry) return ActivationResult.Failure(LicenseError.EXPIRED)
        installedLicense()?.let { cur -> if (cur.licenseId == license.licenseId && license.revision < cur.revision) return ActivationResult.Failure(LicenseError.SUPERSEDED) }
        val pub = try {
            keys.ensureKey()
        } catch (e: InstallKeyException) {
            return ActivationResult.KeystoreUnavailable
        }
        val record = buildRecord(token1, license.licenseId, pub) ?: return ActivationResult.KeystoreUnavailable
        return try {
            for (slot in 0 until ActivationStore.SLOTS) store.write(slot, record)
            ActivationResult.Success(license)
        } catch (e: Exception) {
            ActivationResult.StorageFailed
        }
    }

    /** Evaluates the installed license, then applies administrator revocations and the end date of term licenses. */
    fun status(): ActivationStatus {
        val base = baseStatus()
        if (base !is ActivationStatus.Active) return base
        val lic = base.license
        revoked()[lic.licenseId]?.let { upTo -> if (lic.revision <= upTo) { lastDetail = "revoked by the seller"; return ActivationStatus.Revoked(lic) } }
        val exp = lic.expiresAtEpochSeconds
        if (exp != null && effectiveNow() > exp) { lastDetail = "term ended"; return ActivationStatus.Expired(lic) }
        return base
    }

    /** The license currently installed (whatever its state), or null. */
    private fun installedLicense(): LicensePayload? = when (val s = baseStatus()) {
        is ActivationStatus.Active -> s.license
        else -> null
    }

    private fun applyControl(token: String): ActivationResult {
        val c = when (val r = verifier.verifyControl(token)) {
            is ControlVerifyResult.Valid -> r.control
            is ControlVerifyResult.Invalid -> return ActivationResult.Failure(r.error)
        }
        val map = revoked().toMutableMap()
        val old = map[c.licenseId] ?: -1
        if (c.revision > old) map[c.licenseId] = c.revision
        return try {
            store.writeControls(map.entries.joinToString("\n") { "${it.key},${it.value}" })
            val installed = installedLicense()
            ActivationResult.ControlApplied(c.licenseId, c.revision, installed != null && installed.licenseId == c.licenseId && installed.revision <= c.revision)
        } catch (e: Exception) {
            ActivationResult.StorageFailed
        }
    }

    private fun revoked(): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (line in store.readControls().orEmpty().split('\n')) {
            val i = line.indexOf(',')
            if (i <= 0) continue
            val rev = line.substring(i + 1).toIntOrNull() ?: continue
            out[line.substring(0, i)] = rev
        }
        return out
    }

    /** Wall-clock time that cannot go backwards: setting the phone clock back does not give an expired license new life. */
    private fun effectiveNow(): Long {
        val real = nowEpochSeconds()
        val mark = store.readTimeMark()
        if (real > mark) { if (real - mark >= 60) try { store.writeTimeMark(real) } catch (e: Exception) { }; return real }
        return mark
    }

    private fun baseStatus(): ActivationStatus {
        lastDetail = ""
        var sawRecord = false
        var firstFailure: ActivationStatus? = null
        for (slot in 0 until ActivationStore.SLOTS) {
            val raw = store.read(slot) ?: continue
            sawRecord = true
            val result = check(raw)
            if (result is ActivationStatus.Active) return result
            if (firstFailure == null) firstFailure = result
        }
        return if (!sawRecord) ActivationStatus.NotActivated else firstFailure ?: ActivationStatus.RecordCorrupt
    }

    /** Removes the local activation record (the Keystore key is kept so a bound license can be re-imported). */
    fun clearActivation() = store.clear()

    // ---- internals ----

    private fun buildRecord(token: String, licenseId: String, pub: ByteArray): String? {
        val at = nowEpochSeconds()
        val pubB64 = LicenseFormat.b64(pub)
        val sig = keys.sign(recordSigningInput(licenseId, pubB64, at)) ?: return null
        return "rv=1\ntoken=$token\ninstallpub=$pubB64\nat=$at\nsig=${LicenseFormat.b64(sig)}\n"
    }

    private fun recordSigningInput(licenseId: String, pubB64: String, at: Long): ByteArray =
        (LicenseFormat.ACTIVATION_DOMAIN + "$licenseId\n$pubB64\n$at\n$appId").toByteArray(Charsets.UTF_8)

    private fun check(raw: String): ActivationStatus {
        val fields = HashMap<String, String>()
        for (line in raw.split('\n')) {
            if (line.isEmpty()) continue
            val eq = line.indexOf('=')
            if (eq <= 0) return ActivationStatus.RecordCorrupt
            if (fields.put(line.substring(0, eq), line.substring(eq + 1)) != null) return ActivationStatus.RecordCorrupt
        }
        if (fields["rv"] != "1") return ActivationStatus.RecordCorrupt
        val token = fields["token"] ?: return ActivationStatus.RecordCorrupt
        val pubB64 = fields["installpub"] ?: return ActivationStatus.RecordCorrupt
        val at = fields["at"]?.toLongOrNull() ?: return ActivationStatus.RecordCorrupt
        val sig = fields["sig"]?.let(LicenseFormat::unb64) ?: return ActivationStatus.RecordCorrupt
        val recordPub = LicenseFormat.unb64(pubB64) ?: return ActivationStatus.RecordCorrupt
        val recordKey = LicenseFormat.parseP256PublicKey(recordPub) ?: return ActivationStatus.RecordCorrupt

        // 1. The license itself must still verify (signature, product, policy, binding to the recorded key).
        val license = when (val r = verifier.verify(token, LicenseFormat.installFingerprint(recordPub))) {
            is VerifyResult.Valid -> r.license
            is VerifyResult.Invalid -> return ActivationStatus.LicenseInvalid(r.error)
        }
        // 2. The record must be signed by the key it names (detects edits / bit rot).
        if (!verifySig(recordKey, recordSigningInput(license.licenseId, pubB64, at), sig)) {
            return ActivationStatus.RecordCorrupt
        }
        // 3. This installation must own that key right now (detects data copied to another phone, reinstall).
        var current = keys.publicKeyX509()
        var lookups = 0
        while (current == null && lookups < SIGN_RETRIES) { pause(SIGN_RETRY_PAUSE_MS); current = keys.publicKeyX509(); lookups++ } // Keystore can be briefly unreadable
        if (current == null) { lastDetail = "installation key not found"; return ActivationStatus.InstallKeyMissing }
        if (!current.contentEquals(recordPub)) { lastDetail = "installation key differs from the recorded one"; return ActivationStatus.InstallKeyMissing }
        val nonce = ByteArray(32).also { random.nextBytes(it) }
        val challenge = LicenseFormat.CHALLENGE_DOMAIN.toByteArray(Charsets.UTF_8) + nonce
        // The Keystore can be briefly unavailable (device just booted, system busy): retry before drawing any conclusion.
        var proof = keys.sign(challenge)
        var tries = 0
        while (proof == null && tries < SIGN_RETRIES) { pause(SIGN_RETRY_PAUSE_MS); proof = keys.sign(challenge); tries++ }
        if (proof == null) {
            // The Keystore holds an entry with exactly the recorded public key, so this installation owns it (it cannot exist on another
            // phone). Not being able to sign right now must not throw a paying customer's license away.
            lastDetail = "key present, signing unavailable"
            return ActivationStatus.Active(license)
        }
        if (!verifySig(recordKey, challenge, proof)) { lastDetail = "possession proof failed"; return ActivationStatus.InstallKeyMissing }
        return ActivationStatus.Active(license)
    }

    private fun verifySig(key: java.security.PublicKey, data: ByteArray, sig: ByteArray): Boolean = try {
        Signature.getInstance(LicenseFormat.SIGNATURE_ALGORITHM).run {
            initVerify(key)
            update(data)
            verify(sig)
        }
    } catch (e: Exception) {
        false
    }

    companion object {
        const val SIGN_RETRIES = 3
        const val SIGN_RETRY_PAUSE_MS = 300L
    }
}
