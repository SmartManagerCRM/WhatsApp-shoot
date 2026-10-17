package com.smartmanager.whatsappshoot.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseControlTest {
    private val vendor = TestVendor()
    private val id = "WSS-0001-0002-0003-0004-0005-0006-0007-0008"
    private var now = 1_800_000_000L

    private class Phone(vendor: TestVendor, clock: () -> Long) {
        val keys = FakeInstallKeys()
        val store = FakeStore()
        val engine = ActivationEngine(vendor.verifier, keys, store, "com.smartmanager.whatsappshoot", nowEpochSeconds = clock, pause = {})
    }

    private fun phone() = Phone(vendor) { now }
    private fun term(rev: Int, expires: Long) = vendor.token(vendor.payload(id = id, entitlement = LicenseFormat.ENTITLEMENT_TERM, expires = expires, revision = rev))
    private fun lifetime(rev: Int) = vendor.token(vendor.payload(id = id, revision = rev))

    // ---- format ----

    @Test fun payloadRoundTrip_withExpiryAndRevision_andOldLicensesStillParse() {
        val p = vendor.payload(id = id, entitlement = LicenseFormat.ENTITLEMENT_TERM, expires = 1_900_000_000L, revision = 3)
        val parsed = LicensePayload.parse(p.serialize())!!
        assertEquals(1_900_000_000L, parsed.expiresAtEpochSeconds); assertEquals(3, parsed.revision)
        val old = LicensePayload.parse(vendor.payload(id = id).serialize())!!      // no expires / rev keys at all
        assertNull(old.expiresAtEpochSeconds); assertEquals(0, old.revision)
        assertTrue(String(vendor.payload(id = id).serialize()).let { !it.contains("expires") && !it.contains("rev=") })
    }

    @Test fun termNeedsExpiry_lifetimeMustNotHaveOne() {
        val noExpiry = vendor.token(vendor.payload(id = id, entitlement = LicenseFormat.ENTITLEMENT_TERM))
        assertEquals(VerifyResult.Invalid(LicenseError.MALFORMED), vendor.verifier.verify(noExpiry, null))
        val lifetimeWithExpiry = vendor.token(vendor.payload(id = id, expires = 1_900_000_000L))
        assertEquals(VerifyResult.Invalid(LicenseError.MALFORMED), vendor.verifier.verify(lifetimeWithExpiry, null))
    }

    @Test fun controlCode_verifies_andRejectsForgeryAndOtherDomains() {
        val good = vendor.control(id, 2)
        val r = vendor.verifier.verifyControl(good) as ControlVerifyResult.Valid
        assertEquals(id, r.control.licenseId); assertEquals(2, r.control.revision)
        val attacker = newEcKeyPair()
        assertEquals(ControlVerifyResult.Invalid(LicenseError.BAD_SIGNATURE), vendor.verifier.verifyControl(vendor.control(id, 2, key = attacker)))
        // a license token is not a control code and vice versa
        assertEquals(ControlVerifyResult.Invalid(LicenseError.MALFORMED), vendor.verifier.verifyControl(lifetime(1)))
        assertEquals(VerifyResult.Invalid(LicenseError.MALFORMED), vendor.verifier.verify(good, null))
        assertEquals(ControlVerifyResult.Invalid(LicenseError.MALFORMED), vendor.verifier.verifyControl(vendor.control(id, 2, action = "delete")))
    }

    // ---- term licenses ----

    @Test fun termLicense_isActiveUntilItsEndDate_thenExpired_andRenewalRevivesIt() {
        val p = phone()
        assertTrue(p.engine.activate(term(1, now + 10 * 86_400)) is ActivationResult.Success)
        assertTrue(p.engine.status() is ActivationStatus.Active)
        now += 11 * 86_400
        assertTrue(p.engine.status() is ActivationStatus.Expired)
        // renewal: same id, higher revision, later end date
        assertTrue(p.engine.activate(term(2, now + 30 * 86_400)) is ActivationResult.Success)
        val s = p.engine.status() as ActivationStatus.Active
        assertEquals(2, s.license.revision)
    }

    @Test fun settingTheClockBack_doesNotReviveAnExpiredLicense() {
        val p = phone(); p.engine.activate(term(1, now + 5 * 86_400))
        now += 6 * 86_400
        assertTrue(p.engine.status() is ActivationStatus.Expired)     // records the latest time seen
        now -= 20 * 86_400                                            // user turns the clock back
        assertTrue(p.engine.status() is ActivationStatus.Expired)
    }

    @Test fun anAlreadyEndedLicenseCannotBeActivated() {
        val p = phone()
        assertEquals(ActivationResult.Failure(LicenseError.EXPIRED), p.engine.activate(term(1, now - 1)))
        assertEquals(ActivationStatus.NotActivated, p.engine.status())
    }

    @Test fun olderRevisionCannotReplaceANewerOne() {
        val p = phone(); p.engine.activate(term(3, now + 86_400))
        assertEquals(ActivationResult.Failure(LicenseError.SUPERSEDED), p.engine.activate(term(2, now + 99 * 86_400)))
        assertEquals(3, (p.engine.status() as ActivationStatus.Active).license.revision)
    }

    @Test fun amendedLicense_changesExpiryOnTheSameInstallation() {
        val p = phone(); p.engine.activate(term(1, now + 86_400))
        assertTrue(p.engine.activate(term(2, now + 400 * 86_400)) is ActivationResult.Success)
        assertEquals(now + 400 * 86_400, (p.engine.status() as ActivationStatus.Active).license.expiresAtEpochSeconds)
        // and can be turned into a lifetime license with a higher revision
        assertTrue(p.engine.activate(lifetime(3)) is ActivationResult.Success)
        assertNull((p.engine.status() as ActivationStatus.Active).license.expiresAtEpochSeconds)
    }

    // ---- suspend / cancel / reinstate ----

    @Test fun revocationStopsTheInstalledLicense_andReinstatementWithAHigherRevisionRestoresIt() {
        val p = phone(); p.engine.activate(lifetime(1))
        val r = p.engine.activate(vendor.control(id, 1)) as ActivationResult.ControlApplied
        assertTrue(r.affectsInstalled)
        assertTrue(p.engine.status() is ActivationStatus.Revoked)
        // the same revision cannot be re-activated
        assertEquals(ActivationResult.Failure(LicenseError.REVOKED), p.engine.activate(lifetime(1)))
        // reinstatement = a new revision
        assertTrue(p.engine.activate(lifetime(2)) is ActivationResult.Success)
        assertTrue(p.engine.status() is ActivationStatus.Active)
    }

    @Test fun revocationSurvivesRestart() {
        val p = phone(); p.engine.activate(lifetime(1)); p.engine.activate(vendor.control(id, 1))
        val restarted = ActivationEngine(vendor.verifier, p.keys, p.store, "com.smartmanager.whatsappshoot", nowEpochSeconds = { now }, pause = {})
        assertTrue(restarted.status() is ActivationStatus.Revoked)
    }

    @Test fun revocationOfAnotherLicenseDoesNotAffectThisOne() {
        val p = phone(); p.engine.activate(lifetime(1))
        val other = "WSS-AAAA-BBBB-CCCC-DDDD-EEEE-FFFF-0000-1111"
        val r = p.engine.activate(vendor.control(other, 5)) as ActivationResult.ControlApplied
        assertEquals(false, r.affectsInstalled)
        assertTrue(p.engine.status() is ActivationStatus.Active)
    }

    @Test fun aControlCodeIsStoredEvenBeforeTheLicenseArrives_soASuspendedLicenseStaysRefused() {
        val p = phone()
        p.engine.activate(vendor.control(id, 4))
        assertEquals(ActivationResult.Failure(LicenseError.REVOKED), p.engine.activate(term(4, now + 86_400)))
        assertTrue(p.engine.activate(term(5, now + 86_400)) is ActivationResult.Success)
    }

    @Test fun forgedControlCode_isRefusedAndChangesNothing() {
        val p = phone(); p.engine.activate(lifetime(1))
        val r = p.engine.activate(vendor.control(id, 9, key = newEcKeyPair()))
        assertEquals(ActivationResult.Failure(LicenseError.BAD_SIGNATURE), r)
        assertTrue(p.engine.status() is ActivationStatus.Active)
        assertNull(p.store.controls)
    }

    @Test fun licensesIssuedBeforeRevisionsExisted_areRevisionZero_andCanBeRevokedByRevisionZeroOrMore() {
        val p = phone(); p.engine.activate(vendor.token(vendor.payload(id = id)))   // revision 0
        p.engine.activate(vendor.control(id, 0))
        assertTrue(p.engine.status() is ActivationStatus.Revoked)
        assertNotNull(p.store.controls)
    }
}
