package com.smartmanager.whatsappshoot.platform

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.smartmanager.whatsappshoot.license.ActivationStore
import com.smartmanager.whatsappshoot.license.InstallKeyException
import com.smartmanager.whatsappshoot.license.InstallKeyProvider
import com.smartmanager.whatsappshoot.license.LicenseFormat
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The installation identity: an EC P-256 key pair generated inside the Android Keystore (StrongBox when the
 * device has it). The private key is non-exportable; it cannot be copied with the APK or the app's data.
 * The key is deleted by the system when the app is uninstalled or its data is cleared.
 */
class KeystoreInstallKeys(private val alias: String = ALIAS) : InstallKeyProvider {

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun publicKeyX509(): ByteArray? = try {
        keyStore().getCertificate(alias)?.publicKey?.encoded
    } catch (e: Exception) {
        null
    }

    override fun ensureKey(): ByteArray {
        publicKeyX509()?.let { return it }
        try {
            try {
                generate(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            } catch (e: StrongBoxUnavailableException) {
                generate(strongBox = false)
            }
        } catch (e: Exception) {
            throw InstallKeyException("Android Keystore key generation failed", e)
        }
        return publicKeyX509() ?: throw InstallKeyException("Key not found after generation")
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    override fun sign(data: ByteArray): ByteArray? = try {
        val key = keyStore().getKey(alias, null) as? PrivateKey
        if (key == null) null else Signature.getInstance(LicenseFormat.SIGNATURE_ALGORITHM).run {
            initSign(key)
            update(data)
            sign()
        }
    } catch (e: Exception) {
        null
    }

    override fun deleteKey() {
        try {
            keyStore().deleteEntry(alias)
        } catch (e: Exception) {
            // nothing sensible to do
        }
    }

    companion object {
        const val ALIAS = "wss_install_key_v1"
    }
}

/** Two redundant slots in private SharedPreferences; writes are synchronous so a crash cannot half-write. */
class PrefsActivationStore(context: Context) : ActivationStore {
    private val prefs = context.getSharedPreferences("activation", Context.MODE_PRIVATE)

    override fun read(slot: Int): String? = prefs.getString("slot$slot", null)

    override fun write(slot: Int, value: String) {
        if (!prefs.edit().putString("slot$slot", value).commit()) throw java.io.IOException("activation write failed")
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }
}
