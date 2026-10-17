package com.smartmanager.whatsappshoot.license

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Constants and codecs for the Screenshot Assistant license format (version 1).
 * See docs/LICENSE_FORMAT.md for the normative description.
 *
 * Pure JVM: shared by the Android app (verification) and the owner's license tool (issuing).
 */
object LicenseFormat {
    const val FORMAT_VERSION = 1
    const val PRODUCT_ID = "com.smartmanager.whatsappshoot.lifetime"

    const val TOKEN_PREFIX = "WSS1"
    const val REQUEST_PREFIX = "WSR1"

    /** Signed administrator command (suspend / cancel): WSC1.<payload>.<signature> */
    const val CONTROL_PREFIX = "WSC1"
    const val ACTION_REVOKE = "revoke"

    const val POLICY_DEVICE_BOUND = "device-bound"
    const val POLICY_FIRST_USE = "first-use"
    const val ENTITLEMENT_LIFETIME = "lifetime"
    const val ENTITLEMENT_TERM = "term"

    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    const val KEY_ALGORITHM = "EC"

    /** Domain-separation prefixes so a signature made for one purpose can never validate for another. */
    const val LICENSE_DOMAIN = "WSS-LICENSE-v1\n"
    const val ACTIVATION_DOMAIN = "WSS-ACTIVATION-v1\n"
    const val CHALLENGE_DOMAIN = "WSS-CHALLENGE-v1\n"
    const val CONTROL_DOMAIN = "WSS-CONTROL-v1\n"

    private val b64Enc = Base64.getUrlEncoder().withoutPadding()
    private val b64Dec = Base64.getUrlDecoder()

    fun b64(bytes: ByteArray): String = b64Enc.encodeToString(bytes)

    /** Strict decode: returns null for anything that is not canonical unpadded base64url. */
    fun unb64(text: String): ByteArray? = try {
        val bytes = b64Dec.decode(text)
        if (b64Enc.encodeToString(bytes) == text) bytes else null
    } catch (e: IllegalArgumentException) {
        null
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** Fingerprint of an installation public key (X.509 DER), as stored in a device-bound license. */
    fun installFingerprint(publicKeyX509: ByteArray): String = b64(sha256(publicKeyX509))

    /** Bytes that are actually signed for a license payload. */
    fun licenseSigningInput(payload: ByteArray): ByteArray = LICENSE_DOMAIN.toByteArray(Charsets.UTF_8) + payload

    /** Parses an X.509 encoded EC P-256 public key; null if it is anything else. */
    fun parseP256PublicKey(x509: ByteArray): PublicKey? = try {
        val key = KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(x509))
        if (key is ECPublicKey && key.params.curve.field.fieldSize == 256) key else null
    } catch (e: Exception) {
        null
    }

    /** Short checksum used only to catch typing/copy errors in activation request codes. */
    private fun checksum(data: ByteArray): String = b64(sha256(data)).take(6)

    /** Activation request code shown to the customer: WSR1.<pubkey>.<checksum> */
    fun encodeRequest(installPublicKeyX509: ByteArray): String =
        "$REQUEST_PREFIX.${b64(installPublicKeyX509)}.${checksum(installPublicKeyX509)}"

    /** Returns the installation public key (X.509) from a request code, or null if malformed. */
    fun decodeRequest(request: String): ByteArray? {
        val parts = request.trim().split('.')
        if (parts.size != 3 || parts[0] != REQUEST_PREFIX) return null
        val key = unb64(parts[1]) ?: return null
        if (parts[2] != checksum(key)) return null
        if (parseP256PublicKey(key) == null) return null
        return key
    }

    /** Assembles a token from an already-signed payload. */
    fun encodeToken(payload: ByteArray, signatureDer: ByteArray): String =
        "$TOKEN_PREFIX.${b64(payload)}.${b64(signatureDer)}"

    fun controlSigningInput(payload: ByteArray): ByteArray = CONTROL_DOMAIN.toByteArray(Charsets.UTF_8) + payload

    fun encodeControl(payload: ByteArray, signatureDer: ByteArray): String =
        "$CONTROL_PREFIX.${b64(payload)}.${b64(signatureDer)}"

    fun decodeControl(token: String): Pair<ByteArray, ByteArray>? = decodeToken(token, CONTROL_PREFIX)

    /** Splits a token into (payload, signature) or null if the structure is wrong. */
    fun decodeToken(token: String, prefix: String = TOKEN_PREFIX): Pair<ByteArray, ByteArray>? {
        val cleaned = token.filter { !it.isWhitespace() }
        val parts = cleaned.split('.')
        if (parts.size != 3 || parts[0] != prefix) return null
        val payload = unb64(parts[1]) ?: return null
        val sig = unb64(parts[2]) ?: return null
        if (payload.isEmpty() || payload.size > MAX_PAYLOAD_BYTES || sig.isEmpty() || sig.size > 80) return null
        return payload to sig
    }

    const val MAX_PAYLOAD_BYTES = 1024
}
