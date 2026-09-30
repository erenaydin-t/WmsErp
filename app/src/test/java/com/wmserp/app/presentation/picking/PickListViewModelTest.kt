package com.wmserp.app.presentation.picking

import androidx.lifecycle.SavedStateHandle
import com.wmserp.app.R
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.GeneratedDocument
import com.wmserp.app.domain.model.PickList
import com.wmserp.app.domain.model.PickRowStatus
import com.wmserp.app.domain.model.PickingStatus
import com.wmserp.app.domain.model.ScanSource
import com.wmserp.app.domain.model.ScannerMode
import com.wmserp.app.domain.model.ScannedCode
import com.wmserp.app.domain.model.ScannerSettings
import com.wmserp.app.domain.model.WmsQrKeys
import com.wmserp.app.domain.repository.PickListRepository
import com.wmserp.app.domain.usecase.CompletePickRowUseCase
import com.wmserp.app.domain.usecase.GeneratePickDocumentUseCase
import com.wmserp.app.domain.usecase.GetPickListUseCase
import com.wmserp.app.domain.usecase.GetWmsQrKeysUseCase
import com.wmserp.app.domain.usecase.ObserveScannerSettingsUseCase
import com.wmserp.app.domain.usecase.SavePickRowProgressUseCase
import com.wmserp.app.domain.usecase.StartPickRowUseCase
import com.wmserp.app.domain.usecase.ValidatePickScanUseCase
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.testutil.FakeScannerController
import com.wmserp.app.testutil.MainDispatcherRule
import com.wmserp.app.testutil.TestFixtures
import com.wmserp.app.testutil.qrLabel
import com.wmserp.app.testutil.rowUpdate
import com.wmserp.app.testutil.withRow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PickListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository: PickListRepository = mockk()
    /** Legacy "+1 per scan" mode by default; the prompt tests switch [settings] before creating the view model. */
    private var settings = ScannerSettings(askQuantityOnScan = false)
    private val observeSettings: ObserveScannerSettingsUseCase = mockk { every { this@mockk.invoke() } answers { flowOf(settings) } }
    private val scanner = FakeScannerController(hasHardwareScanner = true)

    private val name = TestFixtures.pickList.name
    private val ready = TestFixtures.pickList
    private var now = 1_000_000L

    private fun createViewModel(initial: PickList = ready): PickListViewModel {
        coEvery { repository.getPickList(name) } returns AppResult.Success(initial)
        coEvery { repository.getQrKeys(any()) } returns AppResult.Success(WmsQrKeys.DEFAULT)
        val vm = PickListViewModel(
            GetPickListUseCase(repository),
            GetWmsQrKeysUseCase(repository),
            StartPickRowUseCase(repository),
            SavePickRowProgressUseCase(repository),
            CompletePickRowUseCase(repository),
            GeneratePickDocumentUseCase(repository),
            ValidatePickScanUseCase(),
            observeSettings,
            scanner,
            SavedStateHandle(mapOf(PickListViewModel.ARG_NAME to name)),
        )
        vm.clock = { now }
        return vm
    }

    private fun PickListViewModel.line(rowName: String) = uiState.value.lines.first { it.item.rowName == rowName }

    /** The server echoes the saved quantity; the row completes at the required quantity. */
    private fun mockProgressFor(rowName: String, required: Double, base: () -> PickList, lastPickerOnComplete: Boolean = false) {
        coEvery { repository.saveRowProgress(name, rowName, any(), any(), any(), any()) } answers {
            val qty = arg<Double>(2)
            val complete = qty >= required
            val updated = base().withRow(rowName, qty, if (complete) PickRowStatus.PICKED else PickRowStatus.PICKING)
            AppResult.Success(rowUpdate(updated, rowName, rowCompleted = complete, lastPicker = complete && lastPickerOnComplete && updated.allRowsPicked))
        }
    }

    @Test
    fun `shows only the rows assigned to me and makes the first open one active`() = runTest {
        val vm = createViewModel()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf("prow1", "prow2"), state.lines.map { it.item.rowName })
        assertEquals("prow1", state.activeRowName)
        assertEquals(1, state.otherRows)
        assertTrue(state.canScan)
        assertFalse(state.canComplete)
        assertNull(state.outcome)
        assertEquals(WmsQrKeys.DEFAULT, state.qrKeys)
    }

    @Test
    fun `a matching QR label adds one and syncs the partial quantity together with the label`() = runTest {
        mockProgressFor("prow1", 10.0, { ready })
        val vm = createViewModel()

        vm.onScanned(ScannedCode(qrLabel("ITEM-001", "B-001"), ScanSource.HARDWARE_KEYBOARD))

        assertEquals(1.0, vm.line("prow1").qty, 0.0)
        assertEquals(PickRowStatus.PICKING, vm.line("prow1").status)
        assertTrue(vm.line("prow1").highlighted)
        assertEquals(UiText.Res(R.string.pick_qty_added, listOf("1", "ITEM-001")), vm.uiState.value.message)
        assertEquals(1, scanner.feedbackCalls.size)
        coVerify(exactly = 1) { repository.saveRowProgress(name, "prow1", 1.0, "ITEM-001", "B-001", any()) }
        assertFalse(vm.line("prow1").syncing)
    }

    @Test
    fun `a wrong batch is rejected with the expected and scanned values and nothing is synced`() = runTest {
        val vm = createViewModel()

        vm.onScanned(ScannedCode(qrLabel("ITEM-001", "B-999"), ScanSource.CAMERA))

        val alert = vm.uiState.value.scanAlert
        assertNotNull(alert)
        assertEquals(ScanAlertKind.WRONG_BATCH, alert!!.kind)
        assertEquals("B-001", alert.expected)
        assertEquals("B-999", alert.scanned)
        assertEquals(UiText.Res(R.string.pick_wrong_batch, listOf("B-001", "B-999")), alert.message)
        assertEquals(0.0, vm.line("prow1").qty, 0.0)
        assertTrue(vm.line("prow1").highlighted)
        assertEquals(1, scanner.errorFeedbackCalls)
        assertTrue(scanner.feedbackCalls.isEmpty())
        coVerify(exactly = 0) { repository.saveRowProgress(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `plain barcodes and unknown items are rejected`() = runTest {
        val vm = createViewModel()

        vm.onScanned(ScannedCode("8690000000017", ScanSource.HARDWARE_KEYBOARD))
        assertEquals(ScanAlertKind.INVALID_QR, vm.uiState.value.scanAlert?.kind)
        assertEquals(UiText.Res(R.string.qr_error_not_json), vm.uiState.value.scanAlert?.message)

        vm.onScanned(ScannedCode(qrLabel("ITEM-003", "B-777"), ScanSource.HARDWARE_KEYBOARD))
        assertEquals(ScanAlertKind.NOT_ASSIGNED, vm.uiState.value.scanAlert?.kind)
        assertEquals(UiText.Res(R.string.pick_item_not_assigned, listOf("ITEM-003")), vm.uiState.value.scanAlert?.message)
        coVerify(exactly = 0) { repository.saveRowProgress(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `scanning beyond the required quantity is refused`() = runTest {
        val full = ready.withRow("prow2", 5.0, PickRowStatus.PICKED)
        val vm = createViewModel(full)

        vm.onScanned(ScannedCode(qrLabel("ITEM-002"), ScanSource.HARDWARE_KEYBOARD))

        assertEquals(ScanAlertKind.ALREADY_COMPLETE, vm.uiState.value.scanAlert?.kind)
        assertEquals(5.0, vm.line("prow2").qty, 0.0)
        coVerify(exactly = 0) { repository.saveRowProgress(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `finishing my rows while others still work ends in Task Completed`() = runTest {
        val start = ready.withRow("prow2", 5.0, PickRowStatus.PICKED)
        var current = start
        coEvery { repository.saveRowProgress(name, "prow1", any(), any(), any(), any()) } answers {
            val qty = arg<Double>(2)
            current = current.withRow("prow1", qty, if (qty >= 10.0) PickRowStatus.PICKED else PickRowStatus.PICKING)
            AppResult.Success(rowUpdate(current, "prow1", rowCompleted = qty >= 10.0, lastPicker = false))
        }
        val vm = createViewModel(start)

        repeat(10) { vm.onScanned(ScannedCode(qrLabel("ITEM-001", "B-001"), ScanSource.HARDWARE_INTENT)) }

        assertEquals(10.0, vm.line("prow1").qty, 0.0)
        assertEquals(PickRowStatus.PICKED, vm.line("prow1").item.rowStatus)
        assertTrue(vm.uiState.value.allMyRowsComplete)
        assertNull(vm.uiState.value.outcome)
        assertFalse(vm.uiState.value.canScan)
        assertTrue(vm.uiState.value.canComplete)

        vm.completePicking()

        assertEquals(PickOutcome.TASK_COMPLETED, vm.uiState.value.outcome)
        assertFalse(vm.uiState.value.canGenerate)
        coVerify(exactly = 0) { repository.completeRow(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the picker who completes the last row of the card gets the document CTA`() = runTest {
        val start = ready.withRow("prow2", 5.0, PickRowStatus.PICKED).withRow("prow3", 3.0, PickRowStatus.PICKED)
        var current = start
        coEvery { repository.saveRowProgress(name, "prow1", any(), any(), any(), any()) } answers {
            val qty = arg<Double>(2)
            val complete = qty >= 10.0
            current = current.withRow("prow1", qty, if (complete) PickRowStatus.PICKED else PickRowStatus.PICKING)
            AppResult.Success(rowUpdate(current, "prow1", rowCompleted = complete, lastPicker = complete))
        }
        coEvery { repository.generateDocument(name) } returns AppResult.Success(GeneratedDocument("Delivery Note", "MAT-DN-00001"))
        val vm = createViewModel(start)

        repeat(10) { vm.onScanned(ScannedCode(qrLabel("ITEM-001", "B-001"), ScanSource.HARDWARE_KEYBOARD)) }

        assertEquals(PickOutcome.CARD_COMPLETED, vm.uiState.value.outcome)
        assertEquals(PickingStatus.PICKED, vm.uiState.value.cardStatus)
        assertEquals(UiText.Res(R.string.pick_completed_message), vm.uiState.value.message)
        assertTrue(vm.uiState.value.canGenerate)

        vm.generateDocument()

        assertEquals("MAT-DN-00001", vm.uiState.value.generatedDocument?.name)
        assertEquals(UiText.Res(R.string.pick_document_created, listOf("Delivery Note", "MAT-DN-00001")), vm.uiState.value.message)
        assertFalse(vm.uiState.value.canGenerate)
        coVerify(exactly = 1) { repository.generateDocument(name) }
    }

    @Test
    fun `Complete picking sends rows the server has not marked yet and applies the last picker rule`() = runTest {
        val start = ready.withRow("prow2", 5.0, PickRowStatus.PICKED).withRow("prow3", 3.0, PickRowStatus.PICKED).withRow("prow1", 10.0, PickRowStatus.PICKING)
        val finished = start.withRow("prow1", 10.0, PickRowStatus.PICKED)
        coEvery { repository.completeRow(name, "prow1", 10.0, null, "B-001", any()) } returns AppResult.Success(rowUpdate(finished, "prow1", rowCompleted = true, lastPicker = true))
        val vm = createViewModel(start)
        assertTrue(vm.uiState.value.canComplete)

        vm.completePicking()

        assertEquals(PickOutcome.CARD_COMPLETED, vm.uiState.value.outcome)
        assertTrue(vm.uiState.value.canGenerate)
        coVerify(exactly = 1) { repository.completeRow(name, "prow1", 10.0, null, "B-001", any()) }
    }

    @Test
    fun `a failed sync rolls the local count back to the server value`() = runTest {
        coEvery { repository.saveRowProgress(name, "prow1", any(), any(), any(), any()) } returns AppResult.Failure(AppError.Network("offline"))
        val vm = createViewModel()

        vm.onScanned(ScannedCode(qrLabel("ITEM-001", "B-001"), ScanSource.HARDWARE_KEYBOARD))

        assertEquals(0.0, vm.line("prow1").qty, 0.0)
        assertFalse(vm.line("prow1").syncing)
        assertEquals(UiText.Res(R.string.error_network_unreachable), vm.uiState.value.error)
    }

    @Test
    fun `selecting a row starts its timer on the server and sends the elapsed time with the scan`() = runTest {
        coEvery { repository.startRow(name, "prow2") } returns AppResult.Success(rowUpdate(ready.withRow("prow2", 0.0, PickRowStatus.PICKING), "prow2", rowCompleted = false))
        mockProgressFor("prow2", 5.0, { ready })
        val vm = createViewModel()

        vm.selectRow("prow2")
        now += 42_000
        vm.onScanned(ScannedCode(qrLabel("ITEM-002"), ScanSource.HARDWARE_KEYBOARD))

        assertEquals("prow2", vm.uiState.value.activeRowName)
        coVerify(exactly = 1) { repository.startRow(name, "prow2") }
        coVerify(exactly = 1) { repository.saveRowProgress(name, "prow2", 1.0, "ITEM-002", null, 42.0) }
    }

    @Test
    fun `an already picked card opens in the post picking state`() = runTest {
        val picked = ready.withRow("prow1", 10.0, PickRowStatus.PICKED).withRow("prow2", 5.0, PickRowStatus.PICKED).withRow("prow3", 3.0, PickRowStatus.PICKED)
        val vm = createViewModel(picked)

        assertEquals(PickOutcome.CARD_COMPLETED, vm.uiState.value.outcome)
        assertTrue(vm.uiState.value.canGenerate)
        assertFalse(vm.uiState.value.canScan)
    }

    // ---- quantity prompt (default setting) --------------------------------------------------

    @Test
    fun `a matching label opens the quantity prompt prefilled with everything still open`() = runTest {
        settings = ScannerSettings()
        val vm = createViewModel()

        vm.onScanned(ScannedCode(qrLabel("ITEM-001", "B-001"), ScanSource.HARDWARE_KEYBOARD))

        val prompt = vm.uiState.value.pendingScan
        assertNotNull(prompt)
        assertEquals("prow1", prompt!!.rowName)
        assertEquals("ITEM-001", prompt.itemCode)
        assertEquals("B-001", prompt.batchNo)
        assertEquals(10.0, prompt.remaining, 0.0)
        assertEquals("10", prompt.qtyText)
        assertTrue(prompt.isValid)
        assertEquals("prow1", vm.uiState.value.activeRowName)
        assertTrue(vm.line("prow1").highlighted)
        assertNotNull(vm.line("prow1").startedAtMillis)
        assertEquals(0.0, vm.line("prow1").qty, 0.0)
        assertFalse(vm.uiState.value.canScan)
        assertEquals(1, scanner.feedbackCalls.size)
        coVerify(exactly = 0) { repository.saveRowProgress(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `confirming the prompt syncs the whole quantity with the label and the elapsed time`() = runTest {
        settings = ScannerSettings()
        mockProgressFor("prow1", 10.0, { ready })
        val vm = createViewModel()

        vm.onScanned(ScannedCode(qrLabel("ITEM-001", "B-001"), ScanSource.CAMERA))
        now += 30_000
        vm.confirmPendingScan()

        assertNull(vm.uiState.value.pendingScan)
        assertEquals(10.0, vm.line("prow1").qty, 0.0)
        assertEquals(PickRowStatus.PICKED, vm.line("prow1").item.rowStatus)
        assertEquals(UiText.Res(R.string.pick_qty_added, listOf("10", "ITEM-001")), vm.uiState.value.message)
        assertEquals("prow2", vm.uiState.value.activeRowName)
        assertTrue(vm.uiState.value.canScan)
        coVerify(exactly = 1) { repository.saveRowProgress(name, "prow1", 10.0, "ITEM-001", "B-001", 30.0) }
    }

    @Test
    fun `the prompt quantity can be lowered but never exceeds what is still open`() = runTest {
        settings = ScannerSettings()
        mockProgressFor("prow1", 10.0, { ready })
        val vm = createViewModel()
        vm.onScanned(ScannedCode(qrLabel("ITEM-001", "B-001"), ScanSource.HARDWARE_KEYBOARD))

        vm.setPendingQty("11")
        assertFalse(vm.uiState.value.pendingScan!!.isValid)
        vm.confirmPendingScan()
        assertNotNull(vm.uiState.value.pendingScan)
        coVerify(exactly = 0) { repository.saveRowProgress(any(), any(), any(), any(), any(), any()) }

        vm.decrementPendingQty()
        assertEquals("10", vm.uiState.value.pendingScan!!.qtyText)
        vm.incrementPendingQty()
        assertEquals("10", vm.uiState.value.pendingScan!!.qtyText)
        vm.setPendingQty("0")
        assertFalse(vm.uiState.value.pendingScan!!.isValid)
        vm.setPendingAll()
        assertEquals("10", vm.uiState.value.pendingScan!!.qtyText)
        vm.setPendingQty("4")
        assertTrue(vm.uiState.value.pendingScan!!.isValid)

        vm.confirmPendingScan()

        assertEquals(4.0, vm.line("prow1").qty, 0.0)
        assertEquals(PickRowStatus.PICKING, vm.line("prow1").status)
        assertEquals(UiText.Res(R.string.pick_qty_added, listOf("4", "ITEM-001")), vm.uiState.value.message)
        coVerify(exactly = 1) { repository.saveRowProgress(name, "prow1", 4.0, "ITEM-001", "B-001", any()) }

        vm.onScanned(ScannedCode(qrLabel("ITEM-001", "B-001"), ScanSource.HARDWARE_KEYBOARD))
        assertEquals(6.0, vm.uiState.value.pendingScan!!.remaining, 0.0)
        assertEquals("6", vm.uiState.value.pendingScan!!.qtyText)
    }

    @Test
    fun `scans are ignored while the prompt is open and cancelling keeps the count`() = runTest {
        settings = ScannerSettings()
        val vm = createViewModel()
        vm.onScanned(ScannedCode(qrLabel("ITEM-001", "B-001"), ScanSource.HARDWARE_KEYBOARD))

        vm.onScanned(ScannedCode(qrLabel("ITEM-002"), ScanSource.HARDWARE_KEYBOARD))
        assertEquals("prow1", vm.uiState.value.pendingScan?.rowName)
        assertNull(vm.uiState.value.scanAlert)
        assertEquals(1, scanner.feedbackCalls.size)

        vm.cancelPendingScan()

        assertNull(vm.uiState.value.pendingScan)
        assertEquals(0.0, vm.line("prow1").qty, 0.0)
        assertNotNull(vm.line("prow1").startedAtMillis)
        assertTrue(vm.uiState.value.canScan)
        coVerify(exactly = 0) { repository.saveRowProgress(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the camera opens by itself only in camera mode`() = runTest {
        settings = ScannerSettings(mode = ScannerMode.CAMERA)
        val camera = createViewModel()
        assertTrue(camera.uiState.value.cameraActive)
        assertFalse(camera.uiState.value.hardwareScannerReady)
        assertTrue(camera.uiState.value.showCameraButton)

        settings = ScannerSettings()
        val pda = createViewModel()
        assertFalse(pda.uiState.value.cameraActive)
        assertTrue(pda.uiState.value.hardwareScannerReady)
        assertFalse(pda.uiState.value.showCameraButton)

        pda.toggleCamera()
        assertTrue(pda.uiState.value.cameraActive)
    }
}
