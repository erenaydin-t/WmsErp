package com.wmserp.app.data.repository

import com.wmserp.app.data.local.SessionStore
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.Credentials
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthRepositoryImplTest {

    private val harness = RepositoryTestHarness()
    private lateinit var repository: AuthRepositoryImpl

    @Before
    fun setUp() {
        harness.start()
        repository = AuthRepositoryImpl(harness.api, harness.dataSource, harness.sessionStore, harness.cookieJar, harness.apiCaller, harness.eventBus)
    }

    @After
    fun tearDown() = harness.shutdown()

    @Test
    fun `password login stores the sid cookie, user id and remembered credentials`() = runTest {
        harness.server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "sid=abc123; Path=/; HttpOnly")
                .addHeader("Set-Cookie", "full_name=Eren%20Aydin; Path=/")
                .setBody("""{"message":"Logged In","home_page":"/app","full_name":"Eren Aydin"}""")
        )
        harness.server.enqueue(MockResponse().setBody("""{"message":"eren@example.com"}"""))

        val result = repository.login(harness.baseUrl, Credentials.Password("eren@example.com", "secret"), rememberMe = true)

        val session = (result as AppResult.Success).data
        assertEquals("eren@example.com", session.userId)
        assertEquals("Eren Aydin", session.fullName)

        val loginRequest = harness.server.takeRequest()
        assertEquals("/api/method/login", loginRequest.path)
        assertTrue(loginRequest.body.readUtf8().contains("usr=eren%40example.com&pwd=secret&device=mobile"))

        val whoAmI = harness.server.takeRequest()
        assertEquals("/api/method/frappe.auth.get_logged_user", whoAmI.path)
        assertTrue("session cookie must be sent", whoAmI.getHeader("Cookie")!!.contains("sid=abc123"))

        assertEquals("abc123", harness.storage.read(SessionStore.KEY_SID))
        assertEquals("secret", harness.storage.read(SessionStore.KEY_SAVED_PASSWORD))
        assertEquals("eren@example.com", harness.storage.read(SessionStore.KEY_USER_ID))
    }

    @Test
    fun `wrong password maps to Unauthorized without emitting session expiry`() = runTest {
        harness.server.enqueue(MockResponse().setResponseCode(401).setBody("""{"exc_type":"AuthenticationError","message":"Incorrect password"}"""))

        val result = repository.login(harness.baseUrl, Credentials.Password("eren@example.com", "bad"), rememberMe = false)

        val error = (result as AppResult.Failure).error
        assertTrue(error is AppError.Unauthorized)
        assertEquals("Invalid username or password", error.message)
        assertNull(harness.storage.read(SessionStore.KEY_SID))
    }

    @Test
    fun `api token login sends Authorization header and no cookies`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"message":"api@example.com"}"""))
        harness.server.enqueue(MockResponse().setBody("""{"data":{"name":"api@example.com","full_name":"API User","email":"api@example.com"}}"""))

        val result = repository.login(harness.baseUrl, Credentials.ApiToken("key", "secret"), rememberMe = true)

        assertEquals("API User", (result as AppResult.Success).data.fullName)
        val request = harness.server.takeRequest()
        assertEquals("token key:secret", request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie"))
    }

    @Test
    fun `restoreSession re-logs in with remembered credentials when the session expired`() = runTest {
        harness.sessionStore.update {
            it.copy(baseUrl = harness.baseUrl, userId = "eren@example.com", fullName = "Eren", sid = "stale", rememberMe = true, savedUsername = "eren@example.com", savedPassword = "secret")
        }
        harness.server.enqueue(MockResponse().setResponseCode(401).setBody("""{"exc_type":"AuthenticationError"}"""))
        harness.server.enqueue(MockResponse().addHeader("Set-Cookie", "sid=fresh; Path=/").setBody("""{"message":"Logged In","full_name":"Eren Aydin"}"""))
        harness.server.enqueue(MockResponse().setBody("""{"message":"eren@example.com"}"""))

        val result = repository.restoreSession()

        assertEquals("Eren Aydin", (result as AppResult.Success).data?.fullName)
        assertEquals("fresh", harness.storage.read(SessionStore.KEY_SID))
    }

    @Test
    fun `restoreSession returns null when nothing is stored`() = runTest {
        val result = repository.restoreSession()
        assertNull((result as AppResult.Success).data)
    }

    @Test
    fun `logout calls the server and clears secrets but keeps the url`() = runTest {
        harness.sessionStore.update { it.copy(baseUrl = harness.baseUrl, userId = "u", sid = "s", savedUsername = "u", savedPassword = "p") }
        harness.server.enqueue(MockResponse().setBody("""{"message":null}"""))

        repository.logout()

        assertEquals("/api/method/logout", harness.server.takeRequest().path)
        assertEquals(harness.baseUrl, harness.storage.read(SessionStore.KEY_BASE_URL))
        assertNull(harness.storage.read(SessionStore.KEY_SID))
        assertNull(harness.storage.read(SessionStore.KEY_SAVED_PASSWORD))
        assertEquals("u", repository.getLoginPrefill().username)
    }

    @Test
    fun `changePassword maps wrong current password to a validation error`() = runTest {
        harness.sessionStore.update { it.copy(baseUrl = harness.baseUrl, userId = "u", sid = "s") }
        harness.server.enqueue(MockResponse().setResponseCode(401).setBody("""{"exc_type":"AuthenticationError","message":"Incorrect password"}"""))

        val result = repository.changePassword("old", "newpassword1")

        assertEquals("Current password is incorrect", (result as AppResult.Failure).error.message)
    }
}
