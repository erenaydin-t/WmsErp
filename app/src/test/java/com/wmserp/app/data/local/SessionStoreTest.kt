package com.wmserp.app.data.local

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStoreTest {

    @Test
    fun `update persists changed keys and load restores them`() = runTest {
        val storage = InMemorySecureStorage()
        val store = SessionStore(storage)

        store.update { it.copy(baseUrl = "https://erp.example.com", userId = "u@x.com", sid = "abc", rememberMe = true) }

        assertEquals("https://erp.example.com", storage.read(SessionStore.KEY_BASE_URL))
        assertEquals("abc", storage.read(SessionStore.KEY_SID))
        assertEquals("true", storage.read(SessionStore.KEY_REMEMBER_ME))

        val reloaded = SessionStore(storage).load()
        assertTrue(reloaded.isAuthenticated)
        assertEquals("u@x.com", reloaded.toUserSession()?.userId)
    }

    @Test
    fun `in-memory cookie updates are persisted by the next update`() = runTest {
        val storage = InMemorySecureStorage()
        val store = SessionStore(storage)
        store.update { it.copy(baseUrl = "https://erp.example.com") }

        store.updateInMemory { it.copy(sid = "from-cookie") }
        assertNull(storage.read(SessionStore.KEY_SID))

        store.update { it.copy(userId = "u@x.com") }
        assertEquals("from-cookie", storage.read(SessionStore.KEY_SID))
    }

    @Test
    fun `clearSession keeps url and username but drops secrets`() = runTest {
        val storage = InMemorySecureStorage()
        val store = SessionStore(storage)
        store.update { it.copy(baseUrl = "https://erp", userId = "u", sid = "s", savedUsername = "u", savedPassword = "p", apiKey = "k", apiSecret = "sec") }

        store.clearSession(keepCredentials = false)

        val state = store.current
        assertEquals("https://erp", state.baseUrl)
        assertEquals("u", state.savedUsername)
        assertNull(state.sid)
        assertNull(state.savedPassword)
        assertNull(state.apiKey)
        assertFalse(state.isAuthenticated)
        assertNull(storage.read(SessionStore.KEY_SAVED_PASSWORD))
    }
}
