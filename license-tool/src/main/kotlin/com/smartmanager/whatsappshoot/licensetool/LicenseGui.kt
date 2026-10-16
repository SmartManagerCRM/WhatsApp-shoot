package com.smartmanager.whatsappshoot.licensetool

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * Desktop interface for the owner: generate the signing keys, issue licences, verify licences.
 * A thin layer over [LicenseTool] (the same code the command line uses and the tests cover).
 * Runs offline; the passphrase is held only in the text field and passed in memory, never written anywhere.
 */
class LicenseGui {
    private val log = JTextArea(10, 70).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }

    private fun runTool(passphrase: CharArray?, vararg args: String): Int {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val env = if (passphrase != null) mapOf("WSS_KEY_PASSPHRASE" to String(passphrase)) else emptyMap()
        val code = LicenseTool(PrintStream(out), PrintStream(err), env, { null }).run(args.toList())
        val o = out.toString().trim(); val e = err.toString().trim()
        log.append("> ${args.firstOrNull()}  (exit $code)\n")
        if (o.isNotEmpty()) log.append(o + "\n")
        if (e.isNotEmpty()) log.append(e + "\n")
        log.append("\n"); log.caretPosition = log.document.length
        return code
    }

    private fun field(cols: Int = 36) = JTextField(cols)
    private fun pass() = JPasswordField(24)

    private fun browse(target: JTextField, save: Boolean, dirs: Boolean = false) = JButton("Browse...").apply {
        addActionListener {
            val fc = JFileChooser(target.text.ifBlank { "." }.let { File(it).let { f -> if (f.isDirectory) f else f.parentFile ?: File(".") } })
            if (dirs) fc.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            val r = if (save) fc.showSaveDialog(this) else fc.showOpenDialog(this)
            if (r == JFileChooser.APPROVE_OPTION) target.text = fc.selectedFile.path
        }
    }

    private fun form(rows: List<Triple<String, JTextField?, JPanel?>>, extra: List<Pair<String, java.awt.Component>> = emptyList()): JPanel {
        val p = JPanel(GridBagLayout()).apply { border = BorderFactory.createEmptyBorder(10, 10, 10, 10) }
        var y = 0
        fun add(label: String, c: java.awt.Component, browse: java.awt.Component? = null) {
            p.add(JLabel(label), GridBagConstraints().apply { gridx = 0; gridy = y; anchor = GridBagConstraints.NORTHWEST; insets = Insets(4, 4, 4, 8) })
            p.add(c, GridBagConstraints().apply { gridx = 1; gridy = y; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; insets = Insets(4, 0, 4, 4) })
            if (browse != null) p.add(browse, GridBagConstraints().apply { gridx = 2; gridy = y; insets = Insets(4, 4, 4, 4) })
            y++
        }
        for ((l, f, _) in rows) add(l, f!!)
        for ((l, c) in extra) add(l, c)
        return p
    }

    // ---------------- tab 1: keys ----------------
    private fun keysTab(): JPanel {
        val dir = field().apply { text = File("secrets").path }
        val p1 = pass(); val p2 = pass()
        val pub = JTextArea(3, 40).apply { isEditable = false; lineWrap = true }
        val copy = JButton("Copy public key").apply { isEnabled = false }
        val gen = JButton("Generate keys")
        gen.addActionListener {
            val a = p1.password; val b = p2.password
            when {
                !a.contentEquals(b) -> JOptionPane.showMessageDialog(gen, "The two passphrases do not match.")
                a.size < 12 -> JOptionPane.showMessageDialog(gen, "Use a passphrase of at least 12 characters.")
                else -> {
                    val code = runTool(a, "keygen", "--out-dir", dir.text)
                    if (code == 0) {
                        val text = File(dir.text, "license-public.b64").readText().trim()
                        pub.text = text; copy.isEnabled = true
                        JOptionPane.showMessageDialog(gen,
                            "Keys created in ${dir.text}.\n\nBACK UP license-private.enc (two copies) and store the passphrase separately.\n" +
                                "Losing either means you can never issue licenses for this app build.\nNever share license-private.enc.")
                    }
                    a.fill('0'); b.fill('0'); p1.text = ""; p2.text = ""
                }
            }
        }
        copy.addActionListener { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(pub.text), null) }
        val panel = JPanel(BorderLayout())
        panel.add(form(emptyList(), listOf(
            "Output folder" to JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(dir); add(browse(dir, false, true)) },
            "Passphrase" to p1, "Repeat passphrase" to p2, "" to gen,
            "Public key (put this in the app build)" to JScrollPane(pub), "" to copy,
        )), BorderLayout.NORTH)
        panel.add(JLabel("<html><div style='padding:10px;width:520px'>The <b>public key</b> is not secret: it goes into the app " +
            "(file <code>license-public-key.txt</code> or the GitHub variable <code>LICENSE_PUBLIC_KEY</code>). The <b>private key</b> " +
            "file stays with you and is encrypted with your passphrase.</div></html>"), BorderLayout.CENTER)
        return panel
    }

    // ---------------- tab 2: issue ----------------
    private fun issueTab(): JPanel {
        val key = field().apply { text = File("secrets/license-private.enc").path }
        val pw = pass()
        val request = JTextArea(3, 40).apply { lineWrap = true }
        val unbound = JCheckBox("No request code (first-use license: can be activated on several phones)")
        val ref = field(); val out = field(); val ledger = field().apply { text = File("secrets/issued-licenses.csv").path }
        val go = JButton("Issue license")
        go.addActionListener {
            val args = mutableListOf("issue", "--key", key.text, "--out", out.text)
            if (unbound.isSelected) args += "--unbound" else args += listOf("--request", request.text.trim())
            if (ref.text.isNotBlank()) args += listOf("--ref", ref.text.trim())
            if (ledger.text.isNotBlank()) args += listOf("--ledger", ledger.text.trim())
            val code = runTool(pw.password, *args.toTypedArray())
            pw.text = ""
            if (code == 0) JOptionPane.showMessageDialog(go, "License written to ${out.text}.\nSend that file to the customer.")
        }
        val keyRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(key); add(browse(key, false)) }
        val outRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(out); add(browse(out, true)) }
        val ledRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(ledger); add(browse(ledger, true)) }
        return form(emptyList(), listOf(
            "Private key file" to keyRow, "Passphrase" to pw,
            "Customer's activation request code" to JScrollPane(request), "" to unbound,
            "Order reference (no personal data)" to ref, "Save license as" to outRow, "Ledger (CSV)" to ledRow, "" to go,
        ))
    }

    // ---------------- tab 3: verify ----------------
    private fun verifyTab(): JPanel {
        val pub = field().apply { text = File("secrets/license-public.b64").path }
        val lic = field(); val req = field()
        val go = JButton("Verify license")
        go.addActionListener {
            val args = mutableListOf("verify", "--pub", pub.text, "--license", lic.text)
            if (req.text.isNotBlank()) args += listOf("--request", req.text.trim())
            runTool(null, *args.toTypedArray())
        }
        return form(emptyList(), listOf(
            "Public key file" to JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(pub); add(browse(pub, false)) },
            "License file" to JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(lic); add(browse(lic, false)) },
            "Request code (optional)" to req, "" to go,
        ))
    }

    // ---------------- tab 4: app signing keystore ----------------
    private fun appKeyTab(): JPanel {
        val file = field().apply { text = File("secrets/wss-release.p12").path }
        val alias = field().apply { text = "wss" }
        val pw = pass(); val pw2 = pass()
        val sha = field()
        val b64 = JTextArea(4, 40).apply { isEditable = false; lineWrap = true }
        val copy = JButton("Copy keystore as text (for GitHub secret)").apply { isEnabled = false }
        val go = JButton("Create app signing keystore")
        go.addActionListener {
            val a = pw.password; val b = pw2.password
            if (!a.contentEquals(b) || a.size < 8) {
                JOptionPane.showMessageDialog(go, "Passphrases must match and be at least 8 characters."); return@addActionListener
            }
            val f = File(file.text)
            if (f.exists()) { JOptionPane.showMessageDialog(go, "File already exists; refusing to overwrite:\n${f.path}"); return@addActionListener }
            f.absoluteFile.parentFile?.mkdirs()
            val pass = String(a)
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            fun kt(vararg args: String): String {
                val pr = ProcessBuilder(listOf(keytool) + args).redirectErrorStream(true).start()
                val o = pr.inputStream.bufferedReader().readText(); pr.waitFor(); return o
            }
            kt("-genkeypair", "-keystore", f.path, "-storetype", "PKCS12", "-storepass", pass, "-keypass", pass, "-alias", alias.text,
                "-keyalg", "RSA", "-keysize", "4096", "-validity", "36500", "-dname", "CN=Screenshot Assistant")
            if (!f.exists()) { log.append("keytool failed\n"); return@addActionListener }
            val listing = kt("-list", "-v", "-keystore", f.path, "-storepass", pass, "-alias", alias.text)
            val fp = Regex("SHA256:\\s*([0-9A-Fa-f:]+)").find(listing)?.groupValues?.get(1)?.replace(":", "")?.lowercase() ?: ""
            sha.text = fp
            b64.text = java.util.Base64.getEncoder().encodeToString(f.readBytes())
            copy.isEnabled = true
            try { java.nio.file.Files.setPosixFilePermissions(f.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) } catch (e: Exception) { }
            log.append("> app keystore created: ${f.path}\nCertificate SHA-256 (signerSha256): $fp\n\n")
            a.fill('0'); b.fill('0'); pw.text = ""; pw2.text = ""
            JOptionPane.showMessageDialog(go, "Keystore created.\nBACK IT UP (two copies) and keep the passphrase separately.\n" +
                "If you lose it, customers cannot receive updates over their installed app.")
        }
        copy.addActionListener { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(b64.text), null) }
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(file); add(browse(file, true)) }
        return form(emptyList(), listOf(
            "Keystore file" to row, "Key alias" to alias, "Passphrase" to pw, "Repeat passphrase" to pw2, "" to go,
            "Certificate SHA-256" to sha, "Keystore as text" to JScrollPane(b64), "" to copy,
        ))
    }

    fun show() {
        val tabs = JTabbedPane().apply {
            addTab("1. Generate keys", keysTab()); addTab("2. Issue license", issueTab()); addTab("3. Verify license", verifyTab()); addTab("4. App signing key", appKeyTab())
        }
        JFrame("Screenshot Assistant - License Manager").apply {
            defaultCloseOperation = JFrame.EXIT_ON_CLOSE
            layout = BorderLayout()
            add(tabs, BorderLayout.CENTER)
            add(JScrollPane(log).apply { border = BorderFactory.createTitledBorder("Output") }, BorderLayout.SOUTH)
            minimumSize = Dimension(760, 640)
            pack(); setLocationRelativeTo(null); isVisible = true
        }
    }
}
