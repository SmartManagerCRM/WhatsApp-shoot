package com.smartmanager.whatsappshoot.license

import org.junit.Assert.*
import org.junit.Test

class ActivationEngineTest {
    private val vendor = TestVendor()
    private val appId = "com.smartmanager.whatsappshoot"

    private class Phone(vendor: TestVendor, appId: String) {
        val keys = FakeInstallKeys()
        val store = FakeStore()
        val engine = ActivationEngine(vendor.verifier, keys, store, appId)
    }

    private fun phone() = Phone(vendor, appId)

    private fun boundTokenFor(p: Phone): String {
        val fp = LicenseFormat.installFingerprint(LicenseFormat.decodeRequest(p.engine.requestCode()!!)!!)
        return vendor.token(vendor.payload(LicenseFormat.POLICY_DEVICE_BOUND, bind = fp))
    }

    @Test fun missingActivationData_isNotActivated() {
        assertEquals(ActivationStatus.NotActivated, phone().engine.status())
    }

    @Test fun firstUseLicenseActivatesAndSurvivesRestart() {
        val p = phone()
        val r = p.engine.activate(vendor.token())
        assertTrue(r is ActivationResult.Success)
        assertTrue(p.engine.status() is ActivationStatus.Active)
        // "Application updated normally": new engine instance over the same keys + store.
        val afterUpdate = ActivationEngine(vendor.verifier, p.keys, p.store, appId)
        assertTrue(afterUpdate.status() is ActivationStatus.Active)
    }

    @Test fun deviceBoundActivationFlow() {
        val p = phone()
        val token = boundTokenFor(p)
        assertTrue(p.engine.activate(token) is ActivationResult.Success)
        assertTrue(p.engine.status() is ActivationStatus.Active)
    }

    @Test fun deviceBoundLicenseRejectedOnAnotherPhone() {
        val original = phone(); val token = boundTokenFor(original)
        val copy = phone(); copy.engine.requestCode()          // different installation key
        val r = copy.engine.activate(token)
        assertEquals(ActivationResult.Failure(LicenseError.WRONG_INSTALLATION), r)
        // and a phone that never created a key at all
        val fresh = phone()
        assertEquals(ActivationResult.Failure(LicenseError.INSTALL_KEY_UNAVAILABLE), fresh.engine.activate(token))
        assertNull(fresh.keys.publicKeyX509()) // failed activation has no side effects
    }

    @Test fun apkAndActivationRecordCopiedToAnotherInstallation_isRejected() {
        val original = phone(); original.engine.activate(vendor.token())
        val clone = phone()                                   // own (different) Keystore, no key for the record
        for (s in 0 until ActivationStore.SLOTS) clone.store.write(s, original.store.read(s)!!)
        assertEquals(ActivationStatus.InstallKeyMissing, clone.engine.status())
        clone.keys.ensureKey()                                // attacker creates own key: still mismatched
        assertEquals(ActivationStatus.InstallKeyMissing, clone.engine.status())
    }

    @Test fun missingInstallationKey_afterReinstallOrKeystoreWipe() {
        val p = phone(); p.engine.activate(vendor.token())
        p.keys.deleteKey()
        assertEquals(ActivationStatus.InstallKeyMissing, p.engine.status())
    }

    @Test fun reinstall_losesBothKeyAndRecord_needsReactivation() {
        val p = phone(); val token = boundTokenFor(p)
        p.engine.activate(token)
        p.keys.deleteKey(); p.store.clear()                   // uninstall wipes data and Keystore key
        assertEquals(ActivationStatus.NotActivated, p.engine.status())
        // The old device-bound license does not work with the new installation key: seller must re-issue.
        p.engine.requestCode()
        assertEquals(ActivationResult.Failure(LicenseError.WRONG_INSTALLATION), p.engine.activate(token))
    }

    @Test fun deviceReplaced_oldBoundLicenseUnusableOnNewDevice() {
        val oldPhone = phone(); val token = boundTokenFor(oldPhone)
        val newPhone = phone(); newPhone.engine.requestCode()
        assertTrue(newPhone.engine.activate(token) is ActivationResult.Failure)
        // Recovery: seller issues a fresh license against the new phone's request code.
        val reissued = boundTokenFor(newPhone)
        assertTrue(newPhone.engine.activate(reissued) is ActivationResult.Success)
    }

    @Test fun corruptedPrimaryRecord_fallsBackToBackupSlot() {
        val p = phone(); p.engine.activate(vendor.token())
        p.store.slots[0] = "rv=1\ntoken=zzz\n"
        assertTrue(p.engine.status() is ActivationStatus.Active)
    }

    @Test fun corruptedRecordInAllSlots() {
        val p = phone(); p.engine.activate(vendor.token())
        p.store.slots[0] = "not a record"
        p.store.slots[1] = "\u0000\u0001garbage"
        assertEquals(ActivationStatus.RecordCorrupt, p.engine.status())
    }

    @Test fun editedRecordFieldsAreDetected() {
        val p = phone(); p.engine.activate(vendor.token())
        val edited = p.store.slots[0]!!.replace(Regex("at=\\d+"), "at=1")
        p.store.slots[0] = edited; p.store.slots[1] = edited
        assertEquals(ActivationStatus.RecordCorrupt, p.engine.status())
    }

    @Test fun replacingTokenInRecordWithAnotherValidLicenseIsDetected() {
        val p = phone(); p.engine.activate(vendor.token())
        val other = vendor.token()
        val edited = p.store.slots[0]!!.lines().joinToString("\n") { if (it.startsWith("token=")) "token=$other" else it }
        p.store.slots[0] = edited; p.store.slots[1] = edited
        assertEquals(ActivationStatus.RecordCorrupt, p.engine.status())
    }

    @Test fun invalidLicenseIsNeverStored() {
        val p = phone()
        val bad = vendor.sign(vendor.payload().serialize(), newEcKeyPair())
        assertEquals(ActivationResult.Failure(LicenseError.BAD_SIGNATURE), p.engine.activate(bad))
        assertEquals(ActivationStatus.NotActivated, p.engine.status())
        assertNull(p.store.slots[0])
        assertNull(p.keys.publicKeyX509())
    }

    @Test fun keystoreFailureAndStorageFailureAreReported() {
        val p = phone(); p.keys.failCreation = true
        assertEquals(ActivationResult.KeystoreUnavailable, p.engine.activate(vendor.token()))
        assertNull(p.engine.requestCode())
        val q = phone(); q.store.failWrites = true
        assertEquals(ActivationResult.StorageFailed, q.engine.activate(vendor.token()))
    }

    @Test fun recordFromDifferentAppIdFailsIntegrity() {
        val p = phone(); p.engine.activate(vendor.token())
        val other = ActivationEngine(vendor.verifier, p.keys, p.store, "com.evil.clone")
        assertEquals(ActivationStatus.RecordCorrupt, other.status())
    }

    @Test fun clearActivation() {
        val p = phone(); p.engine.activate(vendor.token()); p.engine.clearActivation()
        assertEquals(ActivationStatus.NotActivated, p.engine.status())
    }
}
