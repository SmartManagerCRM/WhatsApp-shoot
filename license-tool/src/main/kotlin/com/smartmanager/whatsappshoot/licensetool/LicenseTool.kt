package com.smartmanager.whatsappshoot.licensetool

import com.smartmanager.whatsappshoot.license.ControlPayload
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
                "renew" -> reissue(opts, "renew")
                "amend" -> reissue(opts, "amend")
                "reinstate" -> reissue(opts, "reinstate")
                "suspend" -> withdraw(opts, LicenseState.SUSPENDED)
                "cancel" -> withdraw(opts, LicenseState.CANCELLED)
                "list" -> list(opts)
                "history" -> history(opts)
                "export" -> export(opts)
                "import-ledger" -> importLedger(opts)
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
        val expiresAt: Long? = try { expiryFrom(o) } catch (e: IllegalArgumentException) { err.println("error: ${e.message}"); return 1 }
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
            entitlement = if (expiresAt != null) LicenseFormat.ENTITLEMENT_TERM else LicenseFormat.ENTITLEMENT_LIFETIME,
            issuedAtEpochSeconds = clock().epochSecond,
            customerRef = ref,
            bind = bind,
            expiresAtEpochSeconds = expiresAt,
            revision = 1,
        )
        val token = sign(payload, privateKey)
        Files.write(outPath, (token + "\n").toByteArray(), StandardOpenOption.CREATE_NEW)

        o["ledger"]?.let { appendLedger(Paths.get(it), payload) }
        o["registry"]?.let {
            LicenseRegistry(Paths.get(it)).put(
                RegistryEntry(payload.licenseId, ref, o["note"].orEmpty(), payload.policy, bind, payload.issuedAtEpochSeconds, expiresAt, 1, LicenseState.ACTIVE, payload.issuedAtEpochSeconds),
                "issued", "revision 1, " + (expiresAt?.let { e -> "until ${Instant.ofEpochSecond(e)}" } ?: "lifetime"),
            )
        }
        out.println("License ID : ${payload.licenseId}")
        out.println("Policy     : ${payload.policy}")
        out.println("Valid      : " + (expiresAt?.let { "until ${Instant.ofEpochSecond(it)}" } ?: "lifetime"))
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
                out.println("VALID  id=${p.licenseId} policy=${p.policy} entitlement=${p.entitlement} rev=${p.revision} issued=${Instant.ofEpochSecond(p.issuedAtEpochSeconds)}" + (p.expiresAtEpochSeconds?.let { " expires=${Instant.ofEpochSecond(it)}" } ?: ""))
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

    // ---- administration of issued licenses ----

    private fun sign(payload: LicensePayload, key: PrivateKey): String {
        val bytes = payload.serialize()
        val sig = Signature.getInstance(LicenseFormat.SIGNATURE_ALGORITHM).run {
            initSign(key, random); update(LicenseFormat.licenseSigningInput(bytes)); sign()
        }
        return LicenseFormat.encodeToken(bytes, sig)
    }

    private fun signControl(c: ControlPayload, key: PrivateKey): String {
        val bytes = c.serialize()
        val sig = Signature.getInstance(LicenseFormat.SIGNATURE_ALGORITHM).run {
            initSign(key, random); update(LicenseFormat.controlSigningInput(bytes)); sign()
        }
        return LicenseFormat.encodeControl(bytes, sig)
    }

    private fun loadKey(o: Map<String, String>): PrivateKey? {
        val keyPath = Paths.get(o["key"] ?: run { missing("--key"); return null })
        if (!Files.isRegularFile(keyPath)) throw KeyFileException("Signing key file not found: $keyPath (private key unavailable)")
        val keyText = String(Files.readAllBytes(keyPath))
        val pass = passphrase(confirm = false) ?: return null
        val k = PrivateKeyFile.decrypt(keyText, pass)
        pass.fill('0')
        return k
    }

    /** `--expires YYYY-MM-DD` (end of that day, UTC) or `--days N` from now. Null = lifetime. */
    private fun expiryFrom(o: Map<String, String>, base: Long? = null): Long? {
        val n = listOf("expires", "days", "lifetime").count { o.containsKey(it) }
        require(n <= 1) { "give only one of --expires, --days, --lifetime" }
        o["expires"]?.let {
            val d = try { java.time.LocalDate.parse(it) } catch (e: Exception) { throw IllegalArgumentException("--expires must look like 2027-01-31") }
            val end = d.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond() - 1
            require(end > clock().epochSecond) { "--expires is in the past" }
            return end
        }
        o["days"]?.let {
            val days = it.toLongOrNull()?.takeIf { d -> d in 1..3660 } ?: throw IllegalArgumentException("--days must be a number from 1 to 3660")
            return maxOf(base ?: 0L, clock().epochSecond) + days * 86_400
        }
        return null
    }

    private fun registryOf(o: Map<String, String>): LicenseRegistry? = o["registry"]?.let { LicenseRegistry(Paths.get(it)) } ?: run { missing("--registry"); null }

    /** renew / amend / reinstate: a new revision of an existing license (same id, same phone), signed again. */
    private fun reissue(o: Map<String, String>, kind: String): Int {
        val reg = registryOf(o) ?: return 1
        val outPath = Paths.get(o["out"] ?: return missing("--out"))
        val entry = reg.resolve(o["id"] ?: return missing("--id")) ?: run { err.println("error: no single license matches --id (see the list command)"); return 1 }
        if (Files.exists(outPath)) { err.println("error: output file exists: $outPath"); return 1 }
        val hasExpiryOption = listOf("expires", "days", "lifetime").any { o.containsKey(it) }
        when (kind) {
            "renew" -> if (!hasExpiryOption) { err.println("error: renew needs --days N, --expires YYYY-MM-DD or --lifetime"); return 1 }
            "amend" -> if (!hasExpiryOption && !o.containsKey("ref") && !o.containsKey("note")) { err.println("error: amend needs at least one of --days/--expires/--lifetime/--ref/--note"); return 1 }
            "reinstate" -> if (entry.state == LicenseState.ACTIVE) { err.println("error: that license is not suspended or cancelled"); return 1 }
        }
        val ref = if (o.containsKey("ref")) o["ref"] else entry.customerRef
        if (ref != null && !Regex("[A-Za-z0-9._:\\-]{1,64}").matches(ref)) { err.println("error: --ref may only contain letters, digits and . _ : - (max 64)"); return 1 }
        val newExpiry: Long? = try {
            if (hasExpiryOption) expiryFrom(o, base = entry.expiresAt) else entry.expiresAt
        } catch (e: IllegalArgumentException) { err.println("error: ${e.message}"); return 1 }
        if (newExpiry != null && newExpiry <= clock().epochSecond) { err.println("error: the license would already be expired; give --days, --expires or --lifetime"); return 1 }
        val key = loadKey(o) ?: return 1
        val now = clock().epochSecond
        val payload = LicensePayload(
            version = LicenseFormat.FORMAT_VERSION, productId = LicenseFormat.PRODUCT_ID, licenseId = entry.licenseId, policy = entry.policy,
            entitlement = if (newExpiry != null) LicenseFormat.ENTITLEMENT_TERM else LicenseFormat.ENTITLEMENT_LIFETIME,
            issuedAtEpochSeconds = now, customerRef = ref, bind = entry.bind, expiresAtEpochSeconds = newExpiry, revision = entry.revision + 1,
        )
        Files.write(outPath, (sign(payload, key) + "\n").toByteArray(), StandardOpenOption.CREATE_NEW)
        reg.put(
            entry.copy(customerRef = ref, note = o["note"] ?: entry.note, expiresAt = newExpiry, revision = payload.revision, state = LicenseState.ACTIVE, updatedAt = now),
            kind, "revision ${payload.revision}, " + (newExpiry?.let { "until ${Instant.ofEpochSecond(it)}" } ?: "lifetime"),
        )
        out.println("$kind done: ${entry.licenseId} is now revision ${payload.revision}, " + (newExpiry?.let { "valid until ${Instant.ofEpochSecond(it)}" } ?: "lifetime"))
        out.println("Send the code in $outPath to the customer; the app accepts it in place of the old one.")
        return 0
    }

    /** suspend / cancel: a signed code that withdraws every revision issued so far. The customer applies it by pasting it into the app. */
    private fun withdraw(o: Map<String, String>, to: LicenseState): Int {
        val reg = registryOf(o) ?: return 1
        val outPath = Paths.get(o["out"] ?: return missing("--out"))
        val entry = reg.resolve(o["id"] ?: return missing("--id")) ?: run { err.println("error: no single license matches --id (see the list command)"); return 1 }
        if (Files.exists(outPath)) { err.println("error: output file exists: $outPath"); return 1 }
        val key = loadKey(o) ?: return 1
        val now = clock().epochSecond
        val code = signControl(ControlPayload(LicenseFormat.FORMAT_VERSION, LicenseFormat.PRODUCT_ID, LicenseFormat.ACTION_REVOKE, entry.licenseId, entry.revision, now), key)
        Files.write(outPath, (code + "\n").toByteArray(), StandardOpenOption.CREATE_NEW)
        reg.put(entry.copy(state = to, updatedAt = now), to.name.lowercase(), "withdraws revisions up to ${entry.revision}")
        out.println("${to.name}: ${entry.licenseId} (revisions up to ${entry.revision}).")
        out.println("Code written to $outPath. The license stops on a phone only after the code is entered in the app.")
        out.println("Offline apps cannot be switched off remotely: a TERM license (--days / --expires) ends by itself when it is not renewed.")
        return 0
    }

    private fun list(o: Map<String, String>): Int {
        val reg = registryOf(o) ?: return 1
        val now = clock().epochSecond
        val rows = reg.all().filter { o["status"] == null || it.statusAt(now).equals(o["status"], true) }
        if (rows.isEmpty()) { out.println("(no licenses)"); return 0 }
        out.println("%-45s %-12s %-10s %-4s %-12s %s".format("LICENSE ID", "STATUS", "VALID TO", "REV", "REFERENCE", "NOTE"))
        for (e in rows) out.println("%-45s %-12s %-10s %-4d %-12s %s".format(e.licenseId, e.statusAt(now), e.expiresAt?.let { Instant.ofEpochSecond(it).toString().take(10) } ?: "lifetime", e.revision, e.customerRef.orEmpty(), e.note))
        return 0
    }

    private fun history(o: Map<String, String>): Int {
        val reg = registryOf(o) ?: return 1
        val id = o["id"]?.let { reg.resolve(it)?.licenseId ?: run { err.println("error: no single license matches --id"); return 1 } }
        for (h in reg.history(id)) out.println("${Instant.ofEpochSecond(h.at)}  ${h.licenseId}  ${h.action}  ${h.detail}")
        return 0
    }

    /** Brings licenses issued before the register existed (the old issued-licenses.csv ledger) into the register, as revision 0. */
    private fun importLedger(o: Map<String, String>): Int {
        val reg = registryOf(o) ?: return 1
        val ledger = Paths.get(o["ledger"] ?: return missing("--ledger"))
        var added = 0
        for (line in Files.readAllLines(ledger).drop(1)) {
            val p = line.split(',')
            if (p.size < 5 || reg.find(p[0]) != null) continue
            val issued = runCatching { Instant.parse(p[1]).epochSecond }.getOrNull() ?: continue
            reg.put(RegistryEntry(p[0], p[3].ifEmpty { null }, "imported from ledger", p[2], p[4].ifEmpty { null }, issued, null, 0, LicenseState.ACTIVE, issued), "imported", "from ledger, revision 0")
            added++
        }
        out.println("Imported $added license(s) into ${reg.file}")
        return 0
    }

    private fun export(o: Map<String, String>): Int {
        val reg = registryOf(o) ?: return 1
        val outPath = Paths.get(o["out"] ?: return missing("--out"))
        Files.write(outPath, reg.exportCsv(clock().epochSecond).toByteArray())
        out.println("Exported ${reg.all().size} license(s) to $outPath")
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
            if (name == "unbound" || name == "lifetime") {
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
                  [--days N | --expires YYYY-MM-DD]   (term license; omit for lifetime)
                  [--registry FILE] [--note TEXT]     (private register of every license)
  renew           --registry FILE --key FILE --id ID --out FILE (--days N | --expires DATE | --lifetime)
  amend           --registry FILE --key FILE --id ID --out FILE [--days N | --expires DATE | --lifetime] [--ref REF] [--note TEXT]
  suspend|cancel  --registry FILE --key FILE --id ID --out FILE     (signed stop code for the customer to enter)
  reinstate       --registry FILE --key FILE --id ID --out FILE [--days N | --expires DATE | --lifetime]
  list            --registry FILE [--status ACTIVE|EXPIRING|EXPIRED|SUSPENDED|CANCELLED]
  history         --registry FILE [--id ID]
  export          --registry FILE --out CSV
  import-ledger   --registry FILE --ledger issued-licenses.csv   (adds licenses issued before the register existed)
  verify          --pub FILE --license FILE [--request CODE]
  inspect-request --request CODE

Passphrase: environment variable WSS_KEY_PASSPHRASE, or prompted on the terminal."""
    }
}
