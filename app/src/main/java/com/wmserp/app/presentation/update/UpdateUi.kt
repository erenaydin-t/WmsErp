package com.wmserp.app.presentation.update

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wmserp.app.R
import com.wmserp.app.core.update.AndroidApkInstaller
import com.wmserp.app.core.util.Formatters
import com.wmserp.app.domain.model.UpdateState
import com.wmserp.app.presentation.common.asString
import com.wmserp.app.presentation.common.toUiText
import java.io.File

/** Whether the dashboard should show the update banner for this state. */
val UpdateState.showsBanner: Boolean
    get() = when (this) {
        is UpdateState.Available -> !dismissed
        is UpdateState.Downloading, is UpdateState.ReadyToInstall -> true
        is UpdateState.Failed -> release != null
        else -> false
    }

/**
 * Hands the downloaded APK to the system installer. On Android 8+ the user must first allow the app
 * to install unknown apps; in that case the settings screen is opened and false is returned.
 */
fun installUpdate(context: Context, state: UpdateState.ReadyToInstall): Boolean {
    if (!AndroidApkInstaller.canInstall(context)) {
        AndroidApkInstaller.openUnknownSourcesSettings(context)
        return false
    }
    return AndroidApkInstaller.install(context, File(state.apkPath))
}

/** Remembers which downloads already opened the installer by themselves, so revisiting a screen does not re-prompt. */
private val autoPrompted = mutableSetOf<String>()

@Composable
private fun AutoInstallOnce(state: UpdateState.ReadyToInstall) {
    val context = LocalContext.current
    LaunchedEffect(state.apkPath) {
        if (autoPrompted.add(state.apkPath)) installUpdate(context, state)
    }
}

@Composable
fun UpdateState.statusText(): String = when (this) {
    UpdateState.Idle -> stringResource(R.string.update_idle)
    UpdateState.Checking -> stringResource(R.string.update_checking)
    is UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
    UpdateState.NoRelease -> stringResource(R.string.update_no_release)
    is UpdateState.Available -> stringResource(R.string.update_available_title, release.version)
    is UpdateState.Downloading -> stringResource(R.string.update_downloading, (fraction * 100).toInt().toString())
    is UpdateState.ReadyToInstall -> stringResource(R.string.update_ready)
    is UpdateState.Failed -> stringResource(R.string.update_failed, error.toUiText().asString())
}

/** Compact card at the top of the dashboard while an update is offered, downloading or ready. */
@Composable
fun UpdateBanner(state: UpdateState, onDownload: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val release = state.offeredRelease ?: return
    if (!state.showsBanner) return
    if (state is UpdateState.ReadyToInstall) AutoInstallOnce(state)
    Card(
        modifier = modifier.fillMaxWidth().testTag("update_banner"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    text = when (state) {
                        is UpdateState.ReadyToInstall -> stringResource(R.string.update_ready_title, release.version)
                        is UpdateState.Failed -> stringResource(R.string.update_failed, state.error.toUiText().asString())
                        else -> stringResource(R.string.update_available_title, release.version)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            when (state) {
                is UpdateState.Downloading -> {
                    LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth().testTag("update_progress"))
                    Text(
                        stringResource(R.string.update_downloading, (state.fraction * 100).toInt().toString()) +
                            "  ·  " + Formatters.fileSize(state.downloadedBytes) + " / " + Formatters.fileSize(release.apkSizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                is UpdateState.ReadyToInstall -> {
                    Text(stringResource(R.string.update_ready), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Button(onClick = { installUpdate(context, state) }, modifier = Modifier.testTag("update_install")) {
                        Text(stringResource(R.string.update_install))
                    }
                }
                else -> {
                    if (state is UpdateState.Available) {
                        Text(
                            stringResource(R.string.update_available_message, Formatters.fileSize(release.apkSizeBytes)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onDownload, modifier = Modifier.testTag("update_download")) {
                            Text(stringResource(if (state is UpdateState.Failed) R.string.update_retry else R.string.update_download))
                        }
                        TextButton(onClick = onDismiss, modifier = Modifier.testTag("update_later")) {
                            Text(stringResource(R.string.update_later), color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
        }
    }
}

/** Settings card: installed version, manual check, download progress and install. */
@Composable
fun UpdateCard(
    state: UpdateState,
    currentVersion: String,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    if (state is UpdateState.ReadyToInstall) AutoInstallOnce(state)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier.fillMaxWidth().testTag("update_card"),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                stringResource(R.string.update_current_version, currentVersion),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(state.statusText(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("update_status"))

            val release = state.offeredRelease
            if (release != null && release.notes.isNotBlank() && (state is UpdateState.Available || state is UpdateState.ReadyToInstall)) {
                Text(stringResource(R.string.update_release_notes), style = MaterialTheme.typography.labelLarge)
                Text(
                    release.notes.take(600),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state is UpdateState.Downloading) {
                LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    Formatters.fileSize(state.downloadedBytes) + " / " + Formatters.fileSize(release?.apkSizeBytes ?: 0L),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state is UpdateState.ReadyToInstall && !AndroidApkInstaller.canInstall(context)) {
                Text(stringResource(R.string.update_permission_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (state) {
                    is UpdateState.Downloading -> OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.update_cancel)) }
                    is UpdateState.ReadyToInstall -> Button(onClick = { installUpdate(context, state) }, modifier = Modifier.testTag("update_install")) {
                        Text(stringResource(R.string.update_install))
                    }
                    else -> {
                        if (release != null) {
                            Button(onClick = onDownload, modifier = Modifier.testTag("update_download")) {
                                Text(stringResource(if (state is UpdateState.Failed) R.string.update_retry else R.string.update_download))
                            }
                        }
                        OutlinedButton(onClick = onCheck, enabled = state !is UpdateState.Checking, modifier = Modifier.testTag("update_check")) {
                            Text(stringResource(R.string.update_check))
                        }
                    }
                }
            }
        }
    }
}
