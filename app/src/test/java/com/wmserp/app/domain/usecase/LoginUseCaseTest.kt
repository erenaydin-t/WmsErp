package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.Credentials
import com.wmserp.app.domain.repository.AuthRepository
import com.wmserp.app.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginUseCaseTest {

    private val repository: AuthRepository = mockk()
    private val useCase = LoginUseCase(repository)

    @Test
    fun `normalises url and trims username before calling repository`() = runTest {
        coEvery { repository.login(any(), any(), any()) } returns AppResult.Success(TestFixtures.session)

        val result = useCase("erp.example.com/app", Credentials.Password("  user@example.com ", "secret"), rememberMe = true)

        assertTrue(result is AppResult.Success)
        coVerify { repository.login("https://erp.example.com", Credentials.Password("user@example.com", "secret"), true) }
    }

    @Test
    fun `rejects invalid url without hitting the repository`() = runTest {
        val result = useCase("ftp://nope", Credentials.Password("u", "p"), rememberMe = false)

        assertTrue(result is AppResult.Failure)
        assertTrue((result as AppResult.Failure).error is AppError.Validation)
        coVerify(exactly = 0) { repository.login(any(), any(), any()) }
    }

    @Test
    fun `rejects empty password and empty token`() = runTest {
        val noPassword = useCase("erp.example.com", Credentials.Password("user", ""), false)
        val noToken = useCase("erp.example.com", Credentials.ApiToken("", "secret"), false)

        assertEquals("Password is required", (noPassword as AppResult.Failure).error.message)
        assertEquals("API key and API secret are required", (noToken as AppResult.Failure).error.message)
    }
}
