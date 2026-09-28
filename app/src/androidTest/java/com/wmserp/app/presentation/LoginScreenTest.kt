package com.wmserp.app.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wmserp.app.presentation.login.LoginScreen
import com.wmserp.app.presentation.login.LoginUiState
import com.wmserp.app.presentation.theme.WmsErpTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loginButtonIsDisabledUntilFormIsValid() {
        composeRule.setContent { WmsErpTheme { LoginHost(LoginUiState()) } }

        composeRule.onNodeWithTag("login_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("login_url").performTextInput("erp.example.com")
        composeRule.onNodeWithTag("login_username").performTextInput("eren@example.com")
        composeRule.onNodeWithTag("login_password").performTextInput("secret")
        composeRule.onNodeWithTag("login_button").assertIsEnabled()
    }

    @Test
    fun submittingTheFormInvokesLogin() {
        var loginCalls = 0
        composeRule.setContent {
            WmsErpTheme {
                LoginHost(LoginUiState(url = "https://erp.example.com", username = "eren@example.com", password = "secret"), onLogin = { loginCalls++ })
            }
        }

        composeRule.onNodeWithTag("login_button").performClick()

        assertEquals(1, loginCalls)
    }

    @Test
    fun errorsAndInsecureUrlWarningsAreShown() {
        composeRule.setContent {
            WmsErpTheme { LoginHost(LoginUiState(url = "http://10.0.0.5", error = "Invalid username or password")) }
        }

        composeRule.onNodeWithTag("login_error").assertIsDisplayed()
        composeRule.onNodeWithText("Invalid username or password").assertIsDisplayed()
        composeRule.onNodeWithText("This server uses plain HTTP. Credentials will not be encrypted in transit; prefer HTTPS.").assertIsDisplayed()
    }

    @Test
    fun rememberMeToggleReportsChanges() {
        var remember = false
        composeRule.setContent { WmsErpTheme { LoginHost(LoginUiState(), onRememberMeChange = { remember = it }) } }

        composeRule.onNodeWithTag("login_remember").performClick()

        assertTrue(remember)
    }
}

/** Hosts the stateless LoginScreen with simple state hoisting so tests can type into the fields. */
@androidx.compose.runtime.Composable
private fun LoginHost(
    initial: LoginUiState,
    onLogin: () -> Unit = {},
    onRememberMeChange: (Boolean) -> Unit = {},
) {
    var state by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initial) }
    LoginScreen(
        state = state,
        onUrlChange = { state = state.copy(url = it) },
        onUsernameChange = { state = state.copy(username = it) },
        onPasswordChange = { state = state.copy(password = it) },
        onRememberMeChange = { state = state.copy(rememberMe = it); onRememberMeChange(it) },
        onTogglePasswordVisibility = { state = state.copy(passwordVisible = !state.passwordVisible) },
        onToggleAdvanced = { state = state.copy(showAdvanced = !state.showAdvanced) },
        onUseApiTokenChange = { state = state.copy(useApiToken = it) },
        onApiKeyChange = { state = state.copy(apiKey = it) },
        onApiSecretChange = { state = state.copy(apiSecret = it) },
        onLogin = onLogin,
        onDismissError = { state = state.copy(error = null) },
    )
}
