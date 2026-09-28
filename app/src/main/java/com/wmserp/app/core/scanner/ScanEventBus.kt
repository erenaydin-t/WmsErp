package com.wmserp.app.core.scanner

import com.wmserp.app.domain.model.ScannedCode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Single stream of barcodes coming from any scanner source (wedge, vendor intent, camera, manual). */
class ScanEventBus {
    private val _scans = MutableSharedFlow<ScannedCode>(extraBufferCapacity = 16)
    val scans: SharedFlow<ScannedCode> = _scans.asSharedFlow()

    fun publish(code: ScannedCode): Boolean = _scans.tryEmit(code)
}
