package com.wmserp.app.presentation.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wmserp.app.BuildConfig
import com.wmserp.app.R
import com.wmserp.app.domain.model.AppLanguage
import com.wmserp.app.domain.model.ScannerMode
import com.wmserp.app.domain.model.UpdateState
import com.wmserp.app.presentation.common.asString
import com.wmserp.app.presentation.common.labelRes
import com.wmserp.app.presentation.components.ErrorBanner
import com.wmserp.app.presentation.components.InfoBanner
import com.wmserp.app.presentation.components.LoadingState
import com.wmserp.app.presentation.components.StatusChip
import com.wmserp.app.presentation.update.UpdateCard
import com.wmserp.app.presentation.theme.WmsTheme

@Composable
fun ProfileRoute(viewModel: ProfileViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    ProfileScreen(
        state = state,
        onOldPasswordChange = viewModel::onOldPasswordChange,
        onNewPasswordChange = viewModel::onNewPasswordChange,
        onConfirmPasswordChange = viewModel::onConfirmPasswordChange,
        onTogglePasswordVisibility = viewModel::togglePasswordVisibility,
        onChangePassword = viewModel::submitPasswordChange,
        onScannerModeChange = viewModel::setScannerMode,
        onBeepChange = viewModel::setBeep,
        onVibrateChange = viewModel::setVibrate,
        onAskQuantityChange = viewModel::setAskQuantity,
        onLanguageChange = viewModel::setLanguage,
        onSignOut = viewModel::signOut,
        onRetry = { viewModel.load(forceRefresh = true) },
        onDismissMessages = viewModel::dismissMessages,
        updateState = updateState,
        currentVersion = viewModel.currentVersion,
        onCheckUpdate = viewModel::checkForUpdate,
        onDownloadUpdate = viewModel::downloadUpdate,
        onCancelUpdate = viewModel::cancelUpdate,
    )
}

@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onOldPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onChangePassword: () -> Unit,
    onScannerModeChange: (ScannerMode) -> Unit,
    onBeepChange: (Boolean) -> Unit,
    onVibrateChange: (Boolean) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onAskQuantityChange: (Boolean) -> Unit = {},
    onSignOut: () -> Unit,
    onRetry: () -> Unit,
    onDismissMessages: () -> Unit,
    updateState: UpdateState = UpdateState.Idle,
    currentVersion: String = BuildConfig.VERSION_NAME,
    onCheckUpdate: () -> Unit = {},
    onDownloadUpdate: () -> Unit = {},
    onCancelUpdate: () -> Unit = {},
) {
    val colors = WmsTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.profile_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        if (state.isLoading && state.profile == null) {
            LoadingState(modifier = Modifier.height(200.dp), message = stringResource(R.string.profile_loading))
        }
        state.error?.let { ErrorBanner(it.asString(), onRetry = onRetry, onDismiss = onDismissMessages) }

        state.profile?.let { profile ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.large, elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .background(Brush.linearGradient(listOf(colors.gradientStart, colors.gradientEnd)), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(profile.initials, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(profile.fullName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("profile_name"))
                    Text(profile.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    val role = profile.displayRole.takeIf { it != "User" } ?: stringResource(R.string.profile_role_default)
                    StatusChip(role, colors.kpiPurple, modifier = Modifier.testTag("profile_role"))
                    if (state.serverUrl.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(state.serverUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // Read-only: what the ERPNext User holds. Personal details are maintained in ERPNext, never here.
            SettingsCard(title = stringResource(R.string.profile_account)) {
                Text(stringResource(R.string.profile_account_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ReadOnlyField(stringResource(R.string.profile_full_name), profile.fullName, tag = "profile_full_name")
                ReadOnlyField(stringResource(R.string.profile_email), profile.email)
                profile.username?.takeIf { it.isNotBlank() }?.let { ReadOnlyField(stringResource(R.string.profile_username), it) }
                profile.phone?.takeIf { it.isNotBlank() }?.let { ReadOnlyField(stringResource(R.string.profile_phone), it) }
                profile.mobileNo?.takeIf { it.isNotBlank() }?.let { ReadOnlyField(stringResource(R.string.profile_mobile), it) }
                profile.location?.takeIf { it.isNotBlank() }?.let { ReadOnlyField(stringResource(R.string.profile_location), it) }
                if (profile.visibleRoles.isNotEmpty()) {
                    Text(stringResource(R.string.profile_roles), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(profile.visibleRoles.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("profile_roles"))
                }
            }

            SettingsCard(title = stringResource(R.string.profile_change_password)) {
                val visual = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation()
                val toggle: @Composable () -> Unit = {
                    IconButton(onClick = onTogglePasswordVisibility) {
                        Icon(if (state.passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = stringResource(R.string.login_toggle_visibility))
                    }
                }
                OutlinedTextField(state.oldPassword, onOldPasswordChange, label = { Text(stringResource(R.string.profile_current_password)) }, singleLine = true, visualTransformation = visual, trailingIcon = toggle, modifier = Modifier.fillMaxWidth().testTag("profile_old_password"), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(state.newPassword, onNewPasswordChange, label = { Text(stringResource(R.string.profile_new_password)) }, singleLine = true, visualTransformation = visual, modifier = Modifier.fillMaxWidth().testTag("profile_new_password"), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(state.confirmPassword, onConfirmPasswordChange, label = { Text(stringResource(R.string.profile_confirm_password)) }, singleLine = true, visualTransformation = visual, modifier = Modifier.fillMaxWidth().testTag("profile_confirm_password"), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                state.passwordError?.let { ErrorBanner(it.asString(), onDismiss = onDismissMessages) }
                state.passwordMessage?.let { InfoBanner(it.asString(), container = colors.successContainer, content = MaterialTheme.colorScheme.onSurface) }
                OutlinedButton(onClick = onChangePassword, enabled = state.canChangePassword, modifier = Modifier.fillMaxWidth().testTag("profile_change_password")) {
                    if (state.isChangingPassword) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.profile_update_password))
                }
            }
        }

        SettingsCard(title = stringResource(R.string.profile_language)) {
            Text(stringResource(R.string.profile_language_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                AppLanguage.entries.forEachIndexed { index, language ->
                    SegmentedButton(
                        selected = state.language == language,
                        onClick = { onLanguageChange(language) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = AppLanguage.entries.size),
                        label = { Text(stringResource(language.labelRes()), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.testTag("language_${language.name}"),
                    )
                }
            }
        }

        SettingsCard(title = stringResource(R.string.profile_scanner)) {
            Text(
                stringResource(if (state.hasHardwareScanner) R.string.profile_scanner_detected else R.string.profile_scanner_not_detected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ScannerMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.scannerSettings.mode == mode,
                        onClick = { onScannerModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ScannerMode.entries.size),
                        label = { Text(stringResource(mode.labelRes()), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.testTag("scanner_mode_${mode.name}"),
                    )
                }
            }
            SettingSwitch(stringResource(R.string.profile_beep), state.scannerSettings.beepOnScan, onBeepChange)
            SettingSwitch(stringResource(R.string.profile_vibrate), state.scannerSettings.vibrateOnScan, onVibrateChange)
            SettingSwitch(stringResource(R.string.profile_ask_quantity), state.scannerSettings.askQuantityOnScan, onAskQuantityChange, tag = "scanner_ask_quantity")
            Text(
                stringResource(R.string.profile_ask_quantity_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        UpdateCard(
            state = updateState,
            currentVersion = currentVersion,
            onCheck = onCheckUpdate,
            onDownload = onDownloadUpdate,
            onCancel = onCancelUpdate,
        )

        OutlinedButton(
            onClick = onSignOut,
            enabled = !state.isLoggingOut,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("profile_sign_out"),
        ) {
            Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(if (state.isLoggingOut) R.string.profile_signing_out else R.string.profile_sign_out))
        }
        Text(
            stringResource(R.string.profile_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ReadOnlyField(label: String, value: String, tag: String? = null) {
    Column(modifier = if (tag != null) Modifier.testTag(tag) else Modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.large, elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String? = null) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
    }
}
