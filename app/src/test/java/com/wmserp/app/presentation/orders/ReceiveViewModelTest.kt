package com.wmserp.app.presentation.orders

import androidx.lifecycle.SavedStateHandle
import com.wmserp.app.R
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.ReceiptDifference
import com.wmserp.app.domain.model.ReceiveResult
import com.wmserp.app.domain.model.RequiredField
import com.wmserp.app.domain.model.ScanLookup
import com.wmserp.app.domain.model.ScanSource
import com.wmserp.app.domain.model.ScanTarget
import com.wmserp.app.domain.model.ScannedCode
import com.wmserp.app.domain.model.ScannerSettings
import com.wmserp.app.domain.usecase.GetPurchaseReceiptUseCase
import com.wmserp.app.domain.usecase.LookupScanUseCase
import com.wmserp.app.domain.usecase.ObserveScannerSettingsUseCase
import com.wmserp.app.domain.usecase.ReceiveLine
import com.wmserp.app.domain.usecase.ReceivePurchaseReceiptUseCase
import com.wmserp.app.domain.usecase.SaveDocumentFieldDefaultsUseCase
import com.wmserp.app.domain.usecase.SearchLinkValuesUseCase
import com.wmserp.app.domain.usecase.SearchWarehousesUseCase
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.testutil.FakeScannerController
import com.wmserp.app.testutil.MainDispatcherRule
import com.wmserp.app.testutil.TestFixtures
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReceiveViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getReceipt: GetPurchaseReceiptUseCase = mockk()
    private val receive: ReceivePurchaseReceiptUseCase = mockk()
    private val searchLinkValues: SearchLinkValuesUseCase = mockk()
    private val saveFieldDefaults: SaveDocumentFieldDefaultsUseCase = mockk()
    private val lookupScan: LookupScanUseCase = mockk()
    private val searchWarehouses: SearchWarehousesUseCase = mockk()
    /** Legacy "+1 per scan" mode by default; the prompt test switches [settings] before creating the view model. */
    private var settings = ScannerSettings(askQuantityOnScan = false)
    private val observeSettings: ObserveScannerSettingsUseCase = mockk { every { this@mockk.invoke() } answers { flowOf(settings) } }
    private val scanner = FakeScannerController()

    private val receipt = TestFixtures.purchaseReceipt
    private val submitted = ReceiveResult(receipt = receipt.copy(docStatus = 1, status = "To Bill", workflowState = "Received"), submitted = true)

    private fun createViewModel(initial: com.wmserp.app.domain.model.PurchaseReceipt = receipt): ReceiveViewModel {
        coEvery { getReceipt(receipt.name) } returns AppResult.Success(initial)
        coEvery { searchWarehouses("") } returns AppResult.Success(emptyList())
        return ReceiveViewModel(
            getReceipt, receive, searchLinkValues, saveFieldDefaults, lookupScan, searchWarehouses, observeSettings, scanner,
            SavedStateHandle(mapOf(ReceiveViewModel.ARG_RECEIPT_NAME to receipt.name)),
        )
    }

    private fun ReceiveViewModel.line(itemCode: String) = uiState.value.lines.first { it.item.itemCode == itemCode }

    @Test
    fun `loads the draft receipt with zero counted lines and its accepted warehouse`() = runTest {
        val vm = createViewModel()
        val state = vm.uiState.value
        assertEquals(2, state.lines.size)
        assertEquals(0.0, state.totalQty, 0.0)
        assertEquals(15.0, state.expectedQty, 0.0)
        assertEquals("Stores - WM", state.warehouse)
        assertTrue(state.canReceive)
        assertFalse(state.canSubmit)
        assertTrue(vm.line("ITEM-001").batchMissing)
    }

    @Test
    fun `a receipt at another workflow stage cannot be counted`() = runTest {
        val vm = createViewModel(receipt.copy(canReceive = false, workflowState = "Pending Approval"))
        assertFalse(vm.uiState.value.canReceive)
        assertEquals(UiText.Res(R.string.receive_not_receivable), vm.uiState.value.error)
        vm.receiveAll()
        assertFalse(vm.uiState.value.canSubmit)
    }

    @Test
    fun `scanning an item code or one of its barcodes counts the row without a server round trip`() = runTest {
        val vm = createViewModel()

        repeat(6) { vm.onScanned(ScannedCode("item-002", ScanSource.HARDWARE_KEYBOARD)) }
        vm.onScanned(ScannedCode("8690000000017", ScanSource.HARDWARE_KEYBOARD))

        assertEquals("5", vm.line("ITEM-002").qtyText)
        assertEquals("1", vm.line("ITEM-001").qtyText)
        val message = vm.uiState.value.message as UiText.Res
        assertEquals(R.string.receive_qty_added, message.id)
        assertEquals(7, scanner.feedbackCalls.size)
        coVerify(exactly = 0) { lookupScan(any(), any()) }
    }

    @Test
    fun `a sixth scan of a complete row reports that everything is counted`() = runTest {
        val vm = createViewModel()
        repeat(5) { vm.onScanned(ScannedCode("ITEM-002", ScanSource.HARDWARE_KEYBOARD)) }

        vm.onScanned(ScannedCode("ITEM-002", ScanSource.HARDWARE_KEYBOARD))

        assertEquals("5", vm.line("ITEM-002").qtyText)
        assertEquals(UiText.Res(R.string.receive_already_counted, listOf("5", "ITEM-002")), vm.uiState.value.message)
        assertTrue(vm.line("ITEM-002").highlighted)
    }

    @Test
    fun `scanning a batch label fills the batch of the batch tracked row and counts it`() = runTest {
        val batch = TestFixtures.batch.copy(name = "LOT-9")
        coEvery { lookupScan("LOT-9", ScanTarget.ITEM) } returns AppResult.Success(ScanLookup.BatchFound("LOT-9", batch, TestFixtures.item, TestFixtures.batchStock))
        val vm = createViewModel()

        vm.onScanned(ScannedCode("LOT-9", ScanSource.CAMERA))

        val line = vm.line("ITEM-001")
        assertEquals("LOT-9", line.batchNo)
        assertEquals("1", line.qtyText)
        assertFalse(line.batchMissing)
    }

    @Test
    fun `an item that is not on the receipt is refused`() = runTest {
        coEvery { lookupScan("OTHER", ScanTarget.ITEM) } returns AppResult.Success(ScanLookup.ItemFound("OTHER", TestFixtures.item.copy(code = "ITEM-999"), emptyList()))
        val vm = createViewModel()

        vm.onScanned(ScannedCode("OTHER", ScanSource.HARDWARE_KEYBOARD))

        assertEquals(UiText.Res(R.string.receive_not_on_receipt, listOf("ITEM-999", receipt.name)), vm.uiState.value.error)
        assertEquals(0.0, vm.uiState.value.totalQty, 0.0)
    }

    @Test
    fun `confirming a receipt counted as drafted submits it straight away`() = runTest {
        val lines = slot<List<ReceiveLine>>()
        coEvery { receive(receipt, capture(lines), "Stores - WM", true) } returns AppResult.Success(submitted)
        val vm = createViewModel()
        vm.receiveAll()
        vm.setBatch("row1", "LOT-1")
        assertEquals(15.0, vm.uiState.value.totalQty, 0.0)

        vm.submit(asDraft = false)

        assertNull(vm.uiState.value.confirmation)
        assertEquals(submitted, vm.uiState.value.completed)
        assertEquals(UiText.Res(R.string.receive_submitted, listOf(receipt.name)), vm.uiState.value.message)
        assertEquals(listOf(ReceiveLine("row1", 10.0, "Stores - WM", "LOT-1"), ReceiveLine("row2", 5.0, "Stores - WM", null)), lines.captured)
    }

    @Test
    fun `counts that differ from the draft are confirmed before the receipt is submitted`() = runTest {
        coEvery { receive(receipt, any(), "Stores - WM", true) } returns AppResult.Success(
            submitted.copy(differences = listOf(ReceiptDifference("row2", "ITEM-002", 5.0, 3.0), ReceiptDifference("row1", "ITEM-001", 10.0, 0.0)), removedRows = listOf("row1"))
        )
        val vm = createViewModel()
        vm.setQty("row2", "3")

        vm.submit(asDraft = false)

        val confirmation = vm.uiState.value.confirmation
        assertNotNull(confirmation)
        assertEquals(listOf(ReceiptDifference("row1", "ITEM-001", 10.0, 0.0), ReceiptDifference("row2", "ITEM-002", 5.0, 3.0)), confirmation!!.differences)
        assertNull(vm.uiState.value.completed)
        coVerify(exactly = 0) { receive(any(), any(), any(), any(), any()) }

        vm.confirmDifferences()

        assertNull(vm.uiState.value.confirmation)
        assertEquals(listOf("row1"), vm.uiState.value.completed?.removedRows)
    }

    @Test
    fun `saving progress stores the counts without asking about differences`() = runTest {
        val saved = ReceiveResult(receipt = receipt, submitted = false)
        coEvery { receive(receipt, any(), "Stores - WM", false) } returns AppResult.Success(saved)
        val vm = createViewModel()
        vm.setQty("row2", "2")

        vm.submit(asDraft = true)

        assertNull(vm.uiState.value.confirmation)
        assertEquals(saved, vm.uiState.value.completed)
        assertEquals(UiText.Res(R.string.receive_progress_saved, listOf(receipt.name)), vm.uiState.value.message)
    }

    @Test
    fun `a site whose workflow does not submit at the warehouse stage is reported as pending approval`() = runTest {
        coEvery { receive(receipt, any(), "Stores - WM", true) } returns AppResult.Success(ReceiveResult(receipt = receipt.copy(workflowState = "Pending Approval"), submitted = false))
        val vm = createViewModel()
        vm.receiveAll()

        vm.submit(asDraft = false)

        assertEquals(UiText.Res(R.string.receive_pending_approval, listOf(receipt.name)), vm.uiState.value.message)
    }

    @Test
    fun `required fields the site added are asked for once, saved and resubmitted`() = runTest {
        val department = RequiredField("Purchase Receipt Item", "department", "Department", "Link", options = "Department")
        coEvery { receive(receipt, any(), "Stores - WM", true, emptyMap()) } returns AppResult.Failure(AppError.MissingRequiredFields(listOf(department)))
        coEvery { searchLinkValues("Department", "", "WM Co") } returns AppResult.Success(listOf("Sales - WM", "Warehouse - WM"))
        coEvery { saveFieldDefaults(any()) } just Runs
        val vm = createViewModel()
        vm.receiveAll()

        vm.submit(asDraft = false)

        assertEquals(listOf(department), vm.uiState.value.requiredFields)
        assertEquals(listOf("Sales - WM", "Warehouse - WM"), vm.uiState.value.linkOptions[department.key])
        assertNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.completed)

        val answers = mapOf(department.key to "Warehouse - WM")
        coEvery { receive(receipt, any(), "Stores - WM", true, answers) } returns AppResult.Success(submitted)
        vm.setRequiredFieldAnswer(department.key, "Warehouse - WM")
        vm.confirmRequiredFields()

        assertTrue(vm.uiState.value.requiredFields.isEmpty())
        assertEquals(submitted, vm.uiState.value.completed)
        coVerify { saveFieldDefaults(answers) }
    }

    @Test
    fun `validation failures of the use case are shown and nothing is marked complete`() = runTest {
        coEvery { receive(receipt, any(), "Stores - WM", true) } returns AppResult.Failure(AppError.Validation("ITEM-001: scan or enter the batch", com.wmserp.app.domain.common.ErrorCode.BATCH_REQUIRED_FOR_ITEM, listOf("ITEM-001")))
        val vm = createViewModel()
        vm.receiveAll()

        vm.submit(asDraft = false)

        assertEquals(UiText.Res(R.string.error_batch_required, listOf("ITEM-001")), vm.uiState.value.error)
        assertNull(vm.uiState.value.completed)
        assertFalse(vm.uiState.value.isSubmitting)
    }

    @Test
    fun `a scan opens the quantity prompt prefilled with what is still expected and confirming counts it`() = runTest {
        settings = ScannerSettings()
        val vm = createViewModel()

        vm.onScanned(ScannedCode("item-002", ScanSource.HARDWARE_KEYBOARD))

        val prompt = vm.uiState.value.pendingScan
        assertNotNull(prompt)
        assertEquals("ITEM-002", prompt!!.itemCode)
        assertEquals(5.0, prompt.remaining, 0.0)
        assertEquals("5", prompt.qtyText)
        assertEquals("0", vm.line("ITEM-002").qtyText)

        vm.onScanned(ScannedCode("item-001", ScanSource.HARDWARE_KEYBOARD)) // ignored while the prompt is open
        assertEquals("ITEM-002", vm.uiState.value.pendingScan?.itemCode)

        vm.setPendingQty("2")
        vm.confirmPendingScan()

        assertNull(vm.uiState.value.pendingScan)
        assertEquals("2", vm.line("ITEM-002").qtyText)
        assertTrue(vm.line("ITEM-002").highlighted)
        assertEquals(UiText.Res(R.string.receive_qty_added, listOf("2", "ITEM-002")), vm.uiState.value.message)

        vm.onScanned(ScannedCode("item-002", ScanSource.HARDWARE_KEYBOARD))
        assertEquals(3.0, vm.uiState.value.pendingScan!!.remaining, 0.0)
        vm.confirmPendingScan()
        assertEquals("5", vm.line("ITEM-002").qtyText)
    }

    @Test
    fun `the stepper allows over-receipt which the differences confirmation then shows`() = runTest {
        val vm = createViewModel()
        vm.receiveAll()

        vm.increment("row2")
        vm.decrement("row1")

        assertEquals("6", vm.line("ITEM-002").qtyText)
        assertEquals("9", vm.line("ITEM-001").qtyText)
        vm.submit(asDraft = false)
        assertEquals(
            listOf(ReceiptDifference("row1", "ITEM-001", 10.0, 9.0), ReceiptDifference("row2", "ITEM-002", 5.0, 6.0)),
            vm.uiState.value.confirmation?.differences,
        )
    }
}
