package com.smartmanager.whatsappshoot.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automatic offline binding: one license per sale, no request code. The license is bound to the FIRST installation that activates it
 * (an Android-Keystore key pair created on first launch). Because nothing is registered centrally, the same license code can still be
 * activated on a second phone before either phone talks to the owner: this limitation is deliberate, documented and tested below.
 */
class AutomaticBindingTest {
    private val vendor = TestVendor()
    private val appId = "com.smartmanager.whatsappshoot"

    private class Phone(vendor: TestVendor, appId: String) {
        val keys = FakeInstallKeys()
        val store = FakeStore()
        val engine = ActivationEngine(vendor.verifier, keys, store, appId, pause = {})
    }

    private fun phone() = Phone(vendor, appId)
    private fun license() = vendor.token(vendor.payload(LicenseFormat.POLICY_FIRST_USE, revision = 1)) // the generator's default: lifetime, no request code

    @Test fun firstLaunch_createsTheInstallationKey_withoutAnyRequestCode() {
        val p = phone()
        assertNull(p.keys.publicKeyX509())
        assertTrue(p.engine.prepareInstallation())
        assertNotNull(p.keys.publicKeyX509())
        assertEquals(ActivationStatus.NotActivated, p.engine.status())
        val key = p.keys.publicKeyX509()
        assertTrue(p.engine.prepareInstallation()); assertTrue("idempotent: the key is never replaced", key!!.contentEquals(p.keys.publicKeyX509()))
    }

    @Test fun validLicense_activatesImmediately_isBoundLocally_andSurvivesRestartAndUpdate() {
        val p = phone(); p.engine.prepareInstallation()
        assertTrue(p.engine.activate(license()) is ActivationResult.Success)
        val record = p.store.read(0)!!
        assertTrue("the record names this installation's public key", record.contains("installpub=" + LicenseFormat.b64(p.keys.publicKeyX509()!!)))
        assertTrue(p.engine.status() is ActivationStatus.Active)
        val afterUpdate = ActivationEngine(vendor.verifier, p.keys, p.store, appId, pause = {}) // new process / new APK version, same Keystore and data
        assertTrue(afterUpdate.status() is ActivationStatus.Active)
    }

    @Test fun invalidSignature_isRefused_andNothingIsStored() {
        val p = phone(); p.engine.prepareInstallation()
        val forged = vendor.sign(vendor.payload(LicenseFormat.POLICY_FIRST_USE, revision = 1).serialize(), key = newEcKeyPair())
        assertEquals(ActivationResult.Failure(LicenseError.BAD_SIGNATURE), p.engine.activate(forged))
        val tampered = license().let { t -> t.dropLast(6) + (if (t.last() == 'A') "BBBBBB" else "AAAAAA") }
        assertTrue(p.engine.activate(tampered) is ActivationResult.Failure)
        assertEquals(ActivationStatus.NotActivated, p.engine.status())
        assertNull(p.store.read(0))
    }

    @Test fun copiedLicenseCode_canBeActivatedOnASecondPhone_documentedLimitation() {
        val code = license()
        val first = phone(); val second = phone()
        assertTrue(first.engine.activate(code) is ActivationResult.Success)
        // No server knows the code was used: the second phone accepts it too. Offline licensing cannot prevent this.
        assertTrue(second.engine.activate(code) is ActivationResult.Success)
        assertTrue(first.engine.status() is ActivationStatus.Active); assertTrue(second.engine.status() is ActivationStatus.Active)
    }

    @Test fun copiedActivationRecord_ofAFirstUseLicense_works_sameAsEnteringTheCopiedCode_documentedLimitation() {
        val original = phone(); original.engine.activate(license())
        val clone = phone(); clone.engine.prepareInstallation()
        for (s in 0 until ActivationStore.SLOTS) clone.store.write(s, original.store.read(s)!!)
        // The same code can be activated on any phone anyway (see copiedLicenseCode_canBeActivatedOnASecondPhone), so the record is not refused.
        assertTrue(clone.engine.status() is ActivationStatus.Active)
    }

    @Test fun keystoreEntryLost_doesNotMakeTheCustomerEnterTheLicenseAgain() {
        val code = license()
        val p = phone(); p.engine.activate(code)
        p.keys.deleteKey()                                                                  // Keystore entry gone (OEM wipe, update ...), record still stored
        assertTrue(p.engine.status() is ActivationStatus.Active)
        assertTrue(p.engine.prepareInstallation())                                          // next launch creates a new installation key
        assertTrue(p.engine.status() is ActivationStatus.Active)                            // old record names the old key: still accepted
    }

    @Test fun storedRecordDamaged_afterKeystoreLoss_isStillDetected() {
        val p = phone(); p.engine.activate(license())
        p.keys.deleteKey()
        for (s in 0 until ActivationStore.SLOTS) p.store.write(s, p.store.read(s)!!.replace("at=", "at=9"))
        assertEquals(ActivationStatus.RecordCorrupt, p.engine.status())
    }

    @Test fun phoneLockedLicense_staysStrict_whenTheKeyIsMissing() {
        val p = phone(); p.engine.prepareInstallation()
        val fp = LicenseFormat.installFingerprint(p.keys.publicKeyX509()!!)
        val locked = vendor.token(vendor.payload(LicenseFormat.POLICY_DEVICE_BOUND, bind = fp, revision = 1))
        assertTrue(p.engine.activate(locked) is ActivationResult.Success)
        p.keys.deleteKey()
        assertEquals(ActivationStatus.InstallKeyMissing, p.engine.status())
    }

    @Test fun corruptedActivationData_isDetected_oneBadSlotIsTolerated() {
        val p = phone(); p.engine.activate(license())
        p.store.write(0, "garbage")
        assertTrue("slot 1 still valid", p.engine.status() is ActivationStatus.Active)
        p.store.write(1, "rv=1\ntoken=x\ninstallpub=y\nat=1\nsig=z\n")
        assertEquals(ActivationStatus.RecordCorrupt, p.engine.status())
        assertTrue("re-entering the license repairs it", p.engine.activate(license()) is ActivationResult.Success)
        assertTrue(p.engine.status() is ActivationStatus.Active)
    }

    @Test fun editedRecord_isDetected() {
        val p = phone(); p.engine.activate(license())
        val edited = p.store.read(0)!!.replace(Regex("at=\\d+"), "at=1")
        for (s in 0 until ActivationStore.SLOTS) p.store.write(s, edited)
        assertEquals(ActivationStatus.RecordCorrupt, p.engine.status())
    }

    @Test fun keystoreUnusable_isReported_noActivationHappens() {
        val p = phone(); p.keys.failCreation = true
        assertFalse(p.engine.prepareInstallation())
        assertEquals(ActivationResult.KeystoreUnavailable, p.engine.activate(license()))
        assertEquals(ActivationStatus.NotActivated, p.engine.status())
    }

    @Test fun phoneLockedLicensesStillExist_forThoseWhoWantStrictBinding() {
        val p = phone()
        val fp = LicenseFormat.installFingerprint(LicenseFormat.decodeRequest(p.engine.requestCode()!!)!!)
        val locked = vendor.token(vendor.payload(LicenseFormat.POLICY_DEVICE_BOUND, bind = fp, revision = 1))
        assertTrue(p.engine.activate(locked) is ActivationResult.Success)
        assertEquals(ActivationResult.Failure(LicenseError.WRONG_INSTALLATION), phone().also { it.engine.prepareInstallation() }.engine.activate(locked))
    }
}
