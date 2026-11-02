package com.wmserp.app.presentation.scan

import androidx.lifecycle.SavedStateHandle
import com.wmserp.app.R
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.ScanLookup
import com.wmserp.app.domain.model.ScanSource
import com.wmserp.app.domain.model.ScanTarget
import com.wmserp.app.domain.model.ScannedCode
import com.wmserp.app.domain.model.ScannerMode
import com.wmserp.app.domain.model.ScannerSettings
import com.wmserp.app.domain.model.CreatedTransfer
import com.wmserp.app.domain.model.RequiredField
import com.wmserp.app.domain.model.TransferChoiceField
import com.wmserp.app.domain.model.TransferForm
import com.wmserp.app.domain.model.TransferLine
import com.wmserp.app.domain.model.TransferRequest
import com.wmserp.app.domain.model.Warehouse
import com.wmserp.app.domain.usecase.CreateStockTransferUseCase
import com.wmserp.app.domain.usecase.GetTransferFormUseCase
import com.wmserp.app.domain.usecase.LookupScanUseCase
import com.wmserp.app.domain.usecase.ObserveScannerSettingsUseCase
import com.wmserp.app.domain.usecase.SaveDocumentFieldDefaultsUseCase
import com.wmserp.app.domain.usecase.SearchLinkValuesUseCase
import com.wmserp.app.domain.usecase.SearchWarehousesUseCase
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.testutil.FakeScannerController
import com.wmserp.app.testutil.MainDispatcherRule
import com.wmserp.app.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScanViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val lookupScan: LookupScanUseCase = mockk()
    private val searchWarehouses: SearchWarehousesUseCase = mockk()
    private val getTransferForm: GetTransferFormUseCase = mockk()
    private val createStockTransfer: CreateStockTransferUseCase = mockk()
    private val searchLinkValues: SearchLinkValuesUseCase = mockk { coEvery { this@mockk.invoke(any(), any(), any()) } returns AppResult.Success(emptyList()) }
    private val saveFieldDefaults: SaveDocumentFieldDefaultsUseCase = mockk()
    private val settings = MutableStateFlow(ScannerSettings(mode = ScannerMode.AUTO))
    private val observeSettings: ObserveScannerSettingsUseCase = mockk { every { this@mockk.invoke() } returns settings }
    private val scanner = FakeScannerController(hasHardwareScanner = true)

    private fun createViewModel(target: ScanTarget? = null) = ScanViewModel(
        lookupScan, searchWarehouses, getTransferForm, createStockTransfer, searchLinkValues, saveFieldDefaults, observeSettings, scanner,
        SavedStateHandle(if (target == null) emptyMap() else mapOf(ScanViewModel.ARG_TARGET to target.name)),
    )

    @Test
    fun `hardware devices default to wedge input and settings can force the camera`() = runTest {
        val vm = createViewModel()
        assertTrue(vm.uiState.value.hasHardwareScanner)
        assertFalse(vm.uiState.value.cameraActive)

        settings.value = ScannerSettings(mode = ScannerMode.CAMERA)
        assertTrue(vm.uiState.value.cameraActive)
    }

    @Test
    fun `a scanned item barcode is resolved, added to history and triggers feedback`() = runTest {
        coEvery { lookupScan("8690000000017", ScanTarget.ITEM) } returns AppResult.Success(ScanLookup.ItemFound("8690000000017", TestFixtures.item, TestFixtures.stock))
        val vm = createViewModel()

        vm.onScanned(ScannedCode("8690000000017\n", ScanSource.HARDWARE_KEYBOARD))

        val state = vm.uiState.value
        assertFalse(state.isLookingUp)
        assertTrue(state.result is ScanLookup.ItemFound)
        assertEquals(1, state.history.size)
        assertTrue(state.history.first().found)
        assertEquals(listOf(true to true), scanner.feedbackCalls)
    }

    @Test
    fun `status text follows the selected target and localizes through resources`() = runTest {
        val vm = createViewModel()
        assertEquals(UiText.Res(R.string.scan_hint_item), vm.uiState.value.statusText)

        vm.setTarget(ScanTarget.WAREHOUSE)

        assertEquals(UiText.Res(R.string.scan_hint_warehouse), vm.uiState.value.statusText)
    }

    @Test
    fun `manual entry does not beep and unknown codes are reported`() = runTest {
        coEvery { lookupScan("nope", ScanTarget.WAREHOUSE) } returns AppResult.Success(ScanLookup.NotFound("nope", ScanTarget.WAREHOUSE))
        val vm = createViewModel(ScanTarget.WAREHOUSE)
        vm.onManualInputChange("nope")

        vm.submitManual()

        assertTrue(vm.uiState.value.result is ScanLookup.NotFound)
        assertFalse(vm.uiState.value.history.first().found)
        assertEquals("", vm.uiState.value.manualInput)
        assertTrue(scanner.feedbackCalls.isEmpty())
    }

    @Test
    fun `lookup failures are shown as errors`() = runTest {
        coEvery { lookupScan(any(), any()) } returns AppResult.Failure(AppError.Network("offline"))
        val vm = createViewModel()

        vm.onScanned(ScannedCode("X1", ScanSource.CAMERA))

        assertEquals(UiText.Res(R.string.error_network_unreachable), vm.uiState.value.error)
        assertNull(vm.uiState.value.result)
    }

    private val siteForm = TransferForm(
        department = TransferChoiceField("department", "Department", "Department"),
        receivedBy = TransferChoiceField("custom_received_by", "Received By (Employee)", "Employee"),
        hasBatchNo = true,
    )

    @Test
    fun `an item transfer asks department and receiver and creates a draft with automatic batches`() = runTest {
        coEvery { lookupScan(any(), ScanTarget.ITEM) } returns AppResult.Success(ScanLookup.ItemFound("ITEM-001", TestFixtures.item, TestFixtures.stock))
        coEvery { searchWarehouses("") } returns AppResult.Success(listOf(Warehouse("Stores - WM", "Stores"), Warehouse("Finished Goods - WM", "Finished Goods")))
        coEvery { getTransferForm("ITEM-001") } returns AppResult.Success(siteForm)
        coEvery { searchLinkValues("Department", any(), any()) } returns AppResult.Success(listOf("Warehouse - E", "Lab - E"))
        val request = TransferRequest("ITEM-001", 5.0, "Stores - WM", "Finished Goods - WM", batchNo = null, department = "Lab - E", receivedBy = "EMP-007")
        coEvery { createStockTransfer(request, emptyMap()) } returns
            AppResult.Success(CreatedTransfer("MAT-STE-00001", 0, listOf(TransferLine("B-OLD", 3.0), TransferLine("B-NEW", 2.0))))
        val vm = createViewModel()
        vm.onScanned(ScannedCode("ITEM-001", ScanSource.MANUAL))

        vm.openTransfer()
        val sheet = vm.uiState.value.transfer
        assertEquals("Stores - WM", sheet.fromWarehouse)
        assertEquals(2, sheet.warehouses.size)
        assertTrue(sheet.autoBatches)
        assertEquals(listOf("Warehouse - E", "Lab - E"), sheet.departmentOptions)
        vm.onTransferToChange("Finished Goods - WM")
        vm.onTransferQtyChange("5")
        assertFalse(vm.uiState.value.transfer.canSubmit)
        vm.onTransferDepartmentChange("Lab - E")
        assertFalse(vm.uiState.value.transfer.canSubmit)
        vm.onTransferReceivedByChange("EMP-007")
        assertTrue(vm.uiState.value.transfer.canSubmit)

        vm.submitTransfer()

        assertFalse(vm.uiState.value.transfer.visible)
        val message = vm.uiState.value.message as UiText.Res
        assertEquals(R.string.transfer_draft_created, message.id)
        assertTrue(message.args.contains("MAT-STE-00001"))
    }

    @Test
    fun `a batch transfer moves the scanned batch and asks the site's other fields without remembering Received By`() = runTest {
        coEvery { lookupScan(any(), ScanTarget.ITEM) } returns AppResult.Success(ScanLookup.BatchFound("B-001", TestFixtures.batch, TestFixtures.item, TestFixtures.batchStock))
        coEvery { searchWarehouses("") } returns AppResult.Success(listOf(Warehouse("Stores - WM", "Stores"), Warehouse("Finished Goods - WM", "Finished Goods")))
        coEvery { getTransferForm(any()) } returns AppResult.Success(TransferForm(hasBatchNo = true))
        val costCenter = RequiredField("Stock Entry", "cost_center", "Cost Center", "Link", "Cost Center")
        val other = RequiredField("Stock Entry", "custom_checked_by", "Checked By", "Data", remember = false)
        coEvery { createStockTransfer(any(), emptyMap()) } returns AppResult.Failure(AppError.MissingRequiredFields(listOf(costCenter, other)))
        val answers = mapOf(costCenter.key to "Main - E", other.key to "Ali")
        coEvery { createStockTransfer(any(), answers) } returns AppResult.Success(CreatedTransfer("MAT-STE-00002"))
        coEvery { saveFieldDefaults(mapOf(costCenter.key to "Main - E")) } returns Unit
        val vm = createViewModel()
        vm.onScanned(ScannedCode("B-001", ScanSource.MANUAL))

        vm.openTransfer()
        assertEquals(TestFixtures.batch.name, vm.uiState.value.transfer.batchNo)
        assertFalse(vm.uiState.value.transfer.autoBatches)
        vm.onTransferToChange("Finished Goods - WM")
        vm.onTransferQtyChange("2")
        vm.submitTransfer()
        assertEquals(listOf(costCenter, other), vm.uiState.value.transfer.requiredFields)

        vm.setTransferFieldAnswer(costCenter.key, "Main - E")
        vm.setTransferFieldAnswer(other.key, "Ali")
        vm.confirmTransferFields()

        coVerify(exactly = 1) { saveFieldDefaults(mapOf(costCenter.key to "Main - E")) }
        coVerify(exactly = 1) { createStockTransfer(match { it.batchNo == TestFixtures.batch.name && it.qty == 2.0 }, answers) }
        assertEquals(R.string.transfer_draft_created, (vm.uiState.value.message as UiText.Res).id)
    }
}
