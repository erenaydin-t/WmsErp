package com.wmserp.app.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wmserp.app.R
import com.wmserp.app.domain.model.PickList
import com.wmserp.app.domain.model.PickListItem
import com.wmserp.app.domain.model.PickListPurpose
import com.wmserp.app.domain.model.PickingStatus
import com.wmserp.app.presentation.picking.PickLineState
import com.wmserp.app.presentation.picking.PickListScreen
import com.wmserp.app.presentation.picking.PickListUiState
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
        picker = "user@example.com",
        itemCount = 2,
        requiredQty = 15.0,
        items = listOf(
            PickListItem("prow1", 1, "ITEM-001", "Steel Bolt M8", "Stores - WM", null, null, null, null, 10.0, 0.0, "Nos"),
            PickListItem("prow2", 2, "ITEM-002", "Steel Nut M8", "Stores - WM", null, "B-001", "2027-01-31", null, 5.0, 0.0, "Nos", hasBatchNo = true),
        ),
    )

    private fun stateFor(list: PickList) = PickListUiState(isLoading = false, pickList = list, lines = list.items.map { PickLineState(it) })

    @Test
    fun completePickingIsDisabledUntilEveryRowHasItsRequiredQuantity() {
        composeRule.setContent { WmsErpTheme { PickHost(stateFor(pickList)) } }

        composeRule.onNodeWithTag("pick_complete").assertIsNotEnabled()
        composeRule.onNodeWithTag("pick_save").assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.pick_row_not_picked)).assertIsDisplayed()

        composeRule.onAllNodes(hasSetTextAction())[0].performTextReplacement("10")
        composeRule.onNodeWithTag("pick_save").assertIsEnabled()
        composeRule.onNodeWithTag("pick_complete").assertIsNotEnabled()

        composeRule.onAllNodes(hasSetTextAction())[1].performTextReplacement("5")
        composeRule.onNodeWithTag("pick_complete").assertIsEnabled()
    }

    @Test
    fun readyStateOnlyOffersStartPicking() {
        composeRule.setContent { WmsErpTheme { PickHost(stateFor(pickList.copy(pickingStatus = PickingStatus.READY_TO_PICK))) } }

        composeRule.onNodeWithTag("pick_start").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag("pick_complete").assertDoesNotExist()
        composeRule.onNodeWithText("Steel Bolt M8").assertIsDisplayed()
    }

    @Test
    fun pickedStateShowsThePurposeSpecificCreateButton() {
        val picked = pickList.copy(pickingStatus = PickingStatus.PICKED, purpose = PickListPurpose.MATERIAL_TRANSFER, purposeLabel = "Material Transfer")
        composeRule.setContent { WmsErpTheme { PickHost(stateFor(picked)) } }

        composeRule.onNodeWithTag("pick_picked_title").assertIsDisplayed()
        composeRule.onNodeWithTag("pick_generate").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText(context.getString(R.string.pick_create_material_transfer)).assertIsDisplayed()
    }
}

/** Hosts the stateless screen with local state so quantity edits flow back into the UI state. */
@Composable
private fun PickHost(initial: PickListUiState) {
    var state by remember { mutableStateOf(initial) }
    fun updateLine(rowName: String, transform: (PickLineState) -> PickLineState) {
        state = state.copy(lines = state.lines.map { if (it.item.rowName == rowName) transform(it) else it })
    }
    PickListScreen(
        state = state,
        onBack = {},
        onStart = {},
        onSetQty = { row, text -> updateLine(row) { it.copy(qtyText = text) } },
        onIncrement = { row -> updateLine(row) { it.copy(qtyText = (it.qty + 1).toInt().toString()) } },
        onDecrement = { row -> updateLine(row) { it.copy(qtyText = (it.qty - 1).coerceAtLeast(0.0).toInt().toString()) } },
        onSetBatch = { row, text -> updateLine(row) { it.copy(batchText = text) } },
        onSave = {},
        onComplete = {},
        onGenerate = {},
        onDismissMessage = { state = state.copy(error = null, message = null) },
        onRetry = {},
    )
}
