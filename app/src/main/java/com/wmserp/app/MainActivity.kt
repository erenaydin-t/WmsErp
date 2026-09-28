package com.wmserp.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.wmserp.app.core.scanner.HardwareScannerManager
import com.wmserp.app.presentation.WmsErpApp
import com.wmserp.app.presentation.components.LocalScannerController
import com.wmserp.app.presentation.theme.WmsErpTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var scannerManager: HardwareScannerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        scannerManager.onIntent(intent)
        setContent {
            WmsErpTheme {
                CompositionLocalProvider(LocalScannerController provides scannerManager) {
                    WmsErpApp()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        scannerManager.registerReceivers(this)
    }

    override fun onStop() {
        scannerManager.unregisterReceivers(this)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        scannerManager.onIntent(intent)
    }

    /** Hardware scanners in keyboard-wedge mode deliver barcodes as key events. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (scannerManager.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
