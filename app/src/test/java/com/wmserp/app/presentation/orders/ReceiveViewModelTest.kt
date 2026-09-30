package com.wmserp.app.presentation.orders

import androidx.lifecycle.SavedStateHandle
import com.wmserp.app.R
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.PurchaseReceipt
import com.wmserp.app.domain.model.RequiredField
import com.wmserp.app.domain.model.ScanLookup
import com.wmserp.app.domain.model.ScanSource
import com.wmserp.app.domain.model.ScanTarget
import com.wmserp.app.domain.model.ScannedCode
import com.wmserp.app.domain.model.ScannerSettings
import com.wmserp.app.domain.usecase.GetPurchaseOrderUseCase
import com.wmserp.app.domain.usecase.LookupScanUseCase
import com.wmserp.app.domain.usecase.ObserveScannerSettingsUseCase
import com.wmserp.app.domain.usecase.ReceivePurchaseOrderUseCase
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReceiveViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getPurchaseOrder: GetPurchaseOrderUseCase = mockk()
    private val receive: ReceivePurchaseOrderUseCase = mockk()
    private val searchLinkValues: SearchLinkValuesUseCase = mockk()
    private val saveFieldDefaults: SaveDocumentFieldDefaultsUseCase = mockk()
    private val lookupScan: LookupScanUseCase = mockk()
    private val searchWarehouses: SearchWarehousesUseCase = mockk()
    /** Legacy "+1 per scan" mode by default; the prompt test switches [settings] before creating the view model. */
    private var settings = ScannerSettings(askQuantityOnScan = false)
    private val observeSettings: ObserveScannerSettingsUseCase = mockk { every { this@mockk.invoke() } answers { flowOf(settings) } }
    private val scanner = FakeScannerController()

    private fun createViewModel(): ReceiveViewModel {
        coEvery { getPurchaseOrder("PUR-ORD-2026-00001") } returns AppResult.Success(TestFixtures.purchaseOrder)
        coEvery { searchWarehouses("") } returns AppResult.Success(emptyList())
        return ReceiveViewModel(
            getPurchaseOrder, receive, searchLinkValues, saveFieldDefaults, lookupScan, searchWarehouses, observeSettings, scanner,
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

    @Test
    fun `required fields the site added are asked for once, saved and resubmitted`() = runTest {
        val department = RequiredField("Purchase Receipt Item", "department", "Department", "Link", options = "Department")
        coEvery { receive(TestFixtures.purchaseOrder, any(), "Stores - WM", true, emptyMap()) } returns AppResult.Failure(AppError.MissingRequiredFields(listOf(department)))
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
        coEvery { receive(TestFixtures.purchaseOrder, any(), "Stores - WM", true, answers) } returns AppResult.Success(PurchaseReceipt("MAT-PRE-00002", "SUP-001", "To Bill", "2026-09-28", 1))
        vm.setRequiredFieldAnswer(department.key, "Warehouse - WM")
        vm.confirmRequiredFields()

        assertTrue(vm.uiState.value.requiredFields.isEmpty())
        assertEquals("MAT-PRE-00002", vm.uiState.value.completed?.name)
        coVerify { saveFieldDefaults(answers) }
    }

    @Test
    fun `a scan opens the quantity prompt prefilled with the pending quantity and confirming counts it`() = runTest {
        settings = ScannerSettings()
        val vm = createViewModel()

        vm.onScanned(ScannedCode("item-002", ScanSource.HARDWARE_KEYBOARD))

        val prompt = vm.uiState.value.pendingScan
        assertNotNull(prompt)
        assertEquals("ITEM-002", prompt!!.itemCode)
        assertEquals(3.0, prompt.remaining, 0.0)
        assertEquals("3", prompt.qtyText)
        assertEquals("0", vm.uiState.value.lines.first { it.item.itemCode == "ITEM-002" }.qtyText)

        vm.onScanned(ScannedCode("item-001", ScanSource.HARDWARE_KEYBOARD)) // ignored while the prompt is open
        assertEquals("ITEM-002", vm.uiState.value.pendingScan?.itemCode)

        vm.setPendingQty("2")
        vm.confirmPendingScan()

        assertNull(vm.uiState.value.pendingScan)
        val line = vm.uiState.value.lines.first { it.item.itemCode == "ITEM-002" }
        assertEquals("2", line.qtyText)
        assertTrue(line.highlighted)
        assertEquals(UiText.Res(R.string.receive_qty_added, listOf("2", "ITEM-002")), vm.uiState.value.message)

        vm.onScanned(ScannedCode("item-002", ScanSource.HARDWARE_KEYBOARD))
        assertEquals(1.0, vm.uiState.value.pendingScan!!.remaining, 0.0)
        vm.confirmPendingScan()
        assertEquals("3", vm.uiState.value.lines.first { it.item.itemCode == "ITEM-002" }.qtyText)

        vm.onScanned(ScannedCode("item-002", ScanSource.HARDWARE_KEYBOARD))
        assertNull(vm.uiState.value.pendingScan)
        assertEquals(UiText.Res(R.string.receive_already_counted, listOf("3", "ITEM-002")), vm.uiState.value.message)
    }
}
