package com.wmserp.app.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wmserp.app.R
import com.wmserp.app.domain.model.Item
import com.wmserp.app.domain.model.ScanLookup
import com.wmserp.app.domain.model.ScanTarget
import com.wmserp.app.domain.model.ScannedCode
import com.wmserp.app.domain.model.StockLevel
import com.wmserp.app.presentation.components.LocalScannerController
import com.wmserp.app.presentation.components.ScannerListener
import com.wmserp.app.presentation.scan.ScanScreen
import com.wmserp.app.presentation.scan.ScanUiState
import com.wmserp.app.presentation.theme.WmsErpTheme
import com.wmserp.app.testutil.FakeScannerController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun hardwareScanIsDeliveredToTheScreenListener() {
        val scanner = FakeScannerController(hasHardwareScanner = true)
        val received = mutableListOf<ScannedCode>()
        composeRule.setContent {
            WmsErpTheme {
                CompositionLocalProvider(LocalScannerController provides scanner) {
                    ScannerListener { received += it }
                    ScanHost(ScanUiState(hasHardwareScanner = true, cameraActive = false))
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { scanner.scan("8690000000017") }
        composeRule.waitUntil(timeoutMillis = 5_000) { received.isNotEmpty() }

        assertEquals("8690000000017", received.single().value)
        assertEquals(1, scanner.retainCount)
    }

    @Test
    fun statusTextFollowsTheSelectedTarget() {
        composeRule.setContent { WmsErpTheme { ScanHost(ScanUiState(hasHardwareScanner = true, cameraActive = false)) } }

        composeRule.onNodeWithTag("scan_status").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.scan_hint_item)).assertIsDisplayed()
        composeRule.onNodeWithTag("scan_target_WAREHOUSE").performClick()
        composeRule.onNodeWithText(context.getString(R.string.scan_hint_warehouse)).assertIsDisplayed()
    }

    @Test
    fun manualEntrySubmitsTheTypedCode() {
        var submitted: String? = null
        composeRule.setContent {
            WmsErpTheme { ScanHost(ScanUiState(hasHardwareScanner = false, cameraActive = false), onManualSubmit = { submitted = it }) }
        }

        composeRule.onNodeWithTag("scan_manual_input").performTextInput("ITEM-001")
        composeRule.onNodeWithTag("scan_manual_submit").performClick()

        assertEquals("ITEM-001", submitted)
    }

    @Test
    fun itemResultIsRendered() {
        val item = Item(code = "ITEM-001", name = "Steel Bolt M8", group = "Fasteners", stockUom = "Nos")
        val state = ScanUiState(
            hasHardwareScanner = true,
            cameraActive = false,
            result = ScanLookup.ItemFound("ITEM-001", item, listOf(StockLevel("ITEM-001", "Stores - WM", 120.0))),
        )
        composeRule.setContent { WmsErpTheme { ScanHost(state) } }

        composeRule.onNodeWithTag("scan_result").assertIsDisplayed()
        composeRule.onNodeWithText("Steel Bolt M8").assertIsDisplayed()
        composeRule.onNodeWithText("Stores - WM").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_move_stock").assertIsDisplayed()
    }
}

@Composable
private fun ScanHost(initial: ScanUiState, onManualSubmit: (String) -> Unit = {}) {
    var state by remember { mutableStateOf(initial) }
    ScanScreen(
        state = state,
        onTargetChange = { state = state.copy(target = it) },
        onManualInputChange = { state = state.copy(manualInput = it) },
        onManualSubmit = { onManualSubmit(state.manualInput) },
        onToggleCamera = { state = state.copy(cameraActive = !state.cameraActive) },
        onCameraBarcode = { _, _ -> },
        onClearResult = { state = state.copy(result = null) },
        onDismissMessage = { state = state.copy(error = null, message = null) },
        onOpenTransfer = {},
        onCloseTransfer = {},
        onTransferFromChange = {},
        onTransferToChange = {},
        onTransferQtyChange = {},
        onSubmitTransfer = {},
        onReceivePurchaseReceipt = {},
    )
}
