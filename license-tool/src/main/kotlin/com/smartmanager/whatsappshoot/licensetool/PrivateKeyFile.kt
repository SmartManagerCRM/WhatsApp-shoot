package com.smartmanager.whatsappshoot.licensetool

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class KeyFileException(message: String) : Exception(message)

/**
 * Passphrase-encrypted container for the vendor signing key.
 *
 *     WSSKEY1\n<base64( salt16 | iv12 | AES-256-GCM(pkcs8) )>\n
 *
 * KDF: PBKDF2-HMAC-SHA256, 600 000 iterations. The header is bound as GCM associated data.
 */
object PrivateKeyFile {
    private const val HEADER = "WSSKEY1"
    private const val ITERATIONS = 600_000
    private val random = SecureRandom()

    data class Generated(val privateKeyPkcs8: ByteArray, val publicKeyX509: ByteArray)

    fun generateKeyPair(): Generated {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
        return Generated(kp.private.encoded, kp.public.encoded)
    }

    fun encrypt(pkcs8: ByteArray, passphrase: CharArray): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(HEADER.toByteArray())
        val ct = cipher.doFinal(pkcs8)
        return HEADER + "\n" + Base64.getEncoder().encodeToString(salt + iv + ct) + "\n"
    }

    fun decrypt(fileText: String, passphrase: CharArray): PrivateKey {
        val lines = fileText.trim().lines()
        if (lines.size != 2 || lines[0] != HEADER) throw KeyFileException("Not a signing key file.")
        val blob = try {
            Base64.getDecoder().decode(lines[1])
        } catch (e: IllegalArgumentException) {
            throw KeyFileException("Signing key file is damaged.")
        }
        if (blob.size < 16 + 12 + 16) throw KeyFileException("Signing key file is damaged.")
        val salt = blob.copyOfRange(0, 16)
        val iv = blob.copyOfRange(16, 28)
        val ct = blob.copyOfRange(28, blob.size)
        val pkcs8 = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
                updateAAD(HEADER.toByteArray())
                doFinal(ct)
            }
        } catch (e: Exception) {
            throw KeyFileException("Wrong passphrase, or the signing key file is damaged.")
        }
        return try {
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        } catch (e: Exception) {
            throw KeyFileException("Signing key file is damaged.")
        }
    }

    /** Writes with owner-only permissions where the file system supports POSIX modes. Refuses to overwrite. */
    fun writeSecret(path: Path, text: String) {
        if (Files.exists(path)) throw KeyFileException("Refusing to overwrite existing file: $path")
        try {
            Files.createFile(path, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } catch (e: UnsupportedOperationException) {
            Files.createFile(path)
        }
        Files.write(path, text.toByteArray())
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
