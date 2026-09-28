package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.ScanLookup
import com.wmserp.app.domain.model.ScanTarget
import com.wmserp.app.domain.repository.InventoryRepository
import com.wmserp.app.domain.repository.OrderRepository
import com.wmserp.app.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupScanUseCaseTest {

    private val inventory: InventoryRepository = mockk()
    private val orders: OrderRepository = mockk()
    private val useCase = LookupScanUseCase(inventory, orders)

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
    fun `returns NotFound when barcode is unknown`() = runTest {
        coEvery { inventory.findItemByBarcode("X") } returns AppResult.Success(null)

        val result = useCase("X", ScanTarget.ITEM)

        assertTrue((result as AppResult.Success).data is ScanLookup.NotFound)
    }

    @Test
    fun `resolves purchase orders and propagates failures`() = runTest {
        coEvery { orders.getPurchaseOrder("PUR-ORD-2026-00001") } returns AppResult.Success(TestFixtures.purchaseOrder)
        coEvery { orders.getPurchaseOrder("BAD") } returns AppResult.Failure(AppError.Network())

        val found = useCase("PUR-ORD-2026-00001", ScanTarget.PURCHASE_ORDER)
        val failed = useCase("BAD", ScanTarget.PURCHASE_ORDER)

        assertTrue((found as AppResult.Success).data is ScanLookup.PurchaseOrderFound)
        assertTrue(failed is AppResult.Failure)
    }

    @Test
    fun `empty code is a validation error`() = runTest {
        val result = useCase("   ", ScanTarget.WAREHOUSE)
        assertTrue((result as AppResult.Failure).error is AppError.Validation)
    }
}
