package com.wmserp.app.presentation.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wmserp.app.R
import com.wmserp.app.presentation.common.asString
import com.wmserp.app.presentation.components.ErrorBanner
import com.wmserp.app.presentation.components.InfoBanner
import com.wmserp.app.presentation.theme.WmsTheme

@Composable
fun LoginRoute(viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LoginScreen(
        state = state,
        onUrlChange = viewModel::onUrlChange,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onRememberMeChange = viewModel::onRememberMeChange,
        onTogglePasswordVisibility = viewModel::togglePasswordVisibility,
        onToggleAdvanced = viewModel::toggleAdvanced,
        onUseApiTokenChange = viewModel::onUseApiTokenChange,
        onApiKeyChange = viewModel::onApiKeyChange,
        onApiSecretChange = viewModel::onApiSecretChange,
        onLogin = viewModel::login,
        onDismissError = viewModel::dismissError,
    )
}

@Composable
fun LoginScreen(
    state: LoginUiState,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onRememberMeChange: (Boolean) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onToggleAdvanced: () -> Unit,
    onUseApiTokenChange: (Boolean) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onApiSecretChange: (String) -> Unit,
    onLogin: () -> Unit,
    onDismissError: () -> Unit,
) {
    val colors = WmsTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(
                    Brush.linearGradient(listOf(colors.gradientStart, colors.gradientEnd)),
                    RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = Color.White, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.app_name), color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.login_subtitle), color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(28.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(stringResource(R.string.login_title), style = MaterialTheme.typography.titleLarge)

                    OutlinedTextField(
                        value = state.url,
                        onValueChange = onUrlChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_url"),
                        label = { Text(stringResource(R.string.login_url_label)) },
                        placeholder = { Text(stringResource(R.string.login_url_placeholder)) },
                        leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                        singleLine = true,
                        enabled = !state.isLoading,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    )
                    if (state.isInsecureUrl) {
                        InfoBanner(
                            stringResource(R.string.login_insecure_warning),
                            container = colors.warningContainer,
                            content = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    if (!state.useApiToken) {
                        OutlinedTextField(
                            value = state.username,
                            onValueChange = onUsernameChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("login_username"),
                            label = { Text(stringResource(R.string.login_username_label)) },
                            leadingIcon = { Icon(Icons.Outlined.Email, contentDescription = null) },
                            singleLine = true,
                            enabled = !state.isLoading,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        )
                        OutlinedTextField(
                            value = state.password,
                            onValueChange = onPasswordChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("login_password"),
                            label = { Text(stringResource(R.string.login_password_label)) },
                            leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = onTogglePasswordVisibility, modifier = Modifier.testTag("login_toggle_password")) {
                                    Icon(
                                        if (state.passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = stringResource(if (state.passwordVisible) R.string.login_hide_password else R.string.login_show_password),
                                    )
                                }
                            },
                            singleLine = true,
                            enabled = !state.isLoading,
                            visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (state.canSubmit) onLogin() }),
                        )
                    } else {
                        OutlinedTextField(
                            value = state.apiKey,
                            onValueChange = onApiKeyChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("login_api_key"),
                            label = { Text(stringResource(R.string.login_api_key)) },
                            leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                            singleLine = true,
                            enabled = !state.isLoading,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        )
                        OutlinedTextField(
                            value = state.apiSecret,
                            onValueChange = onApiSecretChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("login_api_secret"),
                            label = { Text(stringResource(R.string.login_api_secret)) },
                            leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                            singleLine = true,
                            enabled = !state.isLoading,
                            visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = onTogglePasswordVisibility) {
                                    Icon(
                                        if (state.passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = stringResource(R.string.login_toggle_visibility),
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (state.canSubmit) onLogin() }),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.login_remember_me), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = state.rememberMe,
                            onCheckedChange = onRememberMeChange,
                            enabled = !state.isLoading,
                            modifier = Modifier.testTag("login_remember"),
                        )
                    }

                    state.error?.let { error ->
                        ErrorBanner(error.asString(), modifier = Modifier.testTag("login_error"), onDismiss = onDismissError)
                    }

                    Button(
                        onClick = onLogin,
                        enabled = state.canSubmit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("login_button"),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.login_button), style = MaterialTheme.typography.titleMedium)
                        }
                    }

                    TextButton(onClick = onToggleAdvanced, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(if (state.useApiToken) R.string.login_advanced_token else R.string.login_advanced))
                        Icon(
                            if (state.showAdvanced) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                            contentDescription = null,
                        )
                    }
                    if (state.showAdvanced) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.login_use_token), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    stringResource(R.string.login_use_token_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = state.useApiToken, onCheckedChange = onUseApiTokenChange, enabled = !state.isLoading, modifier = Modifier.testTag("login_use_token"))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.login_security_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
