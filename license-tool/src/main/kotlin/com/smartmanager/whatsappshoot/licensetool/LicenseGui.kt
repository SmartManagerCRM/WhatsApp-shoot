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
import javax.swing.JTable
import javax.swing.ButtonGroup
import javax.swing.JRadioButton
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * Desktop interface for the owner: generate the signing keys, issue licences, verify licences.
 * A thin layer over [LicenseTool] (the same code the command line uses and the tests cover).
 * Runs offline; the passphrase is held only in the text field and passed in memory, never written anywhere.
 */
class LicenseGui {
    companion object { const val DEFAULT_REGISTRY = "secrets/licenses-registry.tsv" }
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
        val unbound = JCheckBox("Quick license - no request code needed (recommended for sales; the code works on the first phone that uses it and could be shared; use subscriptions)", true)
        val ref = field(); val out = field(); val ledger = field().apply { text = File("secrets/issued-licenses.csv").path }
        val registry = field().apply { text = File(DEFAULT_REGISTRY).path }
        val note = field()
        val lifetime = JRadioButton("Lifetime (never expires)", true)
        val term = JRadioButton("Subscription: valid for")
        ButtonGroup().apply { add(lifetime); add(term) }
        val days = JTextField("30", 5)
        val validity = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { add(lifetime); add(term); add(days); add(JLabel("days (renew it when the customer pays again)")) }
        val go = JButton("Issue license")
        go.addActionListener {
            val args = mutableListOf("issue", "--key", key.text, "--out", out.text)
            if (unbound.isSelected) args += "--unbound" else args += listOf("--request", request.text.trim())
            if (ref.text.isNotBlank()) args += listOf("--ref", ref.text.trim())
            if (ledger.text.isNotBlank()) args += listOf("--ledger", ledger.text.trim())
            if (term.isSelected) args += listOf("--days", days.text.trim())
            if (registry.text.isNotBlank()) args += listOf("--registry", registry.text.trim())
            if (note.text.isNotBlank()) args += listOf("--note", note.text.trim())
            val code = runTool(pw.password, *args.toTypedArray())
            pw.text = ""
            if (code == 0) showCode("License issued", File(out.text))
        }
        val keyRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(key); add(browse(key, false)) }
        val outRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(out); add(browse(out, true)) }
        val ledRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(ledger); add(browse(ledger, true)) }
        return form(emptyList(), listOf(
            "Private key file" to keyRow, "Passphrase" to pw,
            "Activation request (only for a phone-locked license)" to JScrollPane(request), "" to unbound,
            "Order reference (no personal data)" to ref, "Customer / note (private, stays in your register)" to note,
            "Validity" to validity, "Register (all licenses)" to JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(registry); add(browse(registry, true)) },
            "Save license as" to outRow, "Ledger (CSV)" to ledRow, "" to go,
        ))
    }

    // ---------------- customers & licenses: full control of every issued license ----------------
    private fun showCode(title: String, file: File) {
        val text = file.readText().trim()
        val area = JTextArea(text, 6, 60).apply { isEditable = false; lineWrap = true }
        val copy = JButton("Copy code").apply { addActionListener { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) } }
        val panel = JPanel(BorderLayout(0, 6)).apply {
            add(JLabel("<html>Saved to <b>${file.path}</b><br>Send this code to the customer (WhatsApp / email) - they paste it into the app's License screen.</html>"), BorderLayout.NORTH)
            add(JScrollPane(area), BorderLayout.CENTER); add(copy, BorderLayout.SOUTH)
        }
        JOptionPane.showMessageDialog(null, panel, title, JOptionPane.INFORMATION_MESSAGE)
    }

    private fun customersTab(): JPanel {
        val registry = field(30).apply { text = File(DEFAULT_REGISTRY).path }
        val key = field(30).apply { text = File("secrets/license-private.enc").path }
        val pw = pass()
        val model = object : DefaultTableModel(arrayOf("Customer ref", "Note", "License ID", "Status", "Valid to", "Rev", "Policy"), 0) {
            override fun isCellEditable(row: Int, column: Int) = false
        }
        val table = JTable(model).apply { autoCreateRowSorter = true; setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION); rowHeight = 24 }
        val colours = mapOf("ACTIVE" to java.awt.Color(0xDFF3E3), "EXPIRING" to java.awt.Color(0xFFF1CC), "EXPIRED" to java.awt.Color(0xF8D7DA), "SUSPENDED" to java.awt.Color(0xFFE0B2), "CANCELLED" to java.awt.Color(0xE0E0E0))
        table.setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, v: Any?, sel: Boolean, foc: Boolean, row: Int, col: Int): java.awt.Component {
                val c = super.getTableCellRendererComponent(t, v, sel, foc, row, col)
                if (!sel) c.background = colours[t.getValueAt(row, 3)?.toString()] ?: java.awt.Color.WHITE
                return c
            }
        })
        val summary = JLabel(" ")
        fun refresh() {
            model.rowCount = 0
            val now = java.time.Instant.now().epochSecond
            val reg = try { LicenseRegistry(File(registry.text).toPath()) } catch (e: Exception) { summary.text = "Cannot read register: ${e.message}"; return }
            val rows = reg.all().sortedByDescending { it.updatedAt }
            for (e in rows) model.addRow(arrayOf(e.customerRef.orEmpty(), e.note, e.licenseId, e.statusAt(now),
                e.expiresAt?.let { java.time.Instant.ofEpochSecond(it).toString().take(10) } ?: "lifetime", e.revision, e.policy))
            val counts = rows.groupingBy { it.statusAt(now) }.eachCount()
            summary.text = "${rows.size} license(s): " + listOf("ACTIVE", "EXPIRING", "EXPIRED", "SUSPENDED", "CANCELLED").filter { counts[it] != null }.joinToString("  ") { "$it ${counts[it]}" }
        }
        fun selectedId(): String? {
            val r = table.selectedRow
            if (r < 0) { JOptionPane.showMessageDialog(table, "Select a license in the list first."); return null }
            return table.getValueAt(r, 2).toString()
        }
        fun codeFile(id: String, action: String): File {
            val dir = File(File(registry.text).absoluteFile.parentFile, "codes").also { it.mkdirs() }
            return File(dir, "$id-$action-${System.currentTimeMillis() / 1000}.txt")
        }
        /** Runs a registry command that writes a code file, then shows the code. */
        fun act(action: String, extra: List<String>, title: String) {
            val id = selectedId() ?: return
            if (pw.password.isEmpty()) { JOptionPane.showMessageDialog(table, "Type your signing passphrase first (it is used only for this action)."); return }
            val f = codeFile(id, action)
            val code = runTool(pw.password, action, "--registry", registry.text, "--key", key.text, "--id", id, "--out", f.path, *extra.toTypedArray())
            pw.text = ""
            refresh()
            if (code == 0) showCode(title, f)
        }
        fun askExpiry(title: String, allowKeep: Boolean): List<String>? {
            val keep = JRadioButton("Keep the current end date", allowKeep).apply { isVisible = allowKeep }
            val add = JRadioButton("Add days from the current end (or from today if it has passed):", !allowKeep)
            val until = JRadioButton("Valid until date (YYYY-MM-DD):")
            val life = JRadioButton("Lifetime")
            ButtonGroup().apply { add(keep); add(add); add(until); add(life) }
            val days = JTextField("30", 6); val date = JTextField("", 10)
            val p = JPanel(GridBagLayout())
            fun row(y: Int, vararg c: java.awt.Component) { c.forEachIndexed { i, comp -> p.add(comp, GridBagConstraints().apply { gridx = i; gridy = y; anchor = GridBagConstraints.WEST; insets = Insets(3, 3, 3, 3) }) } }
            row(0, keep); row(1, add, days); row(2, until, date); row(3, life)
            if (JOptionPane.showConfirmDialog(table, p, title, JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return null
            return when {
                keep.isSelected -> emptyList()
                add.isSelected -> listOf("--days", days.text.trim())
                until.isSelected -> listOf("--expires", date.text.trim())
                else -> listOf("--lifetime")
            }
        }
        val b = { label: String, tip: String, run: () -> Unit -> JButton(label).apply { toolTipText = tip; addActionListener { run() } } }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(b("Renew...", "Extend a subscription after the customer paid") { askExpiry("Renew", false)?.let { act("renew", it, "Renewal code") } })
            add(b("Amend...", "Change end date, reference or note") {
                val e = askExpiry("Amend: end date", true) ?: return@b
                val ref = JOptionPane.showInputDialog(table, "New order reference (empty = keep):", "Amend", JOptionPane.PLAIN_MESSAGE) ?: return@b
                val note = JOptionPane.showInputDialog(table, "New private note (empty = keep):", "Amend", JOptionPane.PLAIN_MESSAGE) ?: return@b
                val extra = e + (if (ref.isNotBlank()) listOf("--ref", ref.trim()) else emptyList()) + (if (note.isNotBlank()) listOf("--note", note.trim()) else emptyList())
                if (extra.isEmpty()) JOptionPane.showMessageDialog(table, "Nothing to change.") else act("amend", extra, "Amended license code")
            })
            add(b("Suspend", "Stop the license (reversible with Reinstate): produces a stop code") {
                if (JOptionPane.showConfirmDialog(table, "Create a SUSPEND code?\nThe customer's app stops only when they enter it. A subscription also ends by itself if not renewed.", "Suspend", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) act("suspend", emptyList(), "Suspend code")
            })
            add(b("Cancel", "Withdraw the license: produces a stop code") {
                if (JOptionPane.showConfirmDialog(table, "Create a CANCEL code?\nThe customer's app stops only when they enter it. A subscription also ends by itself if not renewed.", "Cancel", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) act("cancel", emptyList(), "Cancel code")
            })
            add(b("Reinstate...", "Give a suspended or cancelled license back") { askExpiry("Reinstate", true)?.let { act("reinstate", it, "Reinstatement code") } })
            add(b("History", "Everything done to the selected license") {
                val id = selectedId() ?: return@b
                val lines = LicenseRegistry(File(registry.text).toPath()).history(id).joinToString("\n") { "${java.time.Instant.ofEpochSecond(it.at)}  ${it.action}  ${it.detail}" }
                JOptionPane.showMessageDialog(table, JScrollPane(JTextArea(lines.ifEmpty { "(nothing)" }, 12, 60).apply { isEditable = false }), "History of $id", JOptionPane.PLAIN_MESSAGE)
            })
            add(b("Import old ledger...", "Add licenses you issued before this register existed (issued-licenses.csv)") {
                val fc = JFileChooser(File("secrets")).apply { selectedFile = File("secrets/issued-licenses.csv") }
                if (fc.showOpenDialog(table) == JFileChooser.APPROVE_OPTION) { runTool(null, "import-ledger", "--registry", registry.text, "--ledger", fc.selectedFile.path); refresh() }
            })
            add(b("Export CSV...", "Save the whole register as a spreadsheet file") {
                val fc = JFileChooser().apply { selectedFile = File("licenses.csv") }
                if (fc.showSaveDialog(table) == JFileChooser.APPROVE_OPTION) runTool(null, "export", "--registry", registry.text, "--out", fc.selectedFile.path)
            })
        }
        val top = form(emptyList(), listOf(
            "Register file" to JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(registry); add(browse(registry, false)); add(JButton("Refresh").apply { addActionListener { refresh() } }) },
            "Private key file" to JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(key); add(browse(key, false)) },
            "Signing passphrase (not stored)" to pw,
        ))
        val panel = JPanel(BorderLayout())
        panel.add(top, BorderLayout.NORTH)
        panel.add(JScrollPane(table), BorderLayout.CENTER)
        val south = JPanel(BorderLayout()); south.add(summary, BorderLayout.NORTH); south.add(buttons, BorderLayout.CENTER)
        south.add(JLabel("<html><div style='padding:4px 8px;width:700px'><b>How control works offline:</b> a <i>subscription</i> licence ends by itself on its end date unless you send a renewal code - " +
            "that is how you stop a customer who does not pay. <i>Suspend / Cancel</i> make a signed stop code the customer must enter; an app that is never connected cannot be switched off remotely. " +
            "Renew / Amend / Reinstate produce a new code for the same phone (no new request code needed).</div></html>"), BorderLayout.SOUTH)
        panel.add(south, BorderLayout.SOUTH)
        refresh()
        return panel
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
            addTab("1. Generate keys", keysTab()); addTab("2. Issue license", issueTab()); addTab("3. Customers & licenses", customersTab())
            addTab("4. Verify license", verifyTab()); addTab("5. App signing key", appKeyTab())
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
