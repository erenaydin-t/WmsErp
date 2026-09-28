package com.wmserp.app.testutil

import com.wmserp.app.core.scanner.ScannerController
import com.wmserp.app.domain.model.ScannedCode
import kotlinx.coroutines.flow.MutableSharedFlow

class FakeScannerController(override val hasHardwareScanner: Boolean = true) : ScannerController {
    override val scans = MutableSharedFlow<ScannedCode>(extraBufferCapacity = 8)
    var retainCount = 0
    var inputActive = false
    val feedbackCalls = mutableListOf<Pair<Boolean, Boolean>>()

    override fun retainCapture() { retainCount++ }
    override fun releaseCapture() { retainCount-- }
    override fun setTextInputActive(active: Boolean) { inputActive = active }
    override fun publish(code: ScannedCode) { scans.tryEmit(code) }
    override fun feedback(beep: Boolean, vibrate: Boolean) { feedbackCalls += beep to vibrate }
}
