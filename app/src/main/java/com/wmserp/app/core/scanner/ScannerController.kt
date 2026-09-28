package com.wmserp.app.core.scanner

import com.wmserp.app.domain.model.ScannedCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * UI-facing contract for the hardware scanner integration.
 * The Android implementation ([HardwareScannerManager]) captures keyboard-wedge key events and
 * vendor broadcast intents; screens only ever talk to this interface.
 */
interface ScannerController {
    /** Every barcode captured from any source. */
    val scans: Flow<ScannedCode>

    /** True when the device looks like an industrial PDA with a built-in scanner. */
    val hasHardwareScanner: Boolean

    /** A screen that wants keyboard-wedge input calls this while visible... */
    fun retainCapture()

    /** ...and releases it when it leaves the screen. */
    fun releaseCapture()

    /** While a text field is focused, key events must reach the field instead of the decoder. */
    fun setTextInputActive(active: Boolean)

    /** Publishes a code from the camera or manual entry so all listeners are notified uniformly. */
    fun publish(code: ScannedCode)

    /** Audible / haptic confirmation of a scan. */
    fun feedback(beep: Boolean, vibrate: Boolean)
}

/** Used for previews and UI tests. */
object NoOpScannerController : ScannerController {
    override val scans: Flow<ScannedCode> = emptyFlow()
    override val hasHardwareScanner: Boolean = false
    override fun retainCapture() = Unit
    override fun releaseCapture() = Unit
    override fun setTextInputActive(active: Boolean) = Unit
    override fun publish(code: ScannedCode) = Unit
    override fun feedback(beep: Boolean, vibrate: Boolean) = Unit
}
