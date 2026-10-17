package com.smartmanager.whatsappshoot.licensetool

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

enum class LicenseState { ACTIVE, SUSPENDED, CANCELLED }

/** One issued license as the owner tracks it. [note] (customer name, phone, invoice...) stays in this private file and never enters a license. */
data class RegistryEntry(
    val licenseId: String,
    val customerRef: String?,
    val note: String,
    val policy: String,
    val bind: String?,
    val issuedAt: Long,
    val expiresAt: Long?,
    val revision: Int,
    val state: LicenseState,
    val updatedAt: Long,
) {
    /** What the owner sees: lifecycle state combined with the end date. */
    fun statusAt(nowSec: Long): String = when {
        state == LicenseState.CANCELLED -> "CANCELLED"
        state == LicenseState.SUSPENDED -> "SUSPENDED"
        expiresAt != null && nowSec > expiresAt -> "EXPIRED"
        expiresAt != null && expiresAt - nowSec < 7 * 86_400 -> "EXPIRING"
        else -> "ACTIVE"
    }
}

data class HistoryEvent(val at: Long, val licenseId: String, val action: String, val detail: String)

/**
 * The owner's private register of every license produced: who has what, until when, in which state, and what was done to it.
 * Plain tab-separated text next to the signing key (back it up with the key). Every change is also appended to a history log.
 * The app never sees this file.
 */
class LicenseRegistry(val file: Path) {
    private val historyFile: Path = file.resolveSibling(file.fileName.toString() + ".history.log")
    private val entries = LinkedHashMap<String, RegistryEntry>()

    init {
        if (Files.isRegularFile(file)) {
            for (line in Files.readAllLines(file)) {
                if (line.isBlank() || line.startsWith("#")) continue
                parse(line)?.let { entries[it.licenseId] = it }
            }
        }
    }

    fun all(): List<RegistryEntry> = entries.values.toList()
    fun find(id: String): RegistryEntry? = entries[id.trim()]

    /** Unique match on a (case-insensitive) id fragment, so the owner can type the last digits. */
    fun resolve(idOrFragment: String): RegistryEntry? {
        val q = idOrFragment.trim().uppercase()
        if (q.isEmpty()) return null
        entries[q]?.let { return it }
        val hits = entries.values.filter { it.licenseId.contains(q) }
        return hits.singleOrNull()
    }

    fun put(e: RegistryEntry, action: String, detail: String) {
        entries[e.licenseId] = e
        save()
        history(HistoryEvent(e.updatedAt, e.licenseId, action, detail))
    }

    fun history(id: String? = null): List<HistoryEvent> {
        if (!Files.isRegularFile(historyFile)) return emptyList()
        return Files.readAllLines(historyFile).mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 4) null else HistoryEvent(p[0].toLongOrNull() ?: return@mapNotNull null, p[1], p[2], unesc(p[3]))
        }.filter { id == null || it.licenseId == id }
    }

    fun exportCsv(nowSec: Long): String = buildString {
        append("license_id,customer_ref,note,policy,status,revision,issued_utc,expires_utc,updated_utc\n")
        for (e in entries.values) {
            append(listOf(e.licenseId, e.customerRef.orEmpty(), csv(e.note), e.policy, e.statusAt(nowSec), e.revision.toString(),
                Instant.ofEpochSecond(e.issuedAt).toString(), e.expiresAt?.let { Instant.ofEpochSecond(it).toString() } ?: "lifetime",
                Instant.ofEpochSecond(e.updatedAt).toString()).joinToString(",")).append('\n')
        }
    }

    private fun save() {
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val text = buildString {
            append("# Screenshot Assistant license register (private, back it up with the signing key)\n")
            for (e in entries.values) {
                append(listOf(e.licenseId, e.customerRef.orEmpty(), esc(e.note), e.policy, e.bind.orEmpty(), e.issuedAt, e.expiresAt?.toString() ?: "",
                    e.revision, e.state.name, e.updatedAt).joinToString("\t")).append('\n')
            }
        }
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.write(tmp, text.toByteArray())
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun history(h: HistoryEvent) {
        Files.write(historyFile, "${h.at}\t${h.licenseId}\t${h.action}\t${esc(h.detail)}\n".toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun parse(line: String): RegistryEntry? {
        val p = line.split('\t')
        if (p.size < 10) return null
        return RegistryEntry(
            licenseId = p[0], customerRef = p[1].ifEmpty { null }, note = unesc(p[2]), policy = p[3], bind = p[4].ifEmpty { null },
            issuedAt = p[5].toLongOrNull() ?: return null, expiresAt = p[6].toLongOrNull(), revision = p[7].toIntOrNull() ?: return null,
            state = runCatching { LicenseState.valueOf(p[8]) }.getOrNull() ?: return null, updatedAt = p[9].toLongOrNull() ?: return null,
        )
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")
    private fun unesc(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { sb.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] }); i += 2 } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
    private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"").replace("\n", " ") + "\"" else s
}
