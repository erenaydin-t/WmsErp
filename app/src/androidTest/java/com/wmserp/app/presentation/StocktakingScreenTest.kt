package com.wmserp.app.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wmserp.app.R
import com.wmserp.app.domain.model.CountItemStatus
import com.wmserp.app.domain.model.CountSubmission
import com.wmserp.app.domain.model.CountType
import com.wmserp.app.domain.model.CountingMode
import com.wmserp.app.domain.model.MyStocktakingStats
import com.wmserp.app.domain.model.StocktakingItem
import com.wmserp.app.domain.model.StocktakingSession
import com.wmserp.app.domain.model.StocktakingStatus
import com.wmserp.app.domain.model.StocktakingTotals
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.presentation.stocktaking.CountFeedback
import com.wmserp.app.presentation.stocktaking.CountingPhase
import com.wmserp.app.presentation.stocktaking.CountingScreen
import com.wmserp.app.presentation.stocktaking.CountingUiState
import com.wmserp.app.presentation.stocktaking.FeedbackKind
import com.wmserp.app.presentation.theme.WmsErpTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StocktakingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val session = StocktakingSession(
        name = "ST-2026-0001",
        warehouse = "Main - C",
        warehouseName = "Main Warehouse",
        status = StocktakingStatus.COUNTING,
        mode = CountingMode.ASSIGNED,
        totals = StocktakingTotals(total = 5000, counted = 4620, uncounted = 380, pendingReview = 75),
        my = MyStocktakingStats(assigned = 350, open = 120),
        canCount = true,
    )
    private val paracetamol = StocktakingItem(
        name = "r1", itemCode = "PCT-500", itemName = "Paracetamol 500mg", warehouse = "Main - C", batchNo = "PCT-250901", expiryDate = "2028-09-01",
        uom = "Nos", erpQty = 100.0, status = CountItemStatus.ASSIGNED, counter = "me", isMine = true, hasBatchNo = true,
    )

    private fun stateFor(phase: CountingPhase = CountingPhase.Scanning, feedback: CountFeedback? = null, pending: Int = 0, hasHardwareScanner: Boolean = true, blind: Boolean = false) =
        CountingUiState(
            isLoading = false,
            session = session.copy(blindCount = blind),
            items = listOf(paracetamol),
            phase = phase,
            feedback = feedback,
            pending = List(pending) { CountSubmission("ref-$it", session.name, "r1", "PCT-500", "PCT-250901", "Main - C", 1.0, "2026-09-30 10:00:00") },
            hasHardwareScanner = hasHardwareScanner,
            userId = "me",
        )

    @Test
    fun scanningStateShowsTheReadyPanelProgressAndPendingSync() {
        composeRule.setContent { WmsErpTheme { Host(stateFor(pending = 3)) } }

        composeRule.onNodeWithTag("st_ready").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.st_ready_hint_hardware)).assertIsDisplayed()
        composeRule.onNodeWithTag("st_progress").assertTextEquals(context.getString(R.string.st_progress_counted, "4,620", "5,000"))
        composeRule.onNodeWithTag("st_pending").assertTextEquals(context.getString(R.string.st_pending_sync, "3"))
        composeRule.onNodeWithTag("st_sync_button").assertIsDisplayed()
        composeRule.onNodeWithTag("st_camera_button").assertDoesNotExist()
    }

    @Test
    fun phoneWithoutScannerGetsTheCameraButton() {
        composeRule.setContent { WmsErpTheme { Host(stateFor(hasHardwareScanner = false)) } }
        composeRule.onNodeWithTag("st_camera_button").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.st_ready_hint_camera)).assertIsDisplayed()
    }

    @Test
    fun countCardShowsItemBatchExpiryErpQtyAndSubmitsTheTypedQuantity() {
        val submitted = mutableListOf<String>()
        composeRule.setContent { WmsErpTheme { Host(stateFor(phase = CountingPhase.Counting(paracetamol)), onSubmit = { submitted += it }) } }

        composeRule.onNodeWithTag("st_item_name").assertTextEquals("Paracetamol 500mg")
        composeRule.onNodeWithTag("st_batch").assertTextEquals("PCT-250901")
        composeRule.onNodeWithTag("st_erp_qty").assertTextContains("100", substring = true)
        composeRule.onNodeWithTag("st_count_type").assertTextEquals(context.getString(R.string.st_count_type_1))
        composeRule.onNodeWithTag("st_submit").assertIsNotEnabled()

        composeRule.onNodeWithTag("st_qty_input").performTextInput("95")
        composeRule.onNodeWithTag("st_submit").assertIsEnabled().performClick()

        assertEquals(listOf("95"), submitted)
    }

    @Test
    fun sameAsErpChipFillsTheQuantityAndBlindCountHidesIt() {
        composeRule.setContent { WmsErpTheme { Host(stateFor(phase = CountingPhase.Counting(paracetamol))) } }
        composeRule.onNodeWithTag("st_same_as_erp").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("st_qty_input").assertTextContains("100", substring = true)
        composeRule.onNodeWithTag("st_submit").assertIsEnabled()
    }

    @Test
    fun blindCountHidesTheErpQuantity() {
        composeRule.setContent { WmsErpTheme { Host(stateFor(phase = CountingPhase.Counting(paracetamol.copy(erpQty = null)), blind = true)) } }
        composeRule.onNodeWithTag("st_erp_qty").assertTextEquals(context.getString(R.string.st_blind_hint))
        composeRule.onNodeWithTag("st_same_as_erp").assertDoesNotExist()
    }

    @Test
    fun secondCountBannerAndFeedbackBannersAreShown() {
        val second = CountingPhase.Counting(paracetamol.copy(status = CountItemStatus.RECOUNT_REQUIRED, nextCountType = CountType.COUNT_2, count1 = 95.0), CountType.COUNT_2, secondCount = true)
        composeRule.setContent { WmsErpTheme { Host(stateFor(phase = second, feedback = CountFeedback(FeedbackKind.ERROR, UiText.Res(R.string.st_already_counted, listOf("Ali")), id = 1))) } }

        composeRule.onNodeWithTag("st_second_count").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.st_second_count_message, "95")).assertIsDisplayed()
        composeRule.onNodeWithTag("st_count_type").assertTextEquals(context.getString(R.string.st_count_type_2))
        composeRule.onNodeWithTag("st_feedback_ERROR").assertIsDisplayed()
        composeRule.onNodeWithTag("st_feedback_title").assertTextEquals(context.getString(R.string.st_already_counted, "Ali"))
    }

    @Test
    fun batchChooserListsTheBatchesAndSelectsOne() {
        val other = paracetamol.copy(name = "r2", batchNo = "PCT-260101", erpQty = 20.0)
        val selected = mutableListOf<String>()
        composeRule.setContent { WmsErpTheme { Host(stateFor(phase = CountingPhase.ChoosingBatch("PCT-500", "Paracetamol 500mg", listOf(paracetamol, other))), onSelect = { selected += it }) } }

        composeRule.onNodeWithTag("st_choose_batch").assertIsDisplayed()
        composeRule.onNodeWithTag("st_batch_r2").performClick()

        assertEquals(listOf("r2"), selected)
    }

    @Test
    fun closedSessionShowsTheNoticeWithoutScanControls() {
        composeRule.setContent { WmsErpTheme { Host(stateFor().copy(session = session.copy(status = StocktakingStatus.MANAGER_REVIEW))) } }
        composeRule.onNodeWithTag("st_closed").assertIsDisplayed()
        composeRule.onNodeWithTag("st_ready").assertDoesNotExist()
        composeRule.onNodeWithTag("st_camera_toggle").assertDoesNotExist()
    }
}

/** Hosts the stateless screen with local state. */
@Composable
private fun Host(initial: CountingUiState, onSubmit: (String) -> Unit = {}, onSelect: (String) -> Unit = {}) {
    var state by remember { mutableStateOf(initial) }
    CountingScreen(
        state = state,
        onBack = {},
        onToggleCamera = { state = state.copy(cameraActive = !state.cameraActive) },
        onCameraBarcode = { _, _ -> },
        onQtyChange = { text -> state = state.copy(phase = (state.phase as? CountingPhase.Counting)?.copy(qtyText = text) ?: state.phase) },
        onUseErpQty = {
            val counting = state.phase as? CountingPhase.Counting
            if (counting?.item?.erpQty != null) state = state.copy(phase = counting.copy(qtyText = counting.item.erpQty.toLong().toString()))
        },
        onSubmit = { (state.phase as? CountingPhase.Counting)?.let { onSubmit(it.qtyText) } },
        onCancelCount = { state = state.copy(phase = CountingPhase.Scanning) },
        onSelectRow = onSelect,
        onSyncNow = {},
        onDismissFeedback = { state = state.copy(feedback = null, error = null) },
        onRetry = {},
    )
}
