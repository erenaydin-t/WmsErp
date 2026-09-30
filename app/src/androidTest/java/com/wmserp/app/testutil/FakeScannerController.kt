package com.wmserp.app.testutil

import com.wmserp.app.core.scanner.ScannerController
import com.wmserp.app.domain.model.ScannedCode
import kotlinx.coroutines.flow.MutableSharedFlow

/** Lets UI tests inject hardware scans without a physical scanner. */
class FakeScannerController(override val hasHardwareScanner: Boolean = true) : ScannerController {
    override val scans = MutableSharedFlow<ScannedCode>(extraBufferCapacity = 8)
    var retainCount = 0
        private set

    override fun retainCapture() { retainCount++ }
    override fun releaseCapture() { retainCount-- }
    override fun setTextInputActive(active: Boolean) = Unit
    override fun publish(code: ScannedCode) { scans.tryEmit(code) }
    override fun feedback(beep: Boolean, vibrate: Boolean, error: Boolean) = Unit

    /** Simulates the hardware trigger being pulled. */
    fun scan(value: String) = publish(ScannedCode(value, com.wmserp.app.domain.model.ScanSource.HARDWARE_KEYBOARD))
}
