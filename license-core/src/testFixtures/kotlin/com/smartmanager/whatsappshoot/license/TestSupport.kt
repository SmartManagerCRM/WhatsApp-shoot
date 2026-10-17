package com.smartmanager.whatsappshoot.license

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** JVM stand-ins for the Android Keystore and SharedPreferences. */
class FakeInstallKeys : InstallKeyProvider {
    private var pair: KeyPair? = null
    var failCreation = false
    /** Number of upcoming sign() calls that return null (Keystore briefly busy); Int.MAX_VALUE = permanently. */
    var signFailures = 0
    override fun publicKeyX509(): ByteArray? = pair?.public?.encoded
    override fun ensureKey(): ByteArray {
        if (failCreation) throw InstallKeyException("keystore down")
        if (pair == null) pair = newEcKeyPair()
        return pair!!.public.encoded
    }
    override fun sign(data: ByteArray): ByteArray? = if (signFailures > 0) { signFailures--; null } else pair?.let {
        Signature.getInstance(LicenseFormat.SIGNATURE_ALGORITHM).run { initSign(it.private); update(data); sign() }
    }
    override fun deleteKey() { pair = null }
}

class FakeStore : ActivationStore {
    val slots = arrayOfNulls<String>(ActivationStore.SLOTS)
    var failWrites = false
    override fun read(slot: Int) = slots[slot]
    override fun write(slot: Int, value: String) { if (failWrites) throw java.io.IOException("disk full"); slots[slot] = value }
    override fun clear() { slots.fill(null); controls = null; mark = 0L }
    var controls: String? = null
    var mark = 0L
    override fun readControls() = controls
    override fun writeControls(value: String) { controls = value }
    override fun readTimeMark() = mark
    override fun writeTimeMark(value: Long) { mark = value }
}

fun newEcKeyPair(): KeyPair =
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

/** A throw-away vendor, to issue licenses in tests (the real signing key is never in the repo). */
class TestVendor(val pair: KeyPair = newEcKeyPair()) {
    val verifier = LicenseVerifier(pair.public.encoded)
    private val rnd = SecureRandom()

    fun payload(
        policy: String = LicenseFormat.POLICY_FIRST_USE,
        product: String = LicenseFormat.PRODUCT_ID,
        bind: String? = null,
        id: String = "WSS-" + (1..8).joinToString("-") { "%04X".format(rnd.nextInt(0x10000)) },
        entitlement: String = LicenseFormat.ENTITLEMENT_LIFETIME,
        version: Int = LicenseFormat.FORMAT_VERSION,
        ref: String? = "order-1",
        expires: Long? = null,
        revision: Int = 0,
    ) = LicensePayload(version, product, id, policy, entitlement, 1_700_000_000L, ref, bind, expires, revision)

    fun control(licenseId: String, revision: Int, action: String = LicenseFormat.ACTION_REVOKE, key: KeyPair = pair): String {
        val bytes = ControlPayload(LicenseFormat.FORMAT_VERSION, LicenseFormat.PRODUCT_ID, action, licenseId, revision, 1_700_000_100L).serialize()
        val sig = Signature.getInstance(LicenseFormat.SIGNATURE_ALGORITHM).run { initSign(key.private); update(LicenseFormat.controlSigningInput(bytes)); sign() }
        return LicenseFormat.encodeControl(bytes, sig)
    }

    fun sign(bytes: ByteArray, key: KeyPair = pair): String {
        val sig = Signature.getInstance(LicenseFormat.SIGNATURE_ALGORITHM).run {
            initSign(key.private); update(LicenseFormat.licenseSigningInput(bytes)); sign()
        }
        return LicenseFormat.encodeToken(bytes, sig)
    }

    fun token(p: LicensePayload = payload()) = sign(p.serialize())
}
