package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.common.ErrorCode
import com.wmserp.app.domain.model.BatchCount
import com.wmserp.app.domain.model.CreatedBatch
import com.wmserp.app.domain.model.NewBatch
import com.wmserp.app.domain.model.ReceiptCount
import com.wmserp.app.domain.model.ReceiptDifference
import com.wmserp.app.domain.model.ReceiveResult
import com.wmserp.app.domain.repository.ReceiptRepository
import com.wmserp.app.domain.repository.SettingsRepository
import com.wmserp.app.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiveUseCasesTest {

    private val repository: ReceiptRepository = mockk()
    private val settings: SettingsRepository = mockk { every { documentFieldDefaults } returns flowOf(mapOf("Purchase Receipt.department" to "Warehouse - WM")) }
    private val useCase = ReceivePurchaseReceiptUseCase(repository, settings)
    private val receipt = TestFixtures.purchaseReceipt

    private fun ReceivePurchaseReceiptUseCase.failure(lines: List<ReceiveLine>, warehouse: String? = null): AppError = runCatchingResult {
        invoke(receipt, lines, warehouse)
    }

    private fun runCatchingResult(block: suspend () -> AppResult<ReceiveResult>): AppError {
        var error: AppError? = null
        kotlinx.coroutines.runBlocking { error = (block() as AppResult.Failure).error }
        return error!!
    }

    @Test
    fun `rows counted zero are left out, batches and warehouses are resolved and remembered answers are merged`() = runTest {
        val counts = slot<List<ReceiptCount>>()
        val values = slot<Map<String, String>>()
        coEvery { repository.receive(receipt.name, capture(counts), capture(values), true) } returns AppResult.Success(ReceiveResult(receipt, submitted = true))

        val result = useCase(
            receipt = receipt,
            lines = listOf(ReceiveLine("row1", 10.0, batchNo = " LOT-1 "), ReceiveLine("row2", 0.0)),
            defaultWarehouse = "Fallback - WM",
            fieldValues = mapOf("Purchase Receipt.cost_center" to "Main - WM"),
        )

        assertTrue(result is AppResult.Success)
        assertEquals(listOf(ReceiptCount("row1", 10.0, "Stores - WM", "LOT-1")), counts.captured)
        assertEquals(mapOf("Purchase Receipt.department" to "Warehouse - WM", "Purchase Receipt.cost_center" to "Main - WM"), values.captured)
    }

    @Test
    fun `a row received in several batches sends them with the summed quantity and zero batches dropped`() = runTest {
        val counts = slot<List<ReceiptCount>>()
        coEvery { repository.receive(receipt.name, capture(counts), any(), true) } returns AppResult.Success(ReceiveResult(receipt, submitted = true))

        useCase(receipt, listOf(ReceiveLine("row1", 99.0, batches = listOf(BatchCount("1-A", 6.0, "A"), BatchCount("2-B", 0.0), BatchCount("3-C", 3.0)))))

        val count = counts.captured.single()
        assertEquals(9.0, count.qty, 0.0)
        assertEquals("1-A", count.batchNo)
        assertEquals(listOf(BatchCount("1-A", 6.0, "A"), BatchCount("3-C", 3.0)), count.batches)
    }

    @Test
    fun `creating a batch validates the number and the dates before calling the server`() = runTest {
        val create = CreateBatchUseCase(repository)
        val created = CreatedBatch(TestFixtures.batch, created = true)
        coEvery { repository.createBatch(NewBatch("ITEM-001", "LOT-1", "2026-10-01", "2028-09-30", receipt = "MAT-PRE-2026-00001")) } returns AppResult.Success(created)

        assertEquals(created, (create(NewBatch("ITEM-001", " LOT-1 ", "2026-10-01", " 2028-09-30 ", receipt = "MAT-PRE-2026-00001")) as AppResult.Success).data)
        assertEquals(ErrorCode.BATCH_REQUIRED_FOR_ITEM, (create(NewBatch("ITEM-001", " ")) as AppResult.Failure).error.code)
        assertEquals(ErrorCode.INVALID_DATE, (create(NewBatch("ITEM-001", "LOT-1", "1/10/2026")) as AppResult.Failure).error.code)
        assertEquals(ErrorCode.EXPIRY_BEFORE_MANUFACTURING, (create(NewBatch("ITEM-001", "LOT-1", "2026-10-01", "2026-09-30")) as AppResult.Failure).error.code)
    }

    @Test
    fun `finding a batch needs a number`() = runTest {
        val find = FindBatchUseCase(repository)
        assertEquals(ErrorCode.BATCH_REQUIRED_FOR_ITEM, (find("  ") as AppResult.Failure).error.code)
    }

    @Test
    fun `an explicit warehouse on the line wins over the row and the receipt`() = runTest {
        val counts = slot<List<ReceiptCount>>()
        coEvery { repository.receive(receipt.name, capture(counts), any(), false) } returns AppResult.Success(ReceiveResult(receipt, submitted = false))

        useCase(receipt, listOf(ReceiveLine("row2", 2.0, warehouse = "Quarantine - WM")), submit = false)

        assertEquals("Quarantine - WM", counts.captured.single().warehouse)
    }

    @Test
    fun `a batch tracked row without a batch is refused before anything is sent`() = runTest {
        val error = useCase.failure(listOf(ReceiveLine("row1", 10.0)))
        assertEquals(ErrorCode.BATCH_REQUIRED_FOR_ITEM, error.code)
        assertEquals(listOf("ITEM-001"), error.args)
    }

    @Test
    fun `negative counts, unknown rows and empty counts are validation errors`() = runTest {
        assertEquals(ErrorCode.QTY_MUST_BE_POSITIVE, useCase.failure(listOf(ReceiveLine("row2", -1.0))).code)
        assertEquals(ErrorCode.UNKNOWN_ORDER_ROW, useCase.failure(listOf(ReceiveLine("nope", 1.0))).code)
        assertEquals(ErrorCode.NOTHING_TO_RECEIVE, useCase.failure(listOf(ReceiveLine("row2", 0.0))).code)
    }

    @Test
    fun `a row without any warehouse needs one from the screen`() = runTest {
        val bare = receipt.copy(setWarehouse = null, items = receipt.items.map { it.copy(warehouse = null) })
        val error = runCatchingResult { useCase(bare, listOf(ReceiveLine("row2", 1.0))) }
        assertEquals(ErrorCode.SELECT_WAREHOUSE_FOR_ITEM, error.code)
    }

    @Test
    fun `a fixed asset or service row is sent without a warehouse and needs none`() = runTest {
        val counts = slot<List<ReceiptCount>>()
        coEvery { repository.receive(any(), capture(counts), any(), true) } returns AppResult.Success(ReceiveResult(receipt, submitted = true))
        val asset = receipt.items[1].copy(warehouse = null, isStockItem = false, isFixedAsset = true)
        val mixed = receipt.copy(setWarehouse = null, items = listOf(receipt.items[1], asset.copy(rowName = "row3", itemCode = "ASSET-1")))

        val result = useCase(mixed, listOf(ReceiveLine("row2", 5.0), ReceiveLine("row3", 3.0)), defaultWarehouse = "Picked - WM")

        assertTrue(result is AppResult.Success)
        assertEquals(listOf(ReceiptCount("row2", 5.0, "Stores - WM"), ReceiptCount("row3", 3.0, null)), counts.captured)
    }

    @Test
    fun `differences list every row whose count differs from the draft, zero meaning not received`() {
        val differences = ReceivePurchaseReceiptUseCase.differences(receipt, listOf(ReceiveLine("row2", 3.0)))
        assertEquals(
            listOf(ReceiptDifference("row1", "ITEM-001", 10.0, 0.0), ReceiptDifference("row2", "ITEM-002", 5.0, 3.0)),
            differences,
        )
        assertTrue(ReceivePurchaseReceiptUseCase.differences(receipt, listOf(ReceiveLine("row1", 10.0), ReceiveLine("row2", 5.0))).isEmpty())
    }

    @Test
    fun `a missing receipt is reported with its own error code`() = runTest {
        coEvery { repository.getPurchaseReceipt("MAT-PRE-2026-00009") } returns AppResult.Success(null)
        val result = GetPurchaseReceiptUseCase(repository)(" MAT-PRE-2026-00009 ")
        val error = (result as AppResult.Failure).error
        assertTrue(error is AppError.NotFound)
        assertEquals(ErrorCode.PURCHASE_RECEIPT_NOT_FOUND, error.code)
    }
}
