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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wmserp.app.BuildConfig
import com.wmserp.app.domain.model.ScannerMode
import com.wmserp.app.presentation.components.ErrorBanner
import com.wmserp.app.presentation.components.InfoBanner
import com.wmserp.app.presentation.components.LoadingState
import com.wmserp.app.presentation.components.StatusChip
import com.wmserp.app.presentation.theme.WmsTheme

@Composable
fun ProfileRoute(viewModel: ProfileViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ProfileScreen(
        state = state,
        onFirstNameChange = viewModel::onFirstNameChange,
        onLastNameChange = viewModel::onLastNameChange,
        onPhoneChange = viewModel::onPhoneChange,
        onMobileChange = viewModel::onMobileChange,
        onLocationChange = viewModel::onLocationChange,
        onSave = viewModel::save,
        onOldPasswordChange = viewModel::onOldPasswordChange,
        onNewPasswordChange = viewModel::onNewPasswordChange,
        onConfirmPasswordChange = viewModel::onConfirmPasswordChange,
        onTogglePasswordVisibility = viewModel::togglePasswordVisibility,
        onChangePassword = viewModel::submitPasswordChange,
        onScannerModeChange = viewModel::setScannerMode,
        onBeepChange = viewModel::setBeep,
        onVibrateChange = viewModel::setVibrate,
        onSignOut = viewModel::signOut,
        onRetry = { viewModel.load(forceRefresh = true) },
        onDismissMessages = viewModel::dismissMessages,
    )
}

@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onFirstNameChange: (String) -> Unit,
    onLastNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onMobileChange: (String) -> Unit,
    onLocationChange: (String) -> Unit,
    onSave: () -> Unit,
    onOldPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onChangePassword: () -> Unit,
    onScannerModeChange: (ScannerMode) -> Unit,
    onBeepChange: (Boolean) -> Unit,
    onVibrateChange: (Boolean) -> Unit,
    onSignOut: () -> Unit,
    onRetry: () -> Unit,
    onDismissMessages: () -> Unit,
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
        Text("Profile & settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        if (state.isLoading && state.profile == null) {
            LoadingState(modifier = Modifier.height(200.dp), message = "Loading profile...")
        }
        state.error?.let { ErrorBanner(it, onRetry = onRetry, onDismiss = onDismissMessages) }

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
                    StatusChip(profile.displayRole, colors.kpiPurple, modifier = Modifier.testTag("profile_role"))
                    if (state.serverUrl.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(state.serverUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            SettingsCard(title = "Personal information") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(state.firstName, onFirstNameChange, label = { Text("First name") }, singleLine = true, modifier = Modifier.weight(1f).testTag("profile_first_name"))
                    OutlinedTextField(state.lastName, onLastNameChange, label = { Text("Last name") }, singleLine = true, modifier = Modifier.weight(1f).testTag("profile_last_name"))
                }
                OutlinedTextField(state.phone, onPhoneChange, label = { Text("Phone") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(state.mobileNo, onMobileChange, label = { Text("Mobile") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(state.location, onLocationChange, label = { Text("Location") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                state.saveMessage?.let { InfoBanner(it, container = colors.successContainer, content = MaterialTheme.colorScheme.onSurface) }
                Button(onClick = onSave, enabled = state.canSave, modifier = Modifier.fillMaxWidth().testTag("profile_save")) {
                    if (state.isSaving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) else Text("Save changes")
                }
            }

            SettingsCard(title = "Change password") {
                val visual = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation()
                val toggle: @Composable () -> Unit = {
                    IconButton(onClick = onTogglePasswordVisibility) {
                        Icon(if (state.passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = "Toggle password visibility")
                    }
                }
                OutlinedTextField(state.oldPassword, onOldPasswordChange, label = { Text("Current password") }, singleLine = true, visualTransformation = visual, trailingIcon = toggle, modifier = Modifier.fillMaxWidth().testTag("profile_old_password"), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(state.newPassword, onNewPasswordChange, label = { Text("New password") }, singleLine = true, visualTransformation = visual, modifier = Modifier.fillMaxWidth().testTag("profile_new_password"), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(state.confirmPassword, onConfirmPasswordChange, label = { Text("Confirm new password") }, singleLine = true, visualTransformation = visual, modifier = Modifier.fillMaxWidth().testTag("profile_confirm_password"), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                state.passwordError?.let { ErrorBanner(it, onDismiss = onDismissMessages) }
                state.passwordMessage?.let { InfoBanner(it, container = colors.successContainer, content = MaterialTheme.colorScheme.onSurface) }
                OutlinedButton(onClick = onChangePassword, enabled = state.canChangePassword, modifier = Modifier.fillMaxWidth().testTag("profile_change_password")) {
                    if (state.isChangingPassword) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Update password")
                }
            }
        }

        SettingsCard(title = "Scanner") {
            Text(
                if (state.hasHardwareScanner) "Hardware barcode scanner detected on this device." else "No hardware scanner detected; the camera will be used by default.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ScannerMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.scannerSettings.mode == mode,
                        onClick = { onScannerModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ScannerMode.entries.size),
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        modifier = Modifier.testTag("scanner_mode_${mode.name}"),
                    )
                }
            }
            SettingSwitch("Beep on scan", state.scannerSettings.beepOnScan, onBeepChange)
            SettingSwitch("Vibrate on scan", state.scannerSettings.vibrateOnScan, onVibrateChange)
        }

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
            Text(if (state.isLoggingOut) "Signing out..." else "Sign out")
        }
        Text(
            "WMS ERP v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(8.dp))
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
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
