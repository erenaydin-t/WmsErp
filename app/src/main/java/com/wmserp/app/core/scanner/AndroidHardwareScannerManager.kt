package com.wmserp.app.core.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.wmserp.app.domain.model.ScanSource
import com.wmserp.app.domain.model.ScannedCode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures barcodes from industrial PDA scanners.
 *
 *  * Keyboard wedge mode: [onKeyEvent] is called from `Activity.dispatchKeyEvent`. While a screen has
 *    retained capture and no text field is focused, key presses are consumed (no soft keyboard,
 *    no focus needed) and reassembled by [WedgeDecoder].
 *  * Intent mode: [registerReceivers] listens for vendor broadcasts (Zebra DataWedge, Honeywell,
 *    Urovo, Newland, Sunmi, ...) and [onIntent] handles scans delivered through `startActivity`.
 */
@Singleton
class HardwareScannerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bus: ScanEventBus,
) : ScannerController {

    private val decoder = WedgeDecoder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var flushJob: Job? = null
    private val captureCount = AtomicInteger(0)
    private val consumedKeys = mutableSetOf<Int>()

    @Volatile
    private var textInputActive = false

    private var receiver: BroadcastReceiver? = null

    override val scans: Flow<ScannedCode> = bus.scans

    override val hasHardwareScanner: Boolean by lazy { ScannerDeviceDetector.hasHardwareScanner(context) }

    val captureActive: Boolean get() = captureCount.get() > 0 && !textInputActive

    override fun retainCapture() {
        captureCount.incrementAndGet()
    }

    override fun releaseCapture() {
        if (captureCount.decrementAndGet() <= 0) {
            captureCount.set(0)
            decoder.reset()
        }
    }

    override fun setTextInputActive(active: Boolean) {
        textInputActive = active
        if (active) decoder.reset()
    }

    override fun publish(code: ScannedCode) {
        bus.publish(code)
    }

    /** Returns true when the event was consumed by the scanner decoder. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!captureActive) return false
        val now = System.currentTimeMillis()

        @Suppress("DEPRECATION")
        if (event.action == KeyEvent.ACTION_MULTIPLE && event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            val chars = event.characters ?: return false
            decoder.onCharacters(chars, now)
            scheduleIdleFlush()
            return true
        }

        val isTerminator = event.keyCode in TERMINATOR_KEYS
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (isTerminator) {
                    if (!decoder.hasPending) return false
                    flushJob?.cancel()
                    decoder.onTerminator(now)?.let { emit(it) }
                    consumedKeys += event.keyCode
                    return true
                }
                val unicode = event.unicodeChar
                if (unicode == 0 || Character.isISOControl(unicode)) return false
                decoder.onCharacter(unicode.toChar(), now)
                scheduleIdleFlush()
                consumedKeys += event.keyCode
                return true
            }
            KeyEvent.ACTION_UP -> return consumedKeys.remove(event.keyCode)
            else -> return false
        }
    }

    /** Handles scans delivered as activity intents (e.g. DataWedge "Send via startActivity"). */
    fun onIntent(intent: Intent?): Boolean {
        val extras = intent?.extras ?: return false
        val map = extras.keySet().associateWith { key ->
            @Suppress("DEPRECATION")
            extras.get(key)
        }
        val parsed = IntentScanParser.parse(map) ?: return false
        bus.publish(ScannedCode(parsed.value, ScanSource.HARDWARE_INTENT, parsed.symbology))
        return true
    }

    fun registerReceivers(activityContext: Context) {
        if (receiver != null) return
        val filter = IntentFilter().apply {
            IntentScanParser.supportedActions.forEach { addAction(it) }
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                onIntent(intent)
            }
        }
        ContextCompat.registerReceiver(activityContext, newReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiver = newReceiver
    }

    fun unregisterReceivers(activityContext: Context) {
        receiver?.let { runCatching { activityContext.unregisterReceiver(it) } }
        receiver = null
    }

    override fun feedback(beep: Boolean, vibrate: Boolean) {
        if (beep) {
            runCatching {
                val tone = ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_DURATION_MS)
                scope.launch {
                    delay(TONE_DURATION_MS.toLong() + 50)
                    tone.release()
                }
            }
        }
        if (vibrate) {
            runCatching {
                val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                vibrator?.vibrate(VibrationEffect.createOneShot(VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    private fun scheduleIdleFlush() {
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(IDLE_FLUSH_DELAY_MS)
            decoder.flushIfIdle(System.currentTimeMillis())?.let { emit(it) }
        }
    }

    private fun emit(code: String) {
        bus.publish(ScannedCode(code, ScanSource.HARDWARE_KEYBOARD))
    }

    private companion object {
        val TERMINATOR_KEYS = setOf(
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_DPAD_CENTER,
        )
        const val IDLE_FLUSH_DELAY_MS = 150L
        const val TONE_VOLUME = 80
        const val TONE_DURATION_MS = 120
        const val VIBRATION_MS = 60L
    }
}

/** Heuristics to decide whether the device is an industrial PDA with a hardware scanner. */
object ScannerDeviceDetector {
    private val pdaManufacturers = listOf(
        "zebra", "symbol", "motorola solutions", "honeywell", "datalogic", "urovo", "newland", "sunmi",
        "chainway", "point mobile", "pointmobile", "cipherlab", "bluebird", "unitech", "m3mobile", "m3 mobile",
        "seuic", "keyence", "casio", "denso", "opticon", "janam", "handheld", "idata", "supoin", "mobydata",
        "ipda", "alps", "zkc", "caribe", "emdoor", "hyco", "senter", "speedata",
    )
    private val scannerPackages = listOf(
        "com.symbol.datawedge",
        "com.honeywell.aidc",
        "com.datalogic.decode",
        "com.ubx.usdk",
        "com.newland.scanner",
        "com.sunmi.scanner",
        "com.chainway.scanner",
        "device.apps.emkit",
    )

    fun hasHardwareScanner(context: Context): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        if (pdaManufacturers.any { manufacturer.contains(it) || brand.contains(it) }) return true
        val pm = context.packageManager
        return scannerPackages.any { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        }
    }

    fun hasCamera(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
}
