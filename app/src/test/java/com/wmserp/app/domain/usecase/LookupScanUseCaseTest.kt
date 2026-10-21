package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.ScanLookup
import com.wmserp.app.domain.model.ScanTarget
import com.wmserp.app.domain.model.WmsQrKeys
import com.wmserp.app.domain.repository.InventoryRepository
import com.wmserp.app.domain.repository.PickListRepository
import com.wmserp.app.domain.repository.ReceiptRepository
import com.wmserp.app.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupScanUseCaseTest {

    private val inventory: InventoryRepository = mockk()
    private val receipts: ReceiptRepository = mockk()
    private val pickLists: PickListRepository = mockk { coEvery { getQrKeys(any()) } returns AppResult.Success(WmsQrKeys.DEFAULT) }
    private val useCase = LookupScanUseCase(inventory, receipts, pickLists)

    @Test
    fun `sanitises control characters and resolves an item with stock`() = runTest {
        coEvery { inventory.findItemByBarcode("8690000000017") } returns AppResult.Success(TestFixtures.item)
        coEvery { inventory.getStockLevels("ITEM-001") } returns AppResult.Success(TestFixtures.stock)

        val result = useCase("\u001D8690000000017\r\n", ScanTarget.ITEM)

        val lookup = (result as AppResult.Success).data as ScanLookup.ItemFound
        assertEquals("ITEM-001", lookup.item.code)
        assertEquals(2, lookup.stock.size)
    }

    @Test
    fun `an unknown code is neither an item nor a batch`() = runTest {
        coEvery { inventory.findItemByBarcode("X") } returns AppResult.Success(null)
        coEvery { inventory.getBatch("X") } returns AppResult.Success(null)

        val result = useCase("X", ScanTarget.ITEM)

        assertTrue((result as AppResult.Success).data is ScanLookup.NotFound)
    }

    @Test
    fun `a plain batch number finds the batch with its item and stock per warehouse`() = runTest {
        coEvery { inventory.findItemByBarcode("B-001") } returns AppResult.Success(null)
        coEvery { inventory.getBatch("B-001") } returns AppResult.Success(TestFixtures.batch)
        coEvery { inventory.getItem("ITEM-001") } returns AppResult.Success(TestFixtures.item)
        coEvery { inventory.getBatchStock("B-001") } returns AppResult.Success(TestFixtures.batchStock)

        val result = useCase("B-001", ScanTarget.ITEM)

        val lookup = (result as AppResult.Success).data as ScanLookup.BatchFound
        assertEquals("B-001", lookup.batch.name)
        assertEquals("ITEM-001", lookup.item?.code)
        assertEquals(40.0, lookup.totalQty, 0.0)
    }

    @Test
    fun `a WMS label with a batch answers with the batch, not only its item`() = runTest {
        coEvery { inventory.getBatch("B-001") } returns AppResult.Success(TestFixtures.batch)
        coEvery { inventory.getItem("ITEM-001") } returns AppResult.Success(TestFixtures.item)
        coEvery { inventory.getBatchStock("B-001") } returns AppResult.Success(TestFixtures.batchStock)

        val result = useCase("""{"item_code":"ITEM-001","batch_no":"B-001"}""", ScanTarget.ITEM)

        assertTrue((result as AppResult.Success).data is ScanLookup.BatchFound)
        coVerify(exactly = 0) { inventory.findItemByBarcode(any()) }
    }

    @Test
    fun `a WMS label without a batch answers with the item`() = runTest {
        coEvery { inventory.findItemByBarcode("ITEM-001") } returns AppResult.Success(TestFixtures.item)
        coEvery { inventory.getStockLevels("ITEM-001") } returns AppResult.Success(TestFixtures.stock)

        val result = useCase("""{"item_code":"ITEM-001"}""", ScanTarget.ITEM)

        val lookup = (result as AppResult.Success).data as ScanLookup.ItemFound
        assertEquals("ITEM-001", lookup.item.code)
    }

    @Test
    fun `resolves purchase receipts and propagates failures`() = runTest {
        coEvery { receipts.getPurchaseReceipt("MAT-PRE-2026-00001") } returns AppResult.Success(TestFixtures.purchaseReceipt)
        coEvery { receipts.getPurchaseReceipt("MISSING") } returns AppResult.Success(null)
        coEvery { receipts.getPurchaseReceipt("BAD") } returns AppResult.Failure(AppError.Network())

        val found = useCase("MAT-PRE-2026-00001", ScanTarget.PURCHASE_RECEIPT)
        val missing = useCase("MISSING", ScanTarget.PURCHASE_RECEIPT)
        val failed = useCase("BAD", ScanTarget.PURCHASE_RECEIPT)

        assertTrue((found as AppResult.Success).data is ScanLookup.PurchaseReceiptFound)
        assertTrue((missing as AppResult.Success).data is ScanLookup.NotFound)
        assertTrue(failed is AppResult.Failure)
    }

    @Test
    fun `empty code is a validation error`() = runTest {
        val result = useCase("   ", ScanTarget.WAREHOUSE)
        assertTrue((result as AppResult.Failure).error is AppError.Validation)
    }
}
