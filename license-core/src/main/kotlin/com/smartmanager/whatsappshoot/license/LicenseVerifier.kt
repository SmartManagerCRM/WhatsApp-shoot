package com.smartmanager.whatsappshoot.license

import java.security.PublicKey
import java.security.Signature

enum class LicenseError {
    /** Not a license at all, or structurally damaged. */
    MALFORMED,
    UNSUPPORTED_VERSION,
    BAD_SIGNATURE,
    WRONG_PRODUCT,
    UNSUPPORTED_POLICY,
    UNSUPPORTED_ENTITLEMENT,

    /** Device-bound license presented while this installation has no identity yet. */
    INSTALL_KEY_UNAVAILABLE,

    /** Device-bound license issued for a different installation. */
    WRONG_INSTALLATION,

    /** The verification key compiled into this build is unusable (build not provisioned). */
    PUBLIC_KEY_INVALID,

    /** The license (or this revision of it) was withdrawn by the seller. */
    REVOKED,

    /** A newer revision of this license is already installed. */
    SUPERSEDED,

    /** A term license whose end date has passed. */
    EXPIRED,
}

sealed class VerifyResult {
    data class Valid(val license: LicensePayload) : VerifyResult()
    data class Invalid(val error: LicenseError) : VerifyResult()
}

/**
 * Verifies license tokens against the vendor public key. Holds only the public key:
 * the signing key never exists in the app.
 */
class LicenseVerifier(publicKeyX509: ByteArray) {
    private val publicKey: PublicKey? = LicenseFormat.parseP256PublicKey(publicKeyX509)

    /**
     * @param installFingerprint fingerprint of this installation's key (null if it has none yet);
     *   only consulted for device-bound licenses.
     */
    fun verify(token: String, installFingerprint: String?): VerifyResult {
        val key = publicKey ?: return VerifyResult.Invalid(LicenseError.PUBLIC_KEY_INVALID)
        val (payloadBytes, sig) = LicenseFormat.decodeToken(token)
            ?: return VerifyResult.Invalid(LicenseError.MALFORMED)

        // Signature first: nothing inside an unauthenticated payload is trusted.
        val signatureOk = try {
            Signature.getInstance(LicenseFormat.SIGNATURE_ALGORITHM).run {
                initVerify(key)
                update(LicenseFormat.licenseSigningInput(payloadBytes))
                verify(sig)
            }
        } catch (e: Exception) {
            false
        }
        if (!signatureOk) return VerifyResult.Invalid(LicenseError.BAD_SIGNATURE)

        val p = LicensePayload.parse(payloadBytes) ?: return VerifyResult.Invalid(LicenseError.MALFORMED)
        if (p.version != LicenseFormat.FORMAT_VERSION) return VerifyResult.Invalid(LicenseError.UNSUPPORTED_VERSION)
        if (p.productId != LicenseFormat.PRODUCT_ID) return VerifyResult.Invalid(LicenseError.WRONG_PRODUCT)
        when (p.entitlement) {
            LicenseFormat.ENTITLEMENT_LIFETIME -> if (p.expiresAtEpochSeconds != null) return VerifyResult.Invalid(LicenseError.MALFORMED)
            LicenseFormat.ENTITLEMENT_TERM -> if (p.expiresAtEpochSeconds == null) return VerifyResult.Invalid(LicenseError.MALFORMED)
            else -> return VerifyResult.Invalid(LicenseError.UNSUPPORTED_ENTITLEMENT)
        }
        when (p.policy) {
            LicenseFormat.POLICY_DEVICE_BOUND -> {
                val bind = p.bind ?: return VerifyResult.Invalid(LicenseError.MALFORMED)
                if (installFingerprint == null) return VerifyResult.Invalid(LicenseError.INSTALL_KEY_UNAVAILABLE)
                if (!constantTimeEquals(bind, installFingerprint)) {
                    return VerifyResult.Invalid(LicenseError.WRONG_INSTALLATION)
                }
            }
            LicenseFormat.POLICY_FIRST_USE -> if (p.bind != null) return VerifyResult.Invalid(LicenseError.MALFORMED)
            else -> return VerifyResult.Invalid(LicenseError.UNSUPPORTED_POLICY)
        }
        return VerifyResult.Valid(p)
    }

    /** Verifies a signed administrator command (see [ControlPayload]). */
    fun verifyControl(token: String): ControlVerifyResult {
        val key = publicKey ?: return ControlVerifyResult.Invalid(LicenseError.PUBLIC_KEY_INVALID)
        val (payloadBytes, sig) = LicenseFormat.decodeControl(token) ?: return ControlVerifyResult.Invalid(LicenseError.MALFORMED)
        val ok = try {
            Signature.getInstance(LicenseFormat.SIGNATURE_ALGORITHM).run {
                initVerify(key); update(LicenseFormat.controlSigningInput(payloadBytes)); verify(sig)
            }
        } catch (e: Exception) { false }
        if (!ok) return ControlVerifyResult.Invalid(LicenseError.BAD_SIGNATURE)
        val c = ControlPayload.parse(payloadBytes) ?: return ControlVerifyResult.Invalid(LicenseError.MALFORMED)
        if (c.version != LicenseFormat.FORMAT_VERSION) return ControlVerifyResult.Invalid(LicenseError.UNSUPPORTED_VERSION)
        if (c.productId != LicenseFormat.PRODUCT_ID) return ControlVerifyResult.Invalid(LicenseError.WRONG_PRODUCT)
        if (c.action != LicenseFormat.ACTION_REVOKE) return ControlVerifyResult.Invalid(LicenseError.MALFORMED)
        return ControlVerifyResult.Valid(c)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
