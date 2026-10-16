package com.smartmanager.whatsappshoot.licensetool

import com.smartmanager.whatsappshoot.license.LicenseFormat
import com.smartmanager.whatsappshoot.license.LicensePayload
import com.smartmanager.whatsappshoot.license.LicenseVerifier
import com.smartmanager.whatsappshoot.license.VerifyResult
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.time.Instant

/**
 * Owner-operated license utility. Runs entirely offline. Never prints key material.
 *
 * Exit codes: 0 ok, 1 usage/input error, 2 key problem, 3 license invalid.
 */
class LicenseTool(
    private val out: PrintStream,
    private val err: PrintStream,
    private val env: Map<String, String>,
    private val readPassphrase: (prompt: String) -> CharArray?,
    private val clock: () -> Instant = { Instant.now() },
    private val random: SecureRandom = SecureRandom(),
) {
    fun run(args: List<String>): Int {
        if (args.isEmpty() || args[0] in setOf("-h", "--help", "help")) {
            out.println(USAGE)
            return if (args.isEmpty()) 1 else 0
        }
        val opts = try {
            parseOptions(args.drop(1))
        } catch (e: IllegalArgumentException) {
            err.println("error: ${e.message}")
            return 1
        }
        return try {
            when (args[0]) {
                "keygen" -> keygen(opts)
                "issue" -> issue(opts)
                "verify" -> verify(opts)
                "inspect-request" -> inspectRequest(opts)
                else -> {
                    err.println("error: unknown command '${args[0]}'\n$USAGE")
                    1
                }
            }
        } catch (e: KeyFileException) {
            err.println("error: ${e.message}")
            2
        } catch (e: java.nio.file.NoSuchFileException) {
            err.println("error: file not found: ${e.file}")
            1
        } catch (e: java.io.IOException) {
            err.println("error: ${e.message}")
            1
        }
    }

    // ---- commands ----

    private fun keygen(o: Map<String, String>): Int {
        val dir = Paths.get(o["out-dir"] ?: return missing("--out-dir"))
        Files.createDirectories(dir)
        val priv = dir.resolve("license-private.enc")
        val pub = dir.resolve("license-public.b64")
        if (Files.exists(priv) || Files.exists(pub)) throw KeyFileException("Key files already exist in $dir; refusing to overwrite.")
        val pass = passphrase(confirm = true) ?: return 1
        val kp = PrivateKeyFile.generateKeyPair()
        PrivateKeyFile.writeSecret(priv, PrivateKeyFile.encrypt(kp.privateKeyPkcs8, pass))
        Files.write(pub, (LicenseFormat.b64(kp.publicKeyX509) + "\n").toByteArray())
        java.util.Arrays.fill(kp.privateKeyPkcs8, 0)
        pass.fill('0')
        out.println("Signing key (encrypted): $priv")
        out.println("Public key (embed in app): $pub")
        out.println("Public key fingerprint: ${LicenseFormat.installFingerprint(kp.publicKeyX509).take(16)}")
        out.println()
        out.println("NEXT: back up $priv AND remember the passphrase (see docs/KEY_MANAGEMENT.md).")
        out.println("      Losing either means you can never issue licenses for this app build again.")
        return 0
    }

    private fun issue(o: Map<String, String>): Int {
        val keyPath = Paths.get(o["key"] ?: return missing("--key"))
        val outPath = Paths.get(o["out"] ?: return missing("--out"))
        val request = o["request"]
        val unbound = o.containsKey("unbound")
        if ((request == null) == !unbound) {
            err.println("error: give exactly one of --request <code> (device-bound, recommended) or --unbound")
            return 1
        }
        val ref = o["ref"]
        if (ref != null && !Regex("[A-Za-z0-9._:\\-]{1,64}").matches(ref)) {
            err.println("error: --ref may only contain letters, digits and . _ : - (max 64); do not put personal data here")
            return 1
        }
        if (Files.exists(outPath)) {
            err.println("error: output file exists: $outPath")
            return 1
        }
        val bind: String? = if (request != null) {
            val pub = LicenseFormat.decodeRequest(request)
            if (pub == null) {
                err.println("error: activation request code is malformed (check for typos or truncation)")
                return 1
            }
            LicenseFormat.installFingerprint(pub)
        } else null

        if (!Files.isRegularFile(keyPath)) throw KeyFileException("Signing key file not found: $keyPath (private key unavailable)")
        val keyText = String(Files.readAllBytes(keyPath))
        val pass = passphrase(confirm = false) ?: return 1
        val privateKey: PrivateKey = PrivateKeyFile.decrypt(keyText, pass)
        pass.fill('0')

        val payload = LicensePayload(
            version = LicenseFormat.FORMAT_VERSION,
            productId = LicenseFormat.PRODUCT_ID,
            licenseId = newLicenseId(),
            policy = if (bind != null) LicenseFormat.POLICY_DEVICE_BOUND else LicenseFormat.POLICY_FIRST_USE,
            entitlement = LicenseFormat.ENTITLEMENT_LIFETIME,
            issuedAtEpochSeconds = clock().epochSecond,
            customerRef = ref,
            bind = bind,
        )
        val bytes = payload.serialize()
        val sig = Signature.getInstance(LicenseFormat.SIGNATURE_ALGORITHM).run {
            initSign(privateKey, random)
            update(LicenseFormat.licenseSigningInput(bytes))
            sign()
        }
        val token = LicenseFormat.encodeToken(bytes, sig)
        Files.write(outPath, (token + "\n").toByteArray(), StandardOpenOption.CREATE_NEW)

        o["ledger"]?.let { appendLedger(Paths.get(it), payload) }
        out.println("License ID : ${payload.licenseId}")
        out.println("Policy     : ${payload.policy}")
        out.println("Written to : $outPath")
        if (bind == null) {
            out.println("NOTE: 'first-use' licenses are NOT tied to a device at issue time; the same file can be activated")
            out.println("      on several phones. Prefer --request for one-installation-per-license (docs/LICENSING.md).")
        }
        return 0
    }

    private fun verify(o: Map<String, String>): Int {
        val pubPath = Paths.get(o["pub"] ?: return missing("--pub"))
        val licPath = Paths.get(o["license"] ?: return missing("--license"))
        val pub = LicenseFormat.unb64(String(Files.readAllBytes(pubPath)).trim())
        if (pub == null) {
            err.println("error: public key file is malformed")
            return 2
        }
        val token = String(Files.readAllBytes(licPath))
        val fingerprint = o["request"]?.let { LicenseFormat.decodeRequest(it) }?.let(LicenseFormat::installFingerprint)
        if (o.containsKey("request") && fingerprint == null) {
            err.println("error: activation request code is malformed")
            return 1
        }
        return when (val r = LicenseVerifier(pub).verify(token, fingerprint)) {
            is VerifyResult.Valid -> {
                val p = r.license
                out.println("VALID  id=${p.licenseId} policy=${p.policy} entitlement=${p.entitlement} issued=${Instant.ofEpochSecond(p.issuedAtEpochSeconds)}")
                0
            }
            is VerifyResult.Invalid -> {
                err.println("INVALID: ${r.error}")
                3
            }
        }
    }

    private fun inspectRequest(o: Map<String, String>): Int {
        val pub = LicenseFormat.decodeRequest(o["request"] ?: return missing("--request"))
        if (pub == null) {
            err.println("error: activation request code is malformed")
            return 1
        }
        out.println("Valid request. Installation fingerprint: ${LicenseFormat.installFingerprint(pub)}")
        return 0
    }

    // ---- helpers ----

    private fun missing(flag: String): Int {
        err.println("error: missing $flag\n$USAGE")
        return 1
    }

    private fun passphrase(confirm: Boolean): CharArray? {
        env["WSS_KEY_PASSPHRASE"]?.takeIf { it.isNotEmpty() }?.let { return it.toCharArray() }
        val p = readPassphrase("Signing key passphrase: ")
        if (p == null || p.isEmpty()) {
            err.println("error: no passphrase available (set WSS_KEY_PASSPHRASE or run in a terminal)")
            return null
        }
        if (confirm) {
            val again = readPassphrase("Repeat passphrase: ")
            if (again == null || !again.contentEquals(p)) {
                err.println("error: passphrases do not match")
                return null
            }
            if (p.size < 12) {
                err.println("error: passphrase must be at least 12 characters")
                return null
            }
        }
        return p
    }

    private fun newLicenseId(): String {
        val b = ByteArray(16).also(random::nextBytes)
        val hex = b.joinToString("") { "%02X".format(it) }
        return "WSS-" + hex.chunked(4).joinToString("-")
    }

    private fun appendLedger(path: Path, p: LicensePayload) {
        val header = !Files.exists(path)
        val sb = StringBuilder()
        if (header) sb.append("license_id,issued_utc,policy,customer_ref,install_fingerprint\n")
        sb.append("${p.licenseId},${Instant.ofEpochSecond(p.issuedAtEpochSeconds)},${p.policy},${p.customerRef ?: ""},${p.bind ?: ""}\n")
        Files.write(path, sb.toString().toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun parseOptions(args: List<String>): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            require(a.startsWith("--")) { "unexpected argument '$a'" }
            val name = a.removePrefix("--")
            if (name == "unbound") {
                m[name] = "true"; i++
            } else {
                require(i + 1 < args.size) { "option $a needs a value" }
                m[name] = args[i + 1]; i += 2
            }
        }
        return m
    }

    companion object {
        const val USAGE = """Screenshot Assistant license tool

  keygen          --out-dir DIR
  issue           --key FILE (--request CODE | --unbound) --out FILE [--ref REF] [--ledger CSV]
  verify          --pub FILE --license FILE [--request CODE]
  inspect-request --request CODE

Passphrase: environment variable WSS_KEY_PASSPHRASE, or prompted on the terminal."""
    }
}
