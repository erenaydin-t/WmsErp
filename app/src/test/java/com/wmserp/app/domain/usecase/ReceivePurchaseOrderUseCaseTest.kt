package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.PurchaseReceipt
import com.wmserp.app.domain.model.PurchaseReceiptDraft
import com.wmserp.app.domain.repository.OrderRepository
import com.wmserp.app.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceivePurchaseOrderUseCaseTest {

    private val orders: OrderRepository = mockk()
    private val useCase = ReceivePurchaseOrderUseCase(orders)

    @Test
    fun `builds a receipt draft with warehouse fallbacks and skips zero lines`() = runTest {
        val draft = slot<PurchaseReceiptDraft>()
        coEvery { orders.createPurchaseReceipt(capture(draft), true) } returns AppResult.Success(PurchaseReceipt("MAT-PRE-00001", "SUP-001", "To Bill", "2026-09-28", 1))

        val result = useCase(
            purchaseOrder = TestFixtures.purchaseOrder,
            lines = listOf(ReceiveLine("row1", 4.0, null), ReceiveLine("row2", 0.0, null)),
            defaultWarehouse = "Default - WM",
            submit = true,
        )

        assertTrue(result is AppResult.Success)
        assertEquals(1, draft.captured.lines.size)
        assertEquals("Stores - WM", draft.captured.lines.first().warehouse)
        assertEquals("PUR-ORD-2026-00001", draft.captured.purchaseOrderName)
    }

    @Test
    fun `uses set_warehouse when the row has none`() = runTest {
        val draft = slot<PurchaseReceiptDraft>()
        coEvery { orders.createPurchaseReceipt(capture(draft), false) } returns AppResult.Success(PurchaseReceipt("MAT-PRE-00002", "SUP-001", "Draft", null, 0))

        useCase(TestFixtures.purchaseOrder, listOf(ReceiveLine("row2", 3.0, null)), defaultWarehouse = null, submit = false)

        assertEquals("Stores - WM", draft.captured.lines.first().warehouse)
    }

    @Test
    fun `rejects over-receiving and empty receipts`() = runTest {
        val over = useCase(TestFixtures.purchaseOrder, listOf(ReceiveLine("row1", 11.0, null)), null, true)
        val empty = useCase(TestFixtures.purchaseOrder, listOf(ReceiveLine("row1", 0.0, null)), null, true)

        assertTrue((over as AppResult.Failure).error is AppError.Validation)
        assertEquals("Enter at least one quantity to receive", (empty as AppResult.Failure).error.message)
    }
}
