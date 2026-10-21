package com.wmserp.app.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wmserp.app.R
import com.wmserp.app.domain.model.PickList
import com.wmserp.app.domain.model.PickListItem
import com.wmserp.app.domain.model.PickListPurpose
import com.wmserp.app.domain.model.PickingStatus
import com.wmserp.app.presentation.common.ScanQuantityPrompt
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.presentation.picking.PickLineState
import com.wmserp.app.presentation.picking.PickListScreen
import com.wmserp.app.presentation.picking.PickListUiState
import com.wmserp.app.presentation.picking.PickOutcome
import com.wmserp.app.presentation.picking.ScanAlert
import com.wmserp.app.presentation.picking.ScanAlertKind
import com.wmserp.app.presentation.theme.WmsErpTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PickingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val pickList = PickList(
        name = "STO-PICK-2026-00012",
        purpose = PickListPurpose.DELIVERY,
        purposeLabel = "Delivery",
        customerName = "Globex",
        parentWarehouse = "Stores - WM",
        pickingStatus = PickingStatus.PICKING,
        itemCount = 3,
        requiredQty = 18.0,
        myRowCount = 2,
        myOpenRows = 2,
        items = listOf(
            PickListItem("prow1", 1, "ITEM-001", "Steel Bolt M8", "Stores - WM", null, "B-001", "2027-01-31", null, 10.0, 0.0, "Nos", hasBatchNo = true, picker = "me", isMine = true),
            PickListItem("prow2", 2, "ITEM-002", "Steel Nut M8", "Stores - WM", null, null, null, null, 5.0, 0.0, "Nos", picker = "me", isMine = true),
            PickListItem("prow3", 3, "ITEM-003", "Washer M8", "Stores - WM", null, "B-777", null, null, 3.0, 0.0, "Nos", hasBatchNo = true, picker = "other", isMine = false),
        ),
    )

    private fun stateFor(
        list: PickList = pickList,
        qty: Map<String, Double> = emptyMap(),
        outcome: PickOutcome? = null,
        alert: ScanAlert? = null,
        pending: ScanQuantityPrompt? = null,
        hasHardwareScanner: Boolean = false,
    ) = PickListUiState(
        isLoading = false,
        pickList = list,
        lines = list.myItems.map { PickLineState(it, qty = qty[it.rowName] ?: it.pickedQty) },
        activeRowName = "prow1",
        outcome = outcome,
        scanAlert = alert,
        pendingScan = pending,
        hasHardwareScanner = hasHardwareScanner,
    )

    @Test
    fun showsOnlyMyRowsWithTheExpectedBatchAndNoManualInputs() {
        composeRule.setContent { WmsErpTheme { PickHost(stateFor()) } }

        composeRule.onNodeWithTag("pick_line_prow1").assertIsDisplayed()
        composeRule.onNodeWithTag("pick_line_prow2").assertIsDisplayed()
        composeRule.onNodeWithTag("pick_line_prow3").assertDoesNotExist()
        composeRule.onNodeWithTag("pick_expected_batch_prow1").assertTextEquals("B-001")
        composeRule.onNodeWithTag("pick_expected_batch_prow2").assertTextEquals(context.getString(R.string.pick_no_batch))
        composeRule.onNodeWithTag("pick_next_batch").assertTextEquals("B-001")
        composeRule.onNodeWithTag("pick_next_open").assertTextEquals(context.getString(R.string.pick_next_open, "10", "Nos"))
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        composeRule.onNodeWithTag("pick_complete").assertIsNotEnabled()
    }

    @Test
    fun cameraButtonIsOfferedOnlyWithoutAHardwareScanner() {
        composeRule.setContent { WmsErpTheme { PickHost(stateFor(hasHardwareScanner = false)) } }
        composeRule.onNodeWithTag("pick_camera_button").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.pick_scanner_camera)).assertIsDisplayed()
    }

    @Test
    fun pdaSeesTheTriggerHintInsteadOfTheCameraButton() {
        composeRule.setContent { WmsErpTheme { PickHost(stateFor(hasHardwareScanner = true)) } }
        composeRule.onNodeWithTag("pick_camera_button").assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.pick_scanner_ready)).assertIsDisplayed()
    }

    @Test
    fun quantityPromptIsPrefilledWithTheOpenQuantityAndConfirms() {
        val prompt = ScanQuantityPrompt("prow1", "ITEM-001", "Steel Bolt M8", "B-001", remaining = 10.0, uom = "Nos")
        composeRule.setContent { WmsErpTheme { PickHost(stateFor(pending = prompt)) } }

        composeRule.onNodeWithTag("scan_qty_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_qty_input").assertTextContains("10")
        composeRule.onNodeWithTag("scan_qty_remaining").assertTextEquals(context.getString(R.string.scan_qty_remaining, "10", "Nos"))
        composeRule.onNodeWithTag("scan_qty_confirm").assertIsEnabled()
        composeRule.onNodeWithText(context.getString(R.string.scan_qty_confirm, "10")).assertIsDisplayed()

        composeRule.onNodeWithTag("scan_qty_confirm").performClick()
        composeRule.onNodeWithTag("scan_qty_dialog").assertDoesNotExist()
    }

    @Test
    fun completePickingIsEnabledOnlyWhenEveryRowReachedItsRequiredQuantity() {
        composeRule.setContent { WmsErpTheme { PickHost(stateFor(qty = mapOf("prow1" to 10.0, "prow2" to 5.0))) } }

        composeRule.onNodeWithTag("pick_complete").assertIsEnabled()
        composeRule.onNodeWithTag("pick_row_status_prow1").assertTextEquals(context.getString(R.string.pick_row_picked))
    }

    @Test
    fun wrongBatchShowsTheRedAlertWithExpectedAndScanned() {
        val alert = ScanAlert(ScanAlertKind.WRONG_BATCH, UiText.Plain("Wrong batch. Expected: B-001, scanned: B-2"), expected = "B-001", scanned = "B-2")
        composeRule.setContent { WmsErpTheme { PickHost(stateFor(alert = alert)) } }

        composeRule.onNodeWithTag("pick_alert_WRONG_BATCH").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.pick_alert_wrong_batch_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.pick_alert_expected, "B-001")).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.pick_alert_scanned, "B-2")).assertIsDisplayed()
    }

    @Test
    fun taskCompletedStateSendsThePickerBackToTheDashboard() {
        composeRule.setContent { WmsErpTheme { PickHost(stateFor(qty = mapOf("prow1" to 10.0, "prow2" to 5.0), outcome = PickOutcome.TASK_COMPLETED)) } }

        composeRule.onNodeWithTag("pick_task_completed").assertIsDisplayed()
        composeRule.onNodeWithTag("pick_done").assertIsDisplayed()
        composeRule.onNodeWithTag("pick_complete").assertDoesNotExist()
        composeRule.onNodeWithTag("pick_generate").assertDoesNotExist()
    }

    @Test
    fun lastPickerSeesThePurposeSpecificCreateButton() {
        val picked = pickList.copy(purpose = PickListPurpose.MATERIAL_TRANSFER, purposeLabel = "Material Transfer", pickingStatus = PickingStatus.PICKED, allRowsPicked = true, pickedRows = 3)
        composeRule.setContent { WmsErpTheme { PickHost(stateFor(picked, qty = mapOf("prow1" to 10.0, "prow2" to 5.0), outcome = PickOutcome.CARD_COMPLETED)) } }

        composeRule.onNodeWithTag("pick_picked_title").assertIsDisplayed()
        composeRule.onNodeWithTag("pick_generate").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText(context.getString(R.string.pick_create_material_transfer)).assertIsDisplayed()
    }
}

/** Hosts the stateless screen with local state. */
@Composable
private fun PickHost(initial: PickListUiState) {
    var state by remember { mutableStateOf(initial) }
    PickListScreen(
        state = state,
        onBack = {},
        onDone = {},
        onSelectRow = { row -> state = state.copy(activeRowName = row) },
        onToggleCamera = { state = state.copy(cameraActive = !state.cameraActive) },
        onCameraBarcode = { _, _ -> },
        onComplete = {},
        onGenerate = {},
        onDismissMessage = { state = state.copy(error = null, message = null, scanAlert = null) },
        onRetry = {},
        onPendingQtyChange = { text -> state = state.copy(pendingScan = state.pendingScan?.withText(text)) },
        onPendingIncrement = { state = state.copy(pendingScan = state.pendingScan?.plusOne()) },
        onPendingDecrement = { state = state.copy(pendingScan = state.pendingScan?.minusOne()) },
        onPendingAll = { state = state.copy(pendingScan = state.pendingScan?.all()) },
        onConfirmPending = { state = state.copy(pendingScan = null) },
        onCancelPending = { state = state.copy(pendingScan = null) },
    )
}
