package com.wmserp.app.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.wmserp.app.core.scanner.NoOpScannerController
import com.wmserp.app.core.scanner.ScannerController
import com.wmserp.app.domain.model.ScannedCode

/** Provided by MainActivity; defaults to a no-op so previews and UI tests work without Hilt. */
val LocalScannerController = staticCompositionLocalOf<ScannerController> { NoOpScannerController }

/**
 * Subscribes the current screen to hardware scanner output while it is STARTED and keeps the
 * keyboard-wedge capture active for as long as the composable is in composition.
 */
@Composable
fun ScannerListener(enabled: Boolean = true, onScan: (ScannedCode) -> Unit) {
    val scanner = LocalScannerController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnScan = rememberUpdatedState(onScan)

    DisposableEffect(scanner, enabled) {
        if (enabled) scanner.retainCapture()
        onDispose {
            if (enabled) {
                scanner.releaseCapture()
                scanner.setTextInputActive(false)
            }
        }
    }

    LaunchedEffect(scanner, enabled, lifecycleOwner) {
        if (!enabled) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            scanner.scans.collect { currentOnScan.value(it) }
        }
    }
}

/** Apply to text fields on scanning screens so wedge input reaches the field while it is focused. */
@Composable
fun Modifier.scannerAwareFocus(): Modifier {
    val scanner = LocalScannerController.current
    return this.onFocusChanged { scanner.setTextInputActive(it.isFocused) }
}
