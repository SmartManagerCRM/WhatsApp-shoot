package com.smartmanager.whatsappshoot.license

/**
 * The signed content of a license. Serialised as strict `key=value` lines (UTF-8, LF), in fixed order:
 *
 *     v=1
 *     product=<product id>
 *     id=<license id>
 *     policy=device-bound|first-use
 *     entitlement=lifetime|term
 *     issued=<epoch seconds>
 *     expires=<epoch seconds>                (present iff entitlement=term)
 *     rev=<revision, 1 and up>               (omitted for revision 0 = licenses issued before revisions existed)
 *     ref=<optional customer reference>      (omitted when empty)
 *     bind=<install fingerprint>             (present iff policy=device-bound)
 *
 * The signature covers the exact serialized bytes, so there is no canonicalisation ambiguity.
 */
data class LicensePayload(
    val version: Int,
    val productId: String,
    val licenseId: String,
    val policy: String,
    val entitlement: String,
    val issuedAtEpochSeconds: Long,
    val customerRef: String?,
    val bind: String?,
    /** End of validity for `term` licenses (epoch seconds); null for lifetime. */
    val expiresAtEpochSeconds: Long? = null,
    /** Renewal/amendment counter of this license id. A newer revision supersedes older ones; a revocation covers all revisions up to a number. */
    val revision: Int = 0,
) {
    fun serialize(): ByteArray = buildString {
        append("v=").append(version).append('\n')
        append("product=").append(productId).append('\n')
        append("id=").append(licenseId).append('\n')
        append("policy=").append(policy).append('\n')
        append("entitlement=").append(entitlement).append('\n')
        append("issued=").append(issuedAtEpochSeconds).append('\n')
        if (expiresAtEpochSeconds != null) append("expires=").append(expiresAtEpochSeconds).append('\n')
        if (revision > 0) append("rev=").append(revision).append('\n')
        if (!customerRef.isNullOrEmpty()) append("ref=").append(customerRef).append('\n')
        if (bind != null) append("bind=").append(bind).append('\n')
    }.toByteArray(Charsets.UTF_8)

    /** Non-secret display form, e.g. `WSS-9F3A-…-C41D`. */
    fun maskedId(): String = maskLicenseId(licenseId)

    companion object {
        private val VALUE = Regex("[A-Za-z0-9._:\\-]{1,64}")
        private val ID = Regex("WSS(-[0-9A-F]{4}){8}")
        private val KEYS_IN_ORDER = listOf("v", "product", "id", "policy", "entitlement", "issued", "expires", "rev", "ref", "bind")

        /** Strict parse. Returns null for unknown/duplicate/out-of-order keys or malformed values. */
        fun parse(bytes: ByteArray): LicensePayload? {
            val text = try {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (e: Exception) {
                return null
            }
            if (!text.endsWith("\n")) return null
            val map = LinkedHashMap<String, String>()
            var lastIndex = -1
            for (line in text.removeSuffix("\n").split('\n')) {
                val eq = line.indexOf('=')
                if (eq <= 0) return null
                val key = line.substring(0, eq)
                val value = line.substring(eq + 1)
                val idx = KEYS_IN_ORDER.indexOf(key)
                if (idx < 0 || idx <= lastIndex) return null // unknown, duplicate or out of order
                lastIndex = idx
                if (!VALUE.matches(value)) return null
                map[key] = value
            }
            val version = map["v"]?.toIntOrNull() ?: return null
            val issued = map["issued"]?.toLongOrNull() ?: return null
            val id = map["id"] ?: return null
            val expires = if (map.containsKey("expires")) (map["expires"]?.toLongOrNull() ?: return null) else null
            val rev = if (map.containsKey("rev")) (map["rev"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null) else 0
            if (!ID.matches(id)) return null
            return LicensePayload(
                version = version,
                productId = map["product"] ?: return null,
                licenseId = id,
                policy = map["policy"] ?: return null,
                entitlement = map["entitlement"] ?: return null,
                issuedAtEpochSeconds = issued,
                customerRef = map["ref"],
                bind = map["bind"],
                expiresAtEpochSeconds = expires,
                revision = rev,
            )
        }

        fun maskLicenseId(id: String): String {
            val groups = id.split('-')
            return if (groups.size >= 3) "${groups.first()}-${groups[1]}-…-${groups.last()}" else "WSS-…"
        }
    }
}
