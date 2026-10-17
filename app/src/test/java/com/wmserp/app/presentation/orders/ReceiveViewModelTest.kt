package com.wmserp.app.presentation.orders

import androidx.lifecycle.SavedStateHandle
import com.wmserp.app.R
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.BatchCount
import com.wmserp.app.domain.model.BatchItemFacts
import com.wmserp.app.domain.model.BatchLookup
import com.wmserp.app.domain.model.CreatedBatch
import com.wmserp.app.domain.model.NewBatch
import com.wmserp.app.domain.model.ReceiptDifference
import com.wmserp.app.domain.model.ReceiveResult
import com.wmserp.app.domain.model.RequiredField
import com.wmserp.app.domain.model.ScanLookup
import com.wmserp.app.domain.model.ScanSource
import com.wmserp.app.domain.model.ScanTarget
import com.wmserp.app.domain.model.ScannedCode
import com.wmserp.app.domain.model.ScannerSettings
import com.wmserp.app.domain.usecase.CreateBatchUseCase
import com.wmserp.app.domain.usecase.FindBatchUseCase
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
    /** Unless a test says otherwise, no batch carries the scanned number. */
    private val findBatch: FindBatchUseCase = mockk { coEvery { this@mockk.invoke(any(), any(), any()) } returns AppResult.Success(BatchLookup("", emptyList())) }
    private val createBatch: CreateBatchUseCase = mockk()
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
            getReceipt, receive, searchLinkValues, saveFieldDefaults, lookupScan, searchWarehouses, findBatch, createBatch, observeSettings, scanner,
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

        repeat(5) { vm.onScanned(ScannedCode("item-002", ScanSource.HARDWARE_KEYBOARD)) }

        assertEquals("5", vm.line("ITEM-002").qtyText)
        val message = vm.uiState.value.message as UiText.Res
        assertEquals(R.string.receive_qty_added, message.id)
        assertEquals(5, scanner.feedbackCalls.size)
        coVerify(exactly = 0) { lookupScan(any(), any()) }

        // The barcode of a batch tracked row without a batch yet: the quantity has to go under a batch, so the sheet opens.
        vm.onScanned(ScannedCode("8690000000017", ScanSource.HARDWARE_KEYBOARD))
        assertEquals("0", vm.line("ITEM-001").qtyText)
        assertEquals("row1", vm.uiState.value.batchSheet?.rowName)
        coVerify(exactly = 0) { findBatch(any(), any(), any()) }
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
    fun `a second row of the same item takes over once the first one is complete`() = runTest {
        val second = receipt.items[1]
        val twoRows = receipt.copy(items = listOf(second, second.copy(rowName = "row3", idx = 3, qty = 2.0)))
        val vm = createViewModel(twoRows)

        repeat(7) { vm.onScanned(ScannedCode("ITEM-002", ScanSource.HARDWARE_KEYBOARD)) }

        assertEquals("5", vm.uiState.value.lines.first { it.item.rowName == "row2" }.qtyText)
        assertEquals("2", vm.uiState.value.lines.first { it.item.rowName == "row3" }.qtyText)
        vm.onScanned(ScannedCode("ITEM-002", ScanSource.HARDWARE_KEYBOARD))
        assertEquals(7.0, vm.uiState.value.totalQty, 0.0)
        assertEquals(UiText.Res(R.string.receive_already_counted, listOf("5", "ITEM-002")), vm.uiState.value.message)
        coVerify(exactly = 0) { lookupScan(any(), any()) }
    }

    @Test
    fun `a saved count continues from the counted quantities and the expected ones stay intact`() = runTest {
        val resumed = receipt.copy(items = receipt.items.map { if (it.rowName == "row2") it.copy(countedQty = 2.0) else it })
        val vm = createViewModel(resumed)

        assertEquals("2", vm.line("ITEM-002").qtyText)
        assertEquals("0", vm.line("ITEM-001").qtyText)
        assertEquals(5.0, vm.line("ITEM-002").item.qty, 0.0)
        assertEquals(15.0, vm.uiState.value.expectedQty, 0.0)
        assertTrue(vm.uiState.value.canSubmit)

        vm.onScanned(ScannedCode("ITEM-002", ScanSource.HARDWARE_KEYBOARD))
        assertEquals("3", vm.line("ITEM-002").qtyText)
    }

    @Test
    fun `scanning the supplier's batch number finds the batch among the receipt's items and counts it under its row`() = runTest {
        val batch = TestFixtures.batch.copy(name = "12-ITEM-001-LOT-9", batchNo = "LOT-9")
        coEvery { findBatch("LOT-9", null, receipt.name) } returns AppResult.Success(BatchLookup("LOT-9", listOf(batch)))
        val vm = createViewModel()

        vm.onScanned(ScannedCode("LOT-9", ScanSource.CAMERA))

        val line = vm.line("ITEM-001")
        assertEquals("12-ITEM-001-LOT-9", line.batchNo)
        assertEquals(listOf("LOT-9"), line.batches.map { it.label })
        assertEquals(1.0, line.qty, 0.0)
        assertFalse(line.batchMissing)
        assertTrue(line.countsByBatch)
        coVerify(exactly = 0) { lookupScan(any(), any()) }

        // A second scan of the same number counts the same batch line again.
        vm.onScanned(ScannedCode("LOT-9", ScanSource.CAMERA))
        assertEquals(2.0, vm.line("ITEM-001").qty, 0.0)
        assertEquals(1, vm.line("ITEM-001").batches.size)
    }

    @Test
    fun `a WMS QR batch label resolved by the scan lookup also lands under the row`() = runTest {
        val batch = TestFixtures.batch.copy(name = "LOT-9")
        coEvery { findBatch(any(), null, receipt.name) } returns AppResult.Success(BatchLookup("x", emptyList()))
        coEvery { lookupScan("LOT-9", ScanTarget.ITEM) } returns AppResult.Success(ScanLookup.BatchFound("LOT-9", batch, TestFixtures.item, TestFixtures.batchStock))
        val vm = createViewModel()

        vm.onScanned(ScannedCode("LOT-9", ScanSource.CAMERA))

        val line = vm.line("ITEM-001")
        assertEquals("LOT-9", line.batchNo)
        assertEquals(1.0, line.qty, 0.0)
    }

    @Test
    fun `a number no batch has opens the sheet for the only batch tracked row and creating the batch counts it`() = runTest {
        coEvery { findBatch("NEW-7", null, receipt.name) } returns AppResult.Success(BatchLookup("NEW-7", emptyList()))
        coEvery { lookupScan("NEW-7", ScanTarget.ITEM) } returns AppResult.Success(ScanLookup.NotFound("NEW-7", ScanTarget.ITEM))
        val expiring = receipt.copy(items = receipt.items.map { if (it.rowName == "row1") it.copy(hasExpiryDate = true, shelfLifeDays = 730) else it })
        val vm = createViewModel(expiring)

        vm.onScanned(ScannedCode("NEW-7", ScanSource.HARDWARE_KEYBOARD))

        val sheet = vm.uiState.value.batchSheet
        assertNotNull(sheet)
        assertEquals("row1", sheet!!.rowName)
        assertEquals("NEW-7", sheet.code)
        assertTrue(sheet.isNew)
        assertEquals(java.time.LocalDate.now().toString(), sheet.manufacturingDate)
        assertEquals(java.time.LocalDate.now().plusDays(730).toString(), sheet.expiryDate)

        vm.setBatchManufacturingDate("2026-10-01")
        assertEquals("2028-09-30", vm.uiState.value.batchSheet!!.expiryDate)

        val request = slot<NewBatch>()
        val created = TestFixtures.batch.copy(name = "13-ITEM-001-NEW-7", batchNo = "NEW-7", expiryDate = "2028-09-30")
        coEvery { createBatch(capture(request)) } returns AppResult.Success(CreatedBatch(created, created = true))
        vm.createBatchFromSheet()

        assertNull(vm.uiState.value.batchSheet)
        assertEquals(NewBatch("ITEM-001", "NEW-7", "2026-10-01", "2028-09-30", "SUP-001", receipt.name), request.captured)
        val line = vm.line("ITEM-001")
        assertEquals(listOf("13-ITEM-001-NEW-7"), line.batches.map { it.batchNo })
        assertTrue(line.batches.single().isNew)
        assertEquals(1.0, line.qty, 0.0)
    }

    @Test
    fun `the batch sheet looks a typed number up for its row and several matches are offered`() = runTest {
        val a = TestFixtures.batch.copy(name = "1-ITEM-001-LOT-5", batchNo = "LOT-5")
        val b = TestFixtures.batch.copy(name = "2-ITEM-001-LOT-5", batchNo = "LOT-5")
        coEvery { findBatch("LOT-5", "ITEM-001", null) } returns AppResult.Success(BatchLookup("LOT-5", listOf(a, b), BatchItemFacts("ITEM-001", hasBatchNo = true)))
        val vm = createViewModel()

        vm.openBatchSheet("row1")
        vm.setBatchCode("LOT-5")
        vm.lookupBatch()

        val sheet = vm.uiState.value.batchSheet!!
        assertEquals(listOf(a, b), sheet.matches)
        assertFalse(sheet.isNew)

        vm.selectFoundBatch(b)

        assertNull(vm.uiState.value.batchSheet)
        assertEquals("2-ITEM-001-LOT-5", vm.line("ITEM-001").batchNo)
        assertEquals(1.0, vm.line("ITEM-001").qty, 0.0)
    }

    @Test
    fun `batches of earlier deliveries carry QC numbers, so the sheet can create another batch with this delivery's QC number`() = runTest {
        val earlier = TestFixtures.batch.copy(name = "1-ITEM-001-LOT-5", batchNo = "LOT-5", qcNo = "5260903080")
        coEvery { findBatch("LOT-5", "ITEM-001", null) } returns AppResult.Success(BatchLookup("LOT-5", listOf(earlier, earlier.copy(name = "2-ITEM-001-LOT-5", qcNo = "5260903081")), BatchItemFacts("ITEM-001", hasBatchNo = true)))
        val vm = createViewModel()
        vm.openBatchSheet("row1")
        vm.setBatchCode("LOT-5")
        vm.lookupBatch()
        assertTrue(vm.uiState.value.batchSheet!!.matchesHaveQc)

        vm.createAnotherBatch()
        val sheet = vm.uiState.value.batchSheet!!
        assertTrue(sheet.isNew)
        assertTrue(sheet.matches.isEmpty())
        vm.setBatchQcNo(" 5260903099 ")

        val request = slot<NewBatch>()
        val created = earlier.copy(name = "3-ITEM-001-LOT-5", qcNo = "5260903099")
        coEvery { createBatch(capture(request)) } returns AppResult.Success(CreatedBatch(created, created = true))
        vm.createBatchFromSheet()

        assertEquals("5260903099", request.captured.qcNo)
        assertEquals("LOT-5", request.captured.batchNo)
        assertEquals("3-ITEM-001-LOT-5", vm.line("ITEM-001").batchNo)
    }

    @Test
    fun `a site that keeps QC numbers refuses to create a batch without one`() = runTest {
        coEvery { findBatch("LOT-8", "ITEM-001", null) } returns AppResult.Success(BatchLookup("LOT-8", emptyList(), BatchItemFacts("ITEM-001", hasBatchNo = true)))
        val vm = createViewModel(receipt.copy(batchQcRequired = true))
        vm.openBatchSheet("row1")
        vm.setBatchCode("LOT-8")
        vm.lookupBatch()
        assertTrue(vm.uiState.value.batchSheet!!.isNew)

        vm.createBatchFromSheet()

        assertNotNull(vm.uiState.value.batchSheet!!.error)
        coVerify(exactly = 0) { createBatch(any()) }
        vm.setBatchQcNo("5260903100")
        val request = slot<NewBatch>()
        coEvery { createBatch(capture(request)) } returns AppResult.Success(CreatedBatch(TestFixtures.batch.copy(name = "9-ITEM-001-LOT-8", batchNo = "LOT-8", qcNo = "5260903100"), created = true))
        vm.createBatchFromSheet()
        assertEquals("5260903100", request.captured.qcNo)
    }

    @Test
    fun `one row received in two batches sends both with their quantities and the row total`() = runTest {
        val a = TestFixtures.batch.copy(name = "1-ITEM-001-A", batchNo = "A")
        val b = TestFixtures.batch.copy(name = "2-ITEM-001-B", batchNo = "B")
        coEvery { findBatch("A", null, receipt.name) } returns AppResult.Success(BatchLookup("A", listOf(a)))
        coEvery { findBatch("B", null, receipt.name) } returns AppResult.Success(BatchLookup("B", listOf(b)))
        val lines = slot<List<ReceiveLine>>()
        coEvery { receive(receipt, capture(lines), "Stores - WM", true) } returns AppResult.Success(submitted)
        val vm = createViewModel()

        repeat(6) { vm.onScanned(ScannedCode("A", ScanSource.HARDWARE_KEYBOARD)) }
        repeat(3) { vm.onScanned(ScannedCode("B", ScanSource.HARDWARE_KEYBOARD)) }
        vm.setBatchQty("row1", "2-ITEM-001-B", "4")
        vm.setQty("row2", "5")

        assertEquals(10.0, vm.line("ITEM-001").qty, 0.0)
        vm.onScanned(ScannedCode("A", ScanSource.HARDWARE_KEYBOARD)) // the row is complete
        assertEquals(UiText.Res(R.string.receive_already_counted, listOf("10", "ITEM-001")), vm.uiState.value.message)

        vm.submit(asDraft = false)
        val confirmation = vm.uiState.value.confirmation!!
        assertTrue(confirmation.differences.isEmpty())
        assertEquals(listOf(BatchCount("1-ITEM-001-A", 6.0, "A"), BatchCount("2-ITEM-001-B", 4.0, "B")), confirmation.summary.first().batches)
        vm.confirmDifferences()

        assertEquals(submitted, vm.uiState.value.completed)
        val row1 = lines.captured.first { it.rowName == "row1" }
        assertEquals(10.0, row1.qty, 0.0)
        assertEquals("1-ITEM-001-A", row1.batchNo)
        assertEquals(listOf(BatchCount("1-ITEM-001-A", 6.0, "A"), BatchCount("2-ITEM-001-B", 4.0, "B")), row1.batches)

        vm.removeBatch("row1", "2-ITEM-001-B")
    }

    @Test
    fun `a saved count with batches continues per batch`() = runTest {
        val resumed = receipt.copy(items = receipt.items.map {
            if (it.rowName == "row1") it.copy(countedQty = 7.0, countedBatches = listOf(BatchCount("1-ITEM-001-A", 4.0, "A"), BatchCount("2-ITEM-001-B", 3.0, "B"))) else it
        })
        val vm = createViewModel(resumed)

        val line = vm.line("ITEM-001")
        assertEquals(listOf("4", "3"), line.batches.map { it.qtyText })
        assertEquals(7.0, line.qty, 0.0)
        assertEquals(3.0, line.remaining, 0.0)
        vm.incrementBatch("row1", "2-ITEM-001-B")
        assertEquals(8.0, vm.line("ITEM-001").qty, 0.0)
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
    fun `confirming a receipt counted as drafted shows the summary without differences and then submits`() = runTest {
        val lines = slot<List<ReceiveLine>>()
        val drafted = receipt.copy(items = receipt.items.map { if (it.rowName == "row1") it.copy(batchNo = "LOT-1", batchLabel = "L1", needsBatch = false) else it })
        coEvery { receive(drafted, capture(lines), "Stores - WM", true) } returns AppResult.Success(submitted)
        val vm = createViewModel(drafted)
        vm.receiveAll()
        assertEquals(15.0, vm.uiState.value.totalQty, 0.0)

        vm.submit(asDraft = false)

        val confirmation = vm.uiState.value.confirmation
        assertNotNull(confirmation)
        assertTrue(confirmation!!.differences.isEmpty())
        assertEquals(listOf("ITEM-001", "ITEM-002"), confirmation.summary.map { it.itemCode })
        assertNull(vm.uiState.value.completed)

        vm.confirmDifferences()

        assertEquals(submitted, vm.uiState.value.completed)
        assertEquals(UiText.Res(R.string.receive_submitted, listOf(receipt.name)), vm.uiState.value.message)
        assertEquals(
            listOf(ReceiveLine("row1", 10.0, "Stores - WM", "LOT-1", listOf(BatchCount("LOT-1", 10.0, "L1"))), ReceiveLine("row2", 5.0, "Stores - WM", null)),
            lines.captured,
        )
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
        vm.confirmDifferences()

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
        vm.confirmDifferences()

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
        vm.confirmDifferences()

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
        val vm = createViewModel(receipt.copy(items = receipt.items.map { if (it.rowName == "row1") it.copy(batchNo = "LOT-1", needsBatch = false) else it }))
        vm.receiveAll()

        vm.increment("row2")
        vm.decrementBatch("row1", "LOT-1")

        assertEquals("6", vm.line("ITEM-002").qtyText)
        assertEquals(9.0, vm.line("ITEM-001").qty, 0.0)
        vm.submit(asDraft = false)
        assertEquals(
            listOf(ReceiptDifference("row1", "ITEM-001", 10.0, 9.0), ReceiptDifference("row2", "ITEM-002", 5.0, 6.0)),
            vm.uiState.value.confirmation?.differences,
        )
    }
}
