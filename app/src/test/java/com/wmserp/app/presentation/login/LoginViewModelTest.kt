package com.wmserp.app.presentation.login

import app.cash.turbine.test
import com.wmserp.app.R
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.common.ErrorCode
import com.wmserp.app.domain.model.Credentials
import com.wmserp.app.domain.model.LoginPrefill
import com.wmserp.app.domain.usecase.GetLoginPrefillUseCase
import com.wmserp.app.domain.usecase.LoginUseCase
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.testutil.MainDispatcherRule
import com.wmserp.app.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val loginUseCase: LoginUseCase = mockk()
    private val prefillUseCase: GetLoginPrefillUseCase = mockk()

    private fun createViewModel(): LoginViewModel {
        coEvery { prefillUseCase() } returns LoginPrefill(baseUrl = "https://erp.example.com", username = "eren@example.com", rememberMe = true)
        return LoginViewModel(loginUseCase, prefillUseCase)
    }

    @Test
    fun `prefills url and username from stored login`() = runTest {
        val vm = createViewModel()
        val state = vm.uiState.value
        assertEquals("https://erp.example.com", state.url)
        assertEquals("eren@example.com", state.username)
        assertTrue(state.rememberMe)
        assertFalse(state.canSubmit)
    }

    @Test
    fun `successful login emits LoggedIn event and clears the password`() = runTest {
        coEvery { loginUseCase(any(), any(), any()) } returns AppResult.Success(TestFixtures.session)
        val vm = createViewModel()
        vm.onPasswordChange("secret")

        vm.events.test {
            vm.login()
            val event = awaitItem() as LoginEvent.LoggedIn
            assertEquals("Eren Aydin", event.session.fullName)
        }
        assertEquals("", vm.uiState.value.password)
        assertFalse(vm.uiState.value.isLoading)
        coVerify { loginUseCase("https://erp.example.com", Credentials.Password("eren@example.com", "secret"), true) }
    }

    @Test
    fun `failed login exposes the error and keeps the form`() = runTest {
        coEvery { loginUseCase(any(), any(), any()) } returns AppResult.Failure(AppError.Unauthorized("Invalid username or password", ErrorCode.INVALID_CREDENTIALS))
        val vm = createViewModel()
        vm.onPasswordChange("bad")

        vm.login()

        assertEquals(UiText.Res(R.string.error_invalid_credentials), vm.uiState.value.error)
        assertEquals("bad", vm.uiState.value.password)
        vm.dismissError()
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `api token mode validates key and secret`() = runTest {
        coEvery { loginUseCase(any(), any(), any()) } returns AppResult.Success(TestFixtures.session)
        val vm = createViewModel()
        vm.onUseApiTokenChange(true)
        assertFalse(vm.uiState.value.canSubmit)
        vm.onApiKeyChange("k")
        vm.onApiSecretChange("s")
        assertTrue(vm.uiState.value.canSubmit)

        vm.login()

        coVerify { loginUseCase("https://erp.example.com", Credentials.ApiToken("k", "s"), true) }
    }

    @Test
    fun `flags insecure http urls`() = runTest {
        val vm = createViewModel()
        vm.onUrlChange("http://10.0.0.5")
        assertTrue(vm.uiState.value.isInsecureUrl)
    }
}
