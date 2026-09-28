package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.common.ErrorCode
import com.wmserp.app.domain.model.BatchAllocation
import com.wmserp.app.domain.model.BatchStock
import com.wmserp.app.domain.model.DeliveryNote
import com.wmserp.app.domain.model.DeliveryNoteDraft
import com.wmserp.app.domain.model.ItemTracking
import com.wmserp.app.domain.model.SalesOrderItem
import com.wmserp.app.domain.repository.OrderRepository
import com.wmserp.app.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DispatchSalesOrderUseCaseTest {

    private val orders: OrderRepository = mockk()
    private val useCase = DispatchSalesOrderUseCase(orders)
    private val created = DeliveryNote("MAT-DN-2026-00001", "CUST-001", "Globex", "Draft", null, 0)

    private val batchItem = SalesOrderItem(rowName = "srow2", itemCode = "20100103", itemName = "Rodiyax C", qty = 9.0, deliveredQty = 0.0, uom = "BOX", warehouse = "Finished Goods - WM", rate = 1000.0, conversionFactor = 12.0)
    private val salesOrder = TestFixtures.salesOrder.copy(items = TestFixtures.salesOrder.items + batchItem)

    private fun AppResult<*>.error(): AppError = (this as AppResult.Failure).error

    @Test
    fun `plain items are dispatched without batches`() = runTest {
        val draft = slot<DeliveryNoteDraft>()
        coEvery { orders.getItemTracking(listOf("ITEM-001")) } returns AppResult.Success(mapOf("ITEM-001" to ItemTracking("ITEM-001", hasBatchNo = false, hasSerialNo = false)))
        coEvery { orders.createDeliveryNote(capture(draft), true) } returns AppResult.Success(created)

        val result = useCase(TestFixtures.salesOrder, listOf(DispatchLine("srow1", 4.0, null)), defaultWarehouse = null, submit = true)

        assertTrue(result is AppResult.Success)
        val line = draft.captured.lines.single()
        assertTrue(line.batches.isEmpty())
        assertEquals("Finished Goods - WM", line.warehouse)
        coVerify(exactly = 0) { orders.getUsableBatches(any(), any()) }
    }

    @Test
    fun `batch tracked items are split first expiry first out in stock units`() = runTest {
        val draft = slot<DeliveryNoteDraft>()
        coEvery { orders.getItemTracking(listOf("20100103")) } returns AppResult.Success(mapOf("20100103" to ItemTracking("20100103", hasBatchNo = true, hasSerialNo = false)))
        coEvery { orders.getUsableBatches("20100103", "Finished Goods - WM") } returns AppResult.Success(
            listOf(BatchStock("B-SOON", 20.0, "2026-10-15"), BatchStock("B-LATE", 40.0, "2027-01-31"))
        )
        coEvery { orders.createDeliveryNote(capture(draft), false) } returns AppResult.Success(created)

        val result = useCase(salesOrder, listOf(DispatchLine("srow2", 2.0, null)), defaultWarehouse = null, submit = false)

        assertTrue(result is AppResult.Success)
        val line = draft.captured.lines.single()
        assertEquals(24.0, line.stockQty, 0.0)
        assertEquals(listOf(BatchAllocation("B-SOON", 20.0), BatchAllocation("B-LATE", 4.0)), line.batches)
    }

    @Test
    fun `two lines of the same item share the warehouse batches`() = runTest {
        val draft = slot<DeliveryNoteDraft>()
        val twice = salesOrder.copy(items = salesOrder.items + batchItem.copy(rowName = "srow3", qty = 1.0))
        coEvery { orders.getItemTracking(listOf("20100103")) } returns AppResult.Success(mapOf("20100103" to ItemTracking("20100103", true, false)))
        coEvery { orders.getUsableBatches("20100103", "Finished Goods - WM") } returns AppResult.Success(listOf(BatchStock("B-SOON", 30.0), BatchStock("B-LATE", 30.0)))
        coEvery { orders.createDeliveryNote(capture(draft), false) } returns AppResult.Success(created)

        useCase(twice, listOf(DispatchLine("srow2", 2.0, null), DispatchLine("srow3", 1.0, null)), null, submit = false)

        val (first, second) = draft.captured.lines
        assertEquals(listOf(BatchAllocation("B-SOON", 24.0)), first.batches)
        assertEquals(listOf(BatchAllocation("B-SOON", 6.0), BatchAllocation("B-LATE", 6.0)), second.batches)
        coVerify(exactly = 1) { orders.getUsableBatches("20100103", "Finished Goods - WM") }
    }

    @Test
    fun `insufficient batch stock and serialised items are refused before anything is created`() = runTest {
        coEvery { orders.getItemTracking(listOf("20100103")) } returns AppResult.Success(mapOf("20100103" to ItemTracking("20100103", true, false)))
        coEvery { orders.getUsableBatches("20100103", "Finished Goods - WM") } returns AppResult.Success(listOf(BatchStock("B-SOON", 10.0)))

        val short = useCase(salesOrder, listOf(DispatchLine("srow2", 2.0, null)), null, submit = true).error()
        assertEquals(ErrorCode.INSUFFICIENT_BATCH_STOCK, short.code)
        assertEquals(listOf("20100103", "10", "24", "Finished Goods - WM"), short.args)

        coEvery { orders.getItemTracking(listOf("20100103")) } returns AppResult.Success(mapOf("20100103" to ItemTracking("20100103", true, true)))
        val serial = useCase(salesOrder, listOf(DispatchLine("srow2", 1.0, null)), null, submit = true).error()
        assertEquals(ErrorCode.SERIAL_ITEM_UNSUPPORTED, serial.code)
        assertEquals(listOf("20100103"), serial.args)

        coVerify(exactly = 0) { orders.createDeliveryNote(any(), any()) }
    }

    @Test
    fun `repository failures while looking up batches are passed through`() = runTest {
        coEvery { orders.getItemTracking(any()) } returns AppResult.Failure(AppError.Forbidden("Not permitted: Item"))

        val result = useCase(salesOrder, listOf(DispatchLine("srow1", 1.0, null)), null, submit = true)

        assertTrue(result.error() is AppError.Forbidden)
        assertNull(result.error().args.firstOrNull())
    }

    @Test
    fun `allocator helpers order by expiry and report what is left`() {
        val today = LocalDate.of(2026, 9, 28)
        val batches = listOf(
            BatchStock("B-FOREVER", 5.0, null),
            BatchStock("B-EXPIRED", 5.0, "2026-09-27"),
            BatchStock("B-LATE", 5.0, "2027-01-01"),
            BatchStock("B-SOON", 5.0, "2026-09-28"),
            BatchStock("B-EMPTY", 0.0, "2026-10-01"),
        )
        val usable = BatchAllocator.usable(batches, today)
        assertEquals(listOf("B-SOON", "B-LATE", "B-FOREVER"), usable.map { it.batchNo })

        val allocation = BatchAllocator.allocate(usable, 7.0)!!
        assertEquals(listOf(BatchAllocation("B-SOON", 5.0), BatchAllocation("B-LATE", 2.0)), allocation)
        assertEquals(listOf(0.0, 3.0, 5.0), BatchAllocator.remaining(usable, allocation).map { it.qty })
        assertNull(BatchAllocator.allocate(usable, 15.0001))
        assertEquals(15.0, BatchAllocator.available(usable), 0.0)
    }
}
