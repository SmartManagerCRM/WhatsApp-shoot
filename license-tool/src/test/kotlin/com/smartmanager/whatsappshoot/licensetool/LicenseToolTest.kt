package com.smartmanager.whatsappshoot.licensetool

import com.smartmanager.whatsappshoot.license.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files

class LicenseToolTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Run(val code: Int, val out: String, val err: String)

    private fun run(vararg args: String, pass: String? = "correct horse battery staple", env: Map<String, String> = emptyMap()): Run {
        val o = ByteArrayOutputStream(); val e = ByteArrayOutputStream()
        val tool = LicenseTool(PrintStream(o), PrintStream(e), env, { pass?.toCharArray() })
        val code = tool.run(args.toList())
        return Run(code, o.toString(), e.toString())
    }

    private fun keygen(): java.io.File {
        val dir = tmp.newFolder("keys")
        // delete the dir contents path so keygen creates files fresh
        val r = run("keygen", "--out-dir", dir.path)
        assertEquals(r.err, 0, r.code)
        return dir
    }

    @Test fun keygenThenIssueThenVerify_deviceBound() {
        val dir = keygen()
        val keys = FakeInstallKeys()
        val request = LicenseFormat.encodeRequest(keys.ensureKey())
        val out = tmp.root.resolve("lic.txt")
        val ledger = tmp.root.resolve("issued.csv")
        val r = run("issue", "--key", "$dir/license-private.enc", "--request", request, "--out", out.path, "--ref", "order-77", "--ledger", ledger.path)
        assertEquals(r.err, 0, r.code)
        assertTrue(r.out.contains("License ID"))

        val v = run("verify", "--pub", "$dir/license-public.b64", "--license", out.path, "--request", request)
        assertEquals(v.err, 0, v.code)
        assertTrue(v.out.startsWith("VALID"))
        assertTrue(ledger.readText().contains("order-77"))

        // The generated license really activates in the app engine, using the generated public key.
        val pub = LicenseFormat.unb64(Files.readString(dir.toPath().resolve("license-public.b64")).trim())!!
        val engine = ActivationEngine(LicenseVerifier(pub), keys, FakeStore(), "com.smartmanager.whatsappshoot")
        assertTrue(engine.activate(out.readText()) is ActivationResult.Success)
        assertTrue(engine.status() is ActivationStatus.Active)

        // ...and verifying against a *different* installation fails (exit 3).
        val other = LicenseFormat.encodeRequest(FakeInstallKeys().ensureKey())
        val bad = run("verify", "--pub", "$dir/license-public.b64", "--license", out.path, "--request", other)
        assertEquals(3, bad.code)
    }

    @Test fun issueUnboundWarnsAboutWeakerBinding() {
        val dir = keygen(); val out = tmp.root.resolve("u.txt")
        val r = run("issue", "--key", "$dir/license-private.enc", "--unbound", "--out", out.path)
        assertEquals(0, r.code)
        assertTrue(r.out.contains("NOT tied to a device"))
    }

    @Test fun privateKeyUnavailable() {
        val r = run("issue", "--key", tmp.root.resolve("missing.enc").path, "--unbound", "--out", tmp.root.resolve("x").path)
        assertEquals(2, r.code)
        assertTrue(r.err.contains("unavailable"))
    }

    @Test fun wrongPassphraseIsRejectedWithoutLeakingAnything() {
        val dir = keygen()
        val r = run("issue", "--key", "$dir/license-private.enc", "--unbound", "--out", tmp.root.resolve("y").path, pass = "not the passphrase!")
        assertEquals(2, r.code)
        assertFalse(tmp.root.resolve("y").exists())
    }

    @Test fun passphraseFromEnvironmentAndNoTerminal() {
        val dir = tmp.newFolder("k2")
        val ok = run("keygen", "--out-dir", dir.path, pass = null, env = mapOf("WSS_KEY_PASSPHRASE" to "a-long-enough-passphrase"))
        assertEquals(ok.err, 0, ok.code)
        val none = run("issue", "--key", "$dir/license-private.enc", "--unbound", "--out", tmp.root.resolve("z").path, pass = null)
        assertEquals(1, none.code)
    }

    @Test fun shortPassphraseRejectedAtKeygen() {
        assertEquals(1, run("keygen", "--out-dir", tmp.newFolder("k3").path, pass = "short").code)
    }

    @Test fun nothingSecretIsEverPrinted() {
        val dir = keygen()
        val enc = Files.readString(dir.toPath().resolve("license-private.enc"))
        val r = run("keygen", "--out-dir", tmp.newFolder("k4").path)
        val all = r.out + r.err
        assertFalse(all.contains("PRIVATE KEY"))
        assertFalse(all.contains(enc.lines()[1].take(20)))
    }

    @Test fun keygenRefusesToOverwrite() {
        val dir = keygen()
        assertEquals(2, run("keygen", "--out-dir", dir.path).code)
    }

    @Test fun malformedInputsAreReported() {
        val dir = keygen()
        val garbage = tmp.root.resolve("g.txt").apply { writeText("not a license") }
        val v = run("verify", "--pub", "$dir/license-public.b64", "--license", garbage.path)
        assertEquals(3, v.code); assertTrue(v.err.contains("MALFORMED"))
        val badReq = run("issue", "--key", "$dir/license-private.enc", "--request", "WSR1.bad.bad", "--out", tmp.root.resolve("q").path)
        assertEquals(1, badReq.code)
        assertEquals(1, run("issue", "--key", "$dir/license-private.enc", "--out", tmp.root.resolve("q2").path).code) // neither mode
        assertEquals(1, run("issue", "--key", "k", "--unbound", "--request", "x", "--out", "o").code)               // both
        assertEquals(1, run("nonsense").code)
        assertEquals(1, run("issue", "--key", "$dir/license-private.enc", "--unbound", "--ref", "john smith@mail.com", "--out", tmp.root.resolve("q3").path).code)
    }

    @Test fun eachLicenseGetsAUniqueId() {
        val dir = keygen()
        val ids = (1..5).map {
            val r = run("issue", "--key", "$dir/license-private.enc", "--unbound", "--out", tmp.root.resolve("l$it").path)
            r.out.lines().first { l -> l.startsWith("License ID") }
        }
        assertEquals(5, ids.toSet().size)
    }
}
