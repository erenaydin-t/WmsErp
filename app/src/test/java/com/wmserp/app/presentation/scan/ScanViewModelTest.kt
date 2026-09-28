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
import com.wmserp.app.domain.model.StockEntry
import com.wmserp.app.domain.model.StockEntryType
import com.wmserp.app.domain.model.Warehouse
import com.wmserp.app.domain.usecase.CreateStockEntryUseCase
import com.wmserp.app.domain.usecase.LookupScanUseCase
import com.wmserp.app.domain.usecase.ObserveScannerSettingsUseCase
import com.wmserp.app.domain.usecase.SearchWarehousesUseCase
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.testutil.FakeScannerController
import com.wmserp.app.testutil.MainDispatcherRule
import com.wmserp.app.testutil.TestFixtures
import io.mockk.coEvery
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
    private val createStockEntry: CreateStockEntryUseCase = mockk()
    private val settings = MutableStateFlow(ScannerSettings(mode = ScannerMode.AUTO))
    private val observeSettings: ObserveScannerSettingsUseCase = mockk { every { this@mockk.invoke() } returns settings }
    private val scanner = FakeScannerController(hasHardwareScanner = true)

    private fun createViewModel(target: ScanTarget? = null) = ScanViewModel(
        lookupScan, searchWarehouses, createStockEntry, observeSettings, scanner,
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

    @Test
    fun `stock transfer sheet prefills the largest warehouse and submits a material transfer`() = runTest {
        coEvery { lookupScan(any(), ScanTarget.ITEM) } returns AppResult.Success(ScanLookup.ItemFound("ITEM-001", TestFixtures.item, TestFixtures.stock))
        coEvery { searchWarehouses("") } returns AppResult.Success(listOf(Warehouse("Stores - WM", "Stores"), Warehouse("Finished Goods - WM", "Finished Goods")))
        coEvery { createStockEntry(StockEntryType.MATERIAL_TRANSFER, "ITEM-001", 5.0, "Stores - WM", "Finished Goods - WM", true, any()) } returns
            AppResult.Success(StockEntry(name = "MAT-STE-00001", type = StockEntryType.MATERIAL_TRANSFER, items = emptyList(), docStatus = 1))
        val vm = createViewModel()
        vm.onScanned(ScannedCode("ITEM-001", ScanSource.MANUAL))

        vm.openTransfer()
        assertEquals("Stores - WM", vm.uiState.value.transfer.fromWarehouse)
        assertEquals(2, vm.uiState.value.transfer.warehouses.size)
        vm.onTransferToChange("Finished Goods - WM")
        vm.onTransferQtyChange("5")
        assertTrue(vm.uiState.value.transfer.canSubmit)

        vm.submitTransfer()

        assertFalse(vm.uiState.value.transfer.visible)
        val message = vm.uiState.value.message as UiText.Res
        assertEquals(R.string.transfer_success, message.id)
        assertTrue(message.args.contains("MAT-STE-00001"))
    }
}
