package com.wmserp.app.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AesGcmStringCipherTest {

    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmStringCipher { key }

    @Test
    fun `round trips unicode text with a fresh iv each time`() {
        val plain = "p@ssw0rd — şifre 密码"
        val a = cipher.encrypt(plain)
        val b = cipher.encrypt(plain)
        assertNotEquals(a, b)
        assertEquals(plain, cipher.decrypt(a))
        assertEquals(plain, cipher.decrypt(b))
    }

    @Test
    fun `tampered cipher text fails authentication`() {
        val encrypted = cipher.encrypt("secret")
        val bytes = java.util.Base64.getDecoder().decode(encrypted)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        val tampered = java.util.Base64.getEncoder().encodeToString(bytes)
        assertThrows(Exception::class.java) { cipher.decrypt(tampered) }
    }

    @Test
    fun `different keys cannot decrypt`() {
        val other = AesGcmStringCipher { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        val encrypted = cipher.encrypt("secret")
        assertThrows(Exception::class.java) { other.decrypt(encrypted) }
    }
}
