package com.wmserp.app.presentation.orders

import androidx.lifecycle.SavedStateHandle
import com.wmserp.app.R
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.PurchaseReceipt
import com.wmserp.app.domain.model.ScanLookup
import com.wmserp.app.domain.model.ScanSource
import com.wmserp.app.domain.model.ScanTarget
import com.wmserp.app.domain.model.ScannedCode
import com.wmserp.app.domain.model.ScannerSettings
import com.wmserp.app.domain.usecase.GetPurchaseOrderUseCase
import com.wmserp.app.domain.usecase.LookupScanUseCase
import com.wmserp.app.domain.usecase.ObserveScannerSettingsUseCase
import com.wmserp.app.domain.usecase.ReceivePurchaseOrderUseCase
import com.wmserp.app.domain.usecase.SearchWarehousesUseCase
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.testutil.FakeScannerController
import com.wmserp.app.testutil.MainDispatcherRule
import com.wmserp.app.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReceiveViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getPurchaseOrder: GetPurchaseOrderUseCase = mockk()
    private val receive: ReceivePurchaseOrderUseCase = mockk()
    private val lookupScan: LookupScanUseCase = mockk()
    private val searchWarehouses: SearchWarehousesUseCase = mockk()
    private val observeSettings: ObserveScannerSettingsUseCase = mockk { every { this@mockk.invoke() } returns flowOf(ScannerSettings()) }
    private val scanner = FakeScannerController()

    private fun createViewModel(): ReceiveViewModel {
        coEvery { getPurchaseOrder("PUR-ORD-2026-00001") } returns AppResult.Success(TestFixtures.purchaseOrder)
        coEvery { searchWarehouses("") } returns AppResult.Success(emptyList())
        return ReceiveViewModel(
            getPurchaseOrder, receive, lookupScan, searchWarehouses, observeSettings, scanner,
            SavedStateHandle(mapOf(ReceiveViewModel.ARG_PO_NAME to "PUR-ORD-2026-00001")),
        )
    }

    @Test
    fun `loads the purchase order with zero counted lines and the default warehouse`() = runTest {
        val vm = createViewModel()
        val state = vm.uiState.value
        assertEquals(2, state.lines.size)
        assertEquals(0.0, state.totalQty, 0.0)
        assertEquals("Stores - WM", state.warehouse)
    }

    @Test
    fun `scanning an item code increments its line and caps at the pending quantity`() = runTest {
        val vm = createViewModel()

        repeat(4) { vm.onScanned(ScannedCode("item-002", ScanSource.HARDWARE_KEYBOARD)) }

        val line = vm.uiState.value.lines.first { it.item.itemCode == "ITEM-002" }
        assertEquals("3", line.qtyText)
        assertTrue(line.highlighted)
        val message = vm.uiState.value.message as UiText.Res
        assertEquals(R.string.receive_already_counted, message.id)
        assertEquals(listOf("3", "ITEM-002"), message.args)
        assertEquals(4, scanner.feedbackCalls.size)
    }

    @Test
    fun `scanning a barcode resolves the item through the lookup use case`() = runTest {
        coEvery { lookupScan("8690000000017", ScanTarget.ITEM) } returns AppResult.Success(ScanLookup.ItemFound("8690000000017", TestFixtures.item, emptyList()))
        val vm = createViewModel()

        vm.onScanned(ScannedCode("8690000000017", ScanSource.CAMERA))

        assertEquals("1", vm.uiState.value.lines.first { it.item.itemCode == "ITEM-001" }.qtyText)
    }

    @Test
    fun `submitting posts the counted quantities and marks the receipt complete`() = runTest {
        coEvery { receive(TestFixtures.purchaseOrder, any(), "Stores - WM", true) } returns AppResult.Success(PurchaseReceipt("MAT-PRE-00001", "SUP-001", "To Bill", "2026-09-28", 1))
        val vm = createViewModel()
        vm.receiveAll()
        assertEquals(13.0, vm.uiState.value.totalQty, 0.0)

        vm.submit(asDraft = false)

        assertNotNull(vm.uiState.value.completed)
        assertEquals(UiText.Res(R.string.receive_submitted, listOf("MAT-PRE-00001")), vm.uiState.value.message)
    }
}
