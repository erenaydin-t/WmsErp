package com.wmserp.app.core.scanner.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera based barcode scanner (CameraX + ML Kit) used as a fallback on devices without a
 * hardware scanner. The caller must already hold the CAMERA permission.
 */
@Composable
fun CameraBarcodeView(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onBarcode: (value: String, symbology: String?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val callback = rememberUpdatedState(onBarcode)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(lifecycleOwner, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val session = CameraSession(context, lifecycleOwner, previewView) { value, symbology -> callback.value(value, symbology) }
        session.start()
        onDispose { session.stop() }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Owns the CameraX use cases and the ML Kit analyzer for one composition. */
private class CameraSession(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onBarcode: (String, String?) -> Unit,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build()
    )
    private var provider: ProcessCameraProvider? = null
    private var lastValue: String? = null
    private var lastEmitMillis = 0L

    @Volatile
    private var stopped = false

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (stopped) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image -> analyze(image) }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start camera", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        stopped = true
        runCatching { provider?.unbindAll() }
        runCatching { scanner.close() }
        executor.shutdown()
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || stopped) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes -> barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.let(::emit) }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun emit(barcode: Barcode) {
        val value = barcode.rawValue ?: return
        val now = System.currentTimeMillis()
        if (value == lastValue && now - lastEmitMillis < DUPLICATE_WINDOW_MS) return
        lastValue = value
        lastEmitMillis = now
        ContextCompat.getMainExecutor(context).execute { onBarcode(value, formatName(barcode.format)) }
    }

    private fun formatName(format: Int): String? = when (format) {
        Barcode.FORMAT_QR_CODE -> "QR"
        Barcode.FORMAT_EAN_13 -> "EAN-13"
        Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_UPC_A -> "UPC-A"
        Barcode.FORMAT_UPC_E -> "UPC-E"
        Barcode.FORMAT_CODE_128 -> "CODE-128"
        Barcode.FORMAT_CODE_39 -> "CODE-39"
        Barcode.FORMAT_CODE_93 -> "CODE-93"
        Barcode.FORMAT_DATA_MATRIX -> "DATA-MATRIX"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_CODABAR -> "CODABAR"
        Barcode.FORMAT_AZTEC -> "AZTEC"
        else -> null
    }

    private companion object {
        const val TAG = "CameraBarcodeView"
        const val DUPLICATE_WINDOW_MS = 2500L
    }
}
