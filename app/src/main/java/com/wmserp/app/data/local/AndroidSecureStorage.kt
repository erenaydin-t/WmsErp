package com.wmserp.app.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wmserp.app.core.security.SecretKeyProvider
import com.wmserp.app.core.security.StringCipher
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * DataStore-backed [SecureStorage]. Every value is encrypted with an AES/GCM key that lives in the
 * Android Keystore, so neither the key nor plaintext credentials are ever written to disk.
 */
class AndroidSecureStorage(
    private val dataStore: DataStore<Preferences>,
    private val cipher: StringCipher,
) : SecureStorage {

    override suspend fun read(key: String): String? {
        val stored = dataStore.data.first()[stringPreferencesKey(key)] ?: return null
        return runCatching { cipher.decrypt(stored) }.getOrNull()
    }

    override suspend fun write(key: String, value: String) {
        val encrypted = cipher.encrypt(value)
        dataStore.edit { it[stringPreferencesKey(key)] = encrypted }
    }

    override suspend fun remove(key: String) {
        dataStore.edit { it.remove(stringPreferencesKey(key)) }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}

/** Creates (once) and returns a hardware-backed AES-256 key from the Android Keystore. */
class AndroidKeystoreKeyProvider(private val alias: String = DEFAULT_ALIAS) : SecretKeyProvider {

    @Volatile
    private var cached: SecretKey? = null

    override fun getKey(): SecretKey {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            val key = existing ?: generate()
            cached = key
            return key
        }
    }

    private fun generate(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "wmserp_master_key"
        private const val KEY_SIZE_BITS = 256
    }
}
