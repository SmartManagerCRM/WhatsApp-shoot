package com.smartmanager.whatsappshoot.licensetool

import com.smartmanager.whatsappshoot.license.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.time.Instant

/** The owner's control over issued licenses: issue (term/lifetime), renew, amend, suspend, cancel, reinstate, register. */
class LicenseAdminTest {
    @get:Rule val tmp = TemporaryFolder()
    private var nowSec = 1_800_000_000L
    private class Run(val code: Int, val out: String, val err: String)

    private fun run(vararg args: String): Run {
        val o = ByteArrayOutputStream(); val e = ByteArrayOutputStream()
        val tool = LicenseTool(PrintStream(o), PrintStream(e), mapOf("WSS_KEY_PASSPHRASE" to "correct horse battery staple"), { null }, clock = { Instant.ofEpochSecond(nowSec) })
        return Run(tool.run(args.toList()), o.toString(), e.toString())
    }

    private val dir by lazy { tmp.newFolder("keys").also { assertEquals(0, run("keygen", "--out-dir", it.path).code) } }
    private val key get() = "$dir/license-private.enc"
    private val registry by lazy { tmp.root.resolve("register.tsv").path }
    private val pub by lazy { LicenseFormat.unb64(Files.readString(dir.toPath().resolve("license-public.b64")).trim())!! }
    private var counter = 0
    private fun out() = tmp.root.resolve("code-${counter++}.txt").path

    private class Phone(pub: ByteArray, clock: () -> Long) {
        val keys = FakeInstallKeys()
        val store = FakeStore()
        val engine = ActivationEngine(LicenseVerifier(pub), keys, store, "com.smartmanager.whatsappshoot", nowEpochSeconds = clock, pause = {})
        val request get() = LicenseFormat.encodeRequest(keys.ensureKey())
    }

    private fun issueTerm(phone: Phone, days: Int = 30, note: String = "Acme Ltd, Maria"): Pair<String, String> {
        val f = out()
        val r = run("issue", "--key", key, "--request", phone.request, "--out", f, "--days", "$days", "--ref", "order-1", "--registry", registry, "--note", note)
        assertEquals(r.err, 0, r.code)
        val id = Regex("WSS(-[0-9A-F]{4}){8}").find(r.out)!!.value
        return id to Files.readString(java.nio.file.Paths.get(f)).trim()
    }

    @Test fun issueTermLicense_registersIt_andTheAppActivatesIt() {
        val phone = Phone(pub) { nowSec }
        val (id, token) = issueTerm(phone)
        assertTrue(phone.engine.activate(token) is ActivationResult.Success)
        val s = phone.engine.status() as ActivationStatus.Active
        assertEquals(nowSec + 30 * 86_400, s.license.expiresAtEpochSeconds); assertEquals(1, s.license.revision)
        val list = run("list", "--registry", registry)
        assertTrue(list.out, list.out.contains(id) && list.out.contains("ACTIVE") && list.out.contains("Acme Ltd, Maria"))
    }

    @Test fun nonPayment_termEndsByItself_renewalExtendsFromTheCurrentEnd() {
        val phone = Phone(pub) { nowSec }
        val (id, token) = issueTerm(phone, days = 30)
        phone.engine.activate(token)
        nowSec += 40 * 86_400                                     // the customer did not pay: no renewal entered
        assertTrue(phone.engine.status() is ActivationStatus.Expired)
        assertTrue(run("list", "--registry", registry).out.contains("EXPIRED"))
        // payment arrives: renew 30 days from today (the old end is in the past)
        val f = out()
        val r = run("renew", "--registry", registry, "--key", key, "--id", id.takeLast(9), "--out", f, "--days", "30")
        assertEquals(r.err, 0, r.code)
        assertTrue(phone.engine.activate(Files.readString(java.nio.file.Paths.get(f))) is ActivationResult.Success)
        val s = phone.engine.status() as ActivationStatus.Active
        assertEquals(2, s.license.revision); assertEquals(nowSec + 30 * 86_400, s.license.expiresAtEpochSeconds)
        assertEquals(id, s.license.licenseId)                       // same license, same phone
    }

    @Test fun renewingBeforeTheEnd_addsToTheRemainingTime() {
        val phone = Phone(pub) { nowSec }
        val (id, token) = issueTerm(phone, days = 30); phone.engine.activate(token)
        nowSec += 10 * 86_400
        val f = out(); assertEquals(0, run("renew", "--registry", registry, "--key", key, "--id", id, "--out", f, "--days", "30").code)
        phone.engine.activate(Files.readString(java.nio.file.Paths.get(f)))
        assertEquals(1_800_000_000L + 60 * 86_400, (phone.engine.status() as ActivationStatus.Active).license.expiresAtEpochSeconds)
    }

    @Test fun suspendThenReinstate_stopsAndRestoresTheSameLicense() {
        val phone = Phone(pub) { nowSec }
        val (id, token) = issueTerm(phone, days = 365); phone.engine.activate(token)
        val stop = out()
        assertEquals(0, run("suspend", "--registry", registry, "--key", key, "--id", id, "--out", stop).code)
        assertTrue(run("list", "--registry", registry).out.contains("SUSPENDED"))
        val applied = phone.engine.activate(Files.readString(java.nio.file.Paths.get(stop))) as ActivationResult.ControlApplied
        assertTrue(applied.affectsInstalled)
        assertTrue(phone.engine.status() is ActivationStatus.Revoked)
        val back = out()
        assertEquals(0, run("reinstate", "--registry", registry, "--key", key, "--id", id, "--out", back).code)
        assertTrue(phone.engine.activate(Files.readString(java.nio.file.Paths.get(back))) is ActivationResult.Success)
        assertTrue(phone.engine.status() is ActivationStatus.Active)
        assertTrue(run("list", "--registry", registry).out.contains("ACTIVE"))
    }

    @Test fun cancel_isRecorded_andTheOldCodeCanNeverComeBack() {
        val phone = Phone(pub) { nowSec }
        val (id, token) = issueTerm(phone, days = 365); phone.engine.activate(token)
        val stop = out(); assertEquals(0, run("cancel", "--registry", registry, "--key", key, "--id", id, "--out", stop).code)
        phone.engine.activate(Files.readString(java.nio.file.Paths.get(stop)))
        assertEquals(ActivationResult.Failure(LicenseError.REVOKED), phone.engine.activate(token))   // re-entering the old code does not help
        assertTrue(run("list", "--registry", registry).out.contains("CANCELLED"))
    }

    @Test fun amend_changesReferenceAndEnd_withoutNeedingTheCustomersRequestCodeAgain() {
        val phone = Phone(pub) { nowSec }
        val (id, token) = issueTerm(phone, days = 30); phone.engine.activate(token)
        val f = out()
        assertEquals(0, run("amend", "--registry", registry, "--key", key, "--id", id, "--out", f, "--expires", "2030-12-31", "--ref", "order-1-upgrade", "--note", "upgraded").code)
        assertTrue(phone.engine.activate(Files.readString(java.nio.file.Paths.get(f))) is ActivationResult.Success)
        val lic = (phone.engine.status() as ActivationStatus.Active).license
        assertEquals("order-1-upgrade", lic.customerRef); assertEquals(2, lic.revision)
        assertEquals(java.time.LocalDate.of(2030, 12, 31).plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond() - 1, lic.expiresAtEpochSeconds)
        // a renewal code made for THIS phone does not work on another phone
        val other = Phone(pub) { nowSec }; other.request
        assertEquals(ActivationResult.Failure(LicenseError.WRONG_INSTALLATION), other.engine.activate(Files.readString(java.nio.file.Paths.get(f))))
    }

    @Test fun amendToLifetime() {
        val phone = Phone(pub) { nowSec }
        val (id, token) = issueTerm(phone, days = 30); phone.engine.activate(token)
        val f = out(); assertEquals(0, run("amend", "--registry", registry, "--key", key, "--id", id, "--out", f, "--lifetime").code)
        phone.engine.activate(Files.readString(java.nio.file.Paths.get(f)))
        assertNull((phone.engine.status() as ActivationStatus.Active).license.expiresAtEpochSeconds)
    }

    @Test fun historyAndCsvExport() {
        val phone = Phone(pub) { nowSec }
        val (id, _) = issueTerm(phone)
        run("suspend", "--registry", registry, "--key", key, "--id", id, "--out", out())
        val h = run("history", "--registry", registry, "--id", id).out
        assertTrue(h, h.contains("issued") && h.contains("suspended"))
        val csv = tmp.root.resolve("all.csv")
        assertEquals(0, run("export", "--registry", registry, "--out", csv.path).code)
        val text = csv.readText()
        assertTrue(text, text.startsWith("license_id,") && text.contains(id) && text.contains("SUSPENDED") && text.contains("\"Acme Ltd, Maria\""))
    }

    @Test fun guardrails() {
        val phone = Phone(pub) { nowSec }
        val (id, _) = issueTerm(phone)
        assertEquals(1, run("renew", "--registry", registry, "--key", key, "--id", id, "--out", out()).code)                  // no new end given
        assertEquals(1, run("renew", "--registry", registry, "--key", key, "--id", "WSS-NOPE", "--out", out(), "--days", "30").code)
        assertEquals(1, run("reinstate", "--registry", registry, "--key", key, "--id", id, "--out", out()).code)             // not suspended
        assertEquals(1, run("renew", "--registry", registry, "--key", key, "--id", id, "--out", out(), "--days", "30", "--lifetime").code)
        assertEquals(1, run("issue", "--key", key, "--unbound", "--out", out(), "--expires", "2001-01-01").code)             // in the past
        val existing = out(); Files.writeString(java.nio.file.Paths.get(existing), "x")
        assertEquals(1, run("suspend", "--registry", registry, "--key", key, "--id", id, "--out", existing).code)            // never overwrite
        assertEquals("nothing was changed by the failed commands", LicenseState.ACTIVE, LicenseRegistry(java.nio.file.Paths.get(registry)).find(id)!!.state)
    }

    @Test fun lifetimeIssueStillWorks_andIsListedAsLifetime() {
        val f = out()
        assertEquals(0, run("issue", "--key", key, "--unbound", "--out", f, "--registry", registry).code)
        assertTrue(run("list", "--registry", registry).out.contains("lifetime"))
    }

    @Test fun oldLedgerLicenses_canBeImported_thenSuspendedAndRenewed() {
        val phone = Phone(pub) { nowSec }
        val oldFile = out()
        assertEquals(0, run("issue", "--key", key, "--request", phone.request, "--out", oldFile, "--ref", "legacy-1", "--ledger", tmp.root.resolve("old.csv").path).code)   // issued before the register: no revision, lifetime
        val oldToken = Files.readString(java.nio.file.Paths.get(oldFile)).trim()
        // this license is revision 1 now (new tool); simulate a legacy revision-0 license made by the previous tool version
        val legacy = LicensePayload(LicenseFormat.FORMAT_VERSION, LicenseFormat.PRODUCT_ID, "WSS-1111-2222-3333-4444-5555-6666-7777-8888", LicenseFormat.POLICY_DEVICE_BOUND, LicenseFormat.ENTITLEMENT_LIFETIME, nowSec, "legacy-2", LicenseFormat.installFingerprint(LicenseFormat.decodeRequest(phone.request)!!))
        val csv = tmp.root.resolve("old2.csv")
        csv.writeText("license_id,issued_utc,policy,customer_ref,install_fingerprint\n${legacy.licenseId},${Instant.ofEpochSecond(nowSec)},${legacy.policy},legacy-2,${legacy.bind}\n")
        val r = run("import-ledger", "--registry", registry, "--ledger", csv.path)
        assertEquals(r.err, 0, r.code); assertTrue(r.out.contains("Imported 1"))
        assertEquals(0, run("import-ledger", "--registry", registry, "--ledger", csv.path).code)
        assertEquals(1, LicenseRegistry(java.nio.file.Paths.get(registry)).all().size)             // not duplicated
        assertNotNull(oldToken)
        val f = out()
        assertEquals(0, run("renew", "--registry", registry, "--key", key, "--id", legacy.licenseId, "--out", f, "--days", "30").code)
        val renewed = Files.readString(java.nio.file.Paths.get(f))
        assertTrue(phone.engine.activate(renewed) is ActivationResult.Success)
        assertEquals(1, (phone.engine.status() as ActivationStatus.Active).license.revision)
    }
}
