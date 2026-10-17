package com.smartmanager.whatsappshoot.license

/**
 * A signed administrator command that stops a license: "every revision of license [licenseId] up to [revision] is withdrawn".
 * Serialised as strict key=value lines in fixed order (signed bytes = exact bytes):
 *
 *     v=1
 *     product=<product id>
 *     action=revoke
 *     id=<license id>
 *     rev=<highest revision withdrawn>
 *     issued=<epoch seconds>
 *
 * A later license of the same id with a higher revision (a renewal or reinstatement) is NOT covered, so the owner can undo a suspension.
 */
data class ControlPayload(
    val version: Int,
    val productId: String,
    val action: String,
    val licenseId: String,
    val revision: Int,
    val issuedAtEpochSeconds: Long,
) {
    fun serialize(): ByteArray = buildString {
        append("v=").append(version).append('\n')
        append("product=").append(productId).append('\n')
        append("action=").append(action).append('\n')
        append("id=").append(licenseId).append('\n')
        append("rev=").append(revision).append('\n')
        append("issued=").append(issuedAtEpochSeconds).append('\n')
    }.toByteArray(Charsets.UTF_8)

    companion object {
        private val ID = Regex("WSS(-[0-9A-F]{4}){8}")
        private val KEYS = listOf("v", "product", "action", "id", "rev", "issued")

        fun parse(bytes: ByteArray): ControlPayload? {
            val text = try {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (e: Exception) {
                return null
            }
            if (!text.endsWith("\n")) return null
            val lines = text.removeSuffix("\n").split('\n')
            if (lines.size != KEYS.size) return null
            val map = HashMap<String, String>()
            for ((i, line) in lines.withIndex()) {
                val eq = line.indexOf('=')
                if (eq <= 0 || line.substring(0, eq) != KEYS[i]) return null
                map[KEYS[i]] = line.substring(eq + 1)
            }
            val id = map["id"]!!.takeIf { ID.matches(it) } ?: return null
            return ControlPayload(
                version = map["v"]!!.toIntOrNull() ?: return null,
                productId = map["product"]!!.takeIf { it.isNotEmpty() } ?: return null,
                action = map["action"]!!.takeIf { it.isNotEmpty() } ?: return null,
                licenseId = id,
                revision = map["rev"]!!.toIntOrNull()?.takeIf { it >= 0 } ?: return null,
                issuedAtEpochSeconds = map["issued"]!!.toLongOrNull() ?: return null,
            )
        }
    }
}

sealed class ControlVerifyResult {
    data class Valid(val control: ControlPayload) : ControlVerifyResult()
    data class Invalid(val error: LicenseError) : ControlVerifyResult()
}
