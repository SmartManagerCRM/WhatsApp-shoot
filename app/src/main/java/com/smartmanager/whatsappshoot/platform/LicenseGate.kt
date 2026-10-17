package com.smartmanager.whatsappshoot.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.smartmanager.whatsappshoot.BuildConfig
import com.smartmanager.whatsappshoot.license.ActivationEngine
import com.smartmanager.whatsappshoot.license.ActivationStatus
import com.smartmanager.whatsappshoot.license.LicenseFormat
import com.smartmanager.whatsappshoot.license.LicenseVerifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest

/**
 * Bridges the independent licensing subsystem to the rest of the app. The automation code only sees
 * [isActive]; it never touches tokens, keys or screenshots-in-licensing. Licensing never receives screenshot data.
 */
class LicenseGate(context: Context, private val appId: String) {
    private val appContext = context.applicationContext

    val publicKey: ByteArray = LicenseFormat.unb64(BuildConfig.LICENSE_PUBLIC_KEY) ?: ByteArray(0)
    val keyConfigured: Boolean get() = LicenseFormat.parseP256PublicKey(publicKey) != null

    val engine = ActivationEngine(LicenseVerifier(publicKey), KeystoreInstallKeys(), PrefsActivationStore(appContext), appId)

    private val _status = MutableStateFlow<ActivationStatus?>(null) // null = not evaluated yet
    val status: StateFlow<ActivationStatus?> = _status

    @Volatile private var active = false

    /** Cheap, thread-safe read for hot paths. */
    fun isActive(): Boolean = active

    /** Re-evaluates the stored activation (Keystore + signature work): call off the main thread. */
    fun refresh() {
        val s = if (!signerMatches()) ActivationStatus.LicenseInvalid(com.smartmanager.whatsappshoot.license.LicenseError.PUBLIC_KEY_INVALID)
        else engine.status()
        active = s is ActivationStatus.Active
        com.smartmanager.whatsappshoot.logic.ActivityLog.add("license: ${s::class.simpleName}" + engine.lastDetail.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty())
        _status.value = s
    }

    /** Offline repackaging check. Disabled when no expected signer is configured (debug builds). */
    private fun signerMatches(): Boolean {
        val expected = BuildConfig.EXPECTED_SIGNER_SHA256
        if (expected.isEmpty()) return true
        return try {
            val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = appContext.packageManager.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            sigs?.any { sig ->
                MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
                    .equals(expected, ignoreCase = true)
            } == true
        } catch (e: Exception) {
            false
        }
    }
}
