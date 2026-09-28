package com.wmserp.app.core.security

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Symmetric string encryption used to protect credentials at rest. */
interface StringCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}

/** Provides the AES key. The Android implementation keeps it inside the hardware-backed Keystore. */
fun interface SecretKeyProvider {
    fun getKey(): SecretKey
}

/**
 * AES/GCM (256-bit key, 96-bit IV, 128-bit tag). Output format: base64(iv || ciphertext||tag).
 * Pure JVM so it can be unit tested; the key itself comes from [SecretKeyProvider].
 */
class AesGcmStringCipher(private val keyProvider: SecretKeyProvider) : StringCipher {

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // No explicit IV: both SunJCE and the Android Keystore generate a fresh random 12-byte IV,
        // and the Keystore rejects caller-provided IVs for encryption.
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider.getKey())
        val iv = cipher.iv
        require(iv != null && iv.size == IV_LENGTH) { "Unexpected GCM IV length" }
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + encrypted)
    }

    override fun decrypt(cipherText: String): String {
        val bytes = Base64.getDecoder().decode(cipherText)
        require(bytes.size > IV_LENGTH) { "Cipher text too short" }
        val iv = bytes.copyOfRange(0, IV_LENGTH)
        val payload = bytes.copyOfRange(IV_LENGTH, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider.getKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(payload), Charsets.UTF_8)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}

/** No-op cipher for unit tests / previews. Never used in production builds. */
class PlainTextCipher : StringCipher {
    override fun encrypt(plainText: String): String = plainText
    override fun decrypt(cipherText: String): String = cipherText
}
