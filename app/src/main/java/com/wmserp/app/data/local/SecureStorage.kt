package com.wmserp.app.data.local

/**
 * Key/value storage whose values are encrypted at rest.
 * The Android implementation wraps DataStore + an AES/GCM key held in the Android Keystore.
 */
interface SecureStorage {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
    suspend fun remove(key: String)
    suspend fun clear()
}

/** In-memory implementation for tests and previews. */
class InMemorySecureStorage : SecureStorage {
    private val map = mutableMapOf<String, String>()
    override suspend fun read(key: String): String? = map[key]
    override suspend fun write(key: String, value: String) { map[key] = value }
    override suspend fun remove(key: String) { map.remove(key) }
    override suspend fun clear() = map.clear()
    fun snapshot(): Map<String, String> = map.toMap()
}
