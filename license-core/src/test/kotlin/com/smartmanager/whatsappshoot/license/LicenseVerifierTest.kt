package com.smartmanager.whatsappshoot.license

import org.junit.Assert.*
import org.junit.Test

class LicenseVerifierTest {
    private val vendor = TestVendor()

    private fun err(r: VerifyResult) = (r as? VerifyResult.Invalid)?.error

    @Test fun validLifetimeFirstUseLicense() {
        val r = vendor.verifier.verify(vendor.token(), null)
        assertTrue(r is VerifyResult.Valid)
        val p = (r as VerifyResult.Valid).license
        assertEquals("lifetime", p.entitlement)
        assertEquals(LicenseFormat.POLICY_FIRST_USE, p.policy)
    }

    @Test fun invalidSignature_wrongVendorKey() {
        val forged = vendor.sign(vendor.payload().serialize(), newEcKeyPair())
        assertEquals(LicenseError.BAD_SIGNATURE, err(vendor.verifier.verify(forged, null)))
    }

    @Test fun modifiedPayloadIsRejected() {
        val bytes = vendor.payload().serialize()
        val token = vendor.sign(bytes)
        val (payload, sig) = LicenseFormat.decodeToken(token)!!
        val tampered = String(payload).replace("order-1", "order-2").toByteArray()
        val bad = LicenseFormat.encodeToken(tampered, sig)
        assertEquals(LicenseError.BAD_SIGNATURE, err(vendor.verifier.verify(bad, null)))
    }

    @Test fun wrongProductIdentifier() {
        val t = vendor.token(vendor.payload(product = "com.other.app"))
        assertEquals(LicenseError.WRONG_PRODUCT, err(vendor.verifier.verify(t, null)))
    }

    @Test fun wrongVersionAndEntitlementAndPolicy() {
        assertEquals(LicenseError.UNSUPPORTED_VERSION, err(vendor.verifier.verify(vendor.token(vendor.payload(version = 2)), null)))
        assertEquals(LicenseError.UNSUPPORTED_ENTITLEMENT, err(vendor.verifier.verify(vendor.token(vendor.payload(entitlement = "monthly")), null)))
        assertEquals(LicenseError.UNSUPPORTED_POLICY, err(vendor.verifier.verify(vendor.token(vendor.payload(policy = "site")), null)))
    }

    @Test fun missingAndMalformedInput() {
        for (bad in listOf("", "   ", "garbage", "WSS1.", "WSS1.a.b", "WSS1.!!!.???", "WSS2.AAAA.AAAA", "WSS1.AAAA.AAAA.AAAA")) {
            assertEquals("input '$bad'", LicenseError.MALFORMED, err(vendor.verifier.verify(bad, null)))
        }
    }

    @Test fun malformedPayloadEvenWithValidSignatureIsRejected() {
        // Signed by the real vendor but structurally invalid (unknown key) -> must not be accepted.
        val t = vendor.sign("v=1\nbogus=1\n".toByteArray())
        assertEquals(LicenseError.MALFORMED, err(vendor.verifier.verify(t, null)))
        val t2 = vendor.sign("v=1\nproduct=x\n".toByteArray()) // missing fields
        assertEquals(LicenseError.MALFORMED, err(vendor.verifier.verify(t2, null)))
    }

    @Test fun whitespaceAndLineWrappingInTokenIsTolerated() {
        val t = vendor.token()
        val wrapped = "  " + t.chunked(40).joinToString("\r\n") + "\n"
        assertTrue(vendor.verifier.verify(wrapped, null) is VerifyResult.Valid)
    }

    @Test fun deviceBoundLicenseOnlyValidForItsInstallation() {
        val keys = FakeInstallKeys(); val other = FakeInstallKeys()
        val fp = LicenseFormat.installFingerprint(keys.ensureKey())
        other.ensureKey()
        val t = vendor.token(vendor.payload(LicenseFormat.POLICY_DEVICE_BOUND, bind = fp))
        assertTrue(vendor.verifier.verify(t, fp) is VerifyResult.Valid)
        assertEquals(LicenseError.WRONG_INSTALLATION, err(vendor.verifier.verify(t, LicenseFormat.installFingerprint(other.publicKeyX509()!!))))
        assertEquals(LicenseError.INSTALL_KEY_UNAVAILABLE, err(vendor.verifier.verify(t, null)))
    }

    @Test fun deviceBoundWithoutBindFieldIsMalformed() {
        val t = vendor.token(vendor.payload(LicenseFormat.POLICY_DEVICE_BOUND, bind = null))
        assertEquals(LicenseError.MALFORMED, err(vendor.verifier.verify(t, "x")))
    }

    @Test fun invalidAppPublicKeyFailsClosed() {
        val v = LicenseVerifier(ByteArray(10))
        assertEquals(LicenseError.PUBLIC_KEY_INVALID, err(v.verify(vendor.token(), null)))
        val placeholder = LicenseVerifier(ByteArray(0))
        assertEquals(LicenseError.PUBLIC_KEY_INVALID, err(placeholder.verify(vendor.token(), null)))
    }

    @Test fun requestCodeRoundTripAndTypoDetection() {
        val pub = FakeInstallKeys().ensureKey()
        val code = LicenseFormat.encodeRequest(pub)
        assertArrayEquals(pub, LicenseFormat.decodeRequest(code))
        val i = code.indexOf('.') + 5
        val typo = code.substring(0, i) + (if (code[i] == 'A') 'B' else 'A') + code.substring(i + 1)
        assertNull(LicenseFormat.decodeRequest(typo))
        assertNull(LicenseFormat.decodeRequest("WSR1.AAAA.AAAAAA"))
        assertNull(LicenseFormat.decodeRequest(""))
    }

    @Test fun maskedIdDoesNotRevealMiddleGroups() {
        val id = "WSS-AAAA-BBBB-CCCC-DDDD-EEEE-FFFF-1234"
        val masked = LicensePayload.maskLicenseId(id)
        assertEquals("WSS-AAAA-…-1234", masked)
        assertFalse(masked.contains("CCCC"))
    }
}
