package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.common.ErrorCode
import com.wmserp.app.domain.model.PickListPurpose
import com.wmserp.app.domain.model.PickProgressLine
import com.wmserp.app.domain.model.PickScanMatch
import com.wmserp.app.domain.model.PickScanMatchType
import com.wmserp.app.domain.model.PickingStatus
import com.wmserp.app.domain.repository.PickListRepository
import com.wmserp.app.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PickListUseCasesTest {

    private val repository: PickListRepository = mockk()
    private val ready = TestFixtures.pickList
    private val picking = ready.copy(pickingStatus = PickingStatus.PICKING)

    private fun AppResult<*>.code(): ErrorCode? = (this as AppResult.Failure).error.code
    private fun AppResult<*>.args(): List<String> = (this as AppResult.Failure).error.args

    @Test
    fun `complete picking rejects incomplete mandatory rows without calling the server`() = runTest {
        val result = CompletePickingUseCase(repository)(picking, listOf(PickProgressLine("prow1", 10.0), PickProgressLine("prow2", 2.0)))

        assertEquals(ErrorCode.PICKING_INCOMPLETE, result.code())
        assertEquals(listOf("1"), result.args())
        coVerify(exactly = 0) { repository.completePicking(any(), any()) }
    }

    @Test
    fun `optional rows may be completed short`() = runTest {
        val withOptional = picking.copy(items = picking.items.map { if (it.rowName == "prow2") it.copy(optional = true) else it })
        val lines = listOf(PickProgressLine("prow1", 10.0), PickProgressLine("prow2", 2.0, "B-001"))
        coEvery { repository.completePicking(withOptional.name, lines) } returns AppResult.Success(withOptional.copy(pickingStatus = PickingStatus.PICKED))

        val result = CompletePickingUseCase(repository)(withOptional, lines)

        assertTrue(result is AppResult.Success)
    }

    @Test
    fun `over picking and unknown rows are rejected locally`() = runTest {
        val over = SavePickProgressUseCase(repository)(picking, listOf(PickProgressLine("prow1", 11.0)))
        assertEquals(ErrorCode.OVER_PICK, over.code())
        assertEquals(listOf("ITEM-001", "11", "10"), over.args())

        val unknown = SavePickProgressUseCase(repository)(picking, listOf(PickProgressLine("nope", 1.0)))
        assertEquals(ErrorCode.UNKNOWN_ORDER_ROW, unknown.code())
        coVerify(exactly = 0) { repository.saveProgress(any(), any()) }
    }

    @Test
    fun `progress can only be saved while picking`() = runTest {
        val result = SavePickProgressUseCase(repository)(ready, listOf(PickProgressLine("prow1", 1.0)))

        assertEquals(ErrorCode.PICKING_NOT_STARTED, result.code())
    }

    @Test
    fun `starting an already picked list fails locally`() = runTest {
        val result = StartPickingUseCase(repository)(ready.copy(pickingStatus = PickingStatus.PICKED))

        assertEquals(ErrorCode.PICK_LIST_ALREADY_PICKED, result.code())
    }

    @Test
    fun `document generation needs a picked list with a supported purpose`() = runTest {
        val notPicked = GeneratePickDocumentUseCase(repository)(picking)
        assertEquals(ErrorCode.PICKING_NOT_COMPLETED, notPicked.code())

        val unsupported = GeneratePickDocumentUseCase(repository)(ready.copy(pickingStatus = PickingStatus.PICKED, purpose = PickListPurpose.OTHER, purposeLabel = "Weird"))
        assertEquals(ErrorCode.UNSUPPORTED_PICK_PURPOSE, unsupported.code())
        assertEquals(listOf("Weird"), unsupported.args())
        coVerify(exactly = 0) { repository.generateDocument(any()) }
    }

    @Test
    fun `scans are matched on the device by item code, barcode and batch`() = runTest {
        val useCase = ResolvePickScanUseCase(repository)

        val byCode = (useCase(picking, "ITEM-001") as AppResult.Success).data as PickScanResult.Matched
        assertEquals("prow1", byCode.row.rowName)
        assertEquals(PickScanMatchType.ITEM_CODE, byCode.matchType)

        val byBarcode = (useCase(picking, "8690000000017\n") as AppResult.Success).data as PickScanResult.Matched
        assertEquals("prow1", byBarcode.row.rowName)
        assertEquals(PickScanMatchType.BARCODE, byBarcode.matchType)

        val byBatch = (useCase(picking, "b-001") as AppResult.Success).data as PickScanResult.Matched
        assertEquals("prow2", byBatch.row.rowName)
        assertEquals(PickScanMatchType.BATCH, byBatch.matchType)
        assertEquals("B-001", byBatch.batchNo)

        coVerify(exactly = 0) { repository.resolveScan(any(), any()) }
    }

    @Test
    fun `scan matching prefers rows that still need picking`() = runTest {
        val twoRowsSameItem = picking.copy(
            items = listOf(
                picking.items[0].copy(rowName = "a", pickedQty = 10.0),
                picking.items[0].copy(rowName = "b", pickedQty = 0.0),
            )
        )

        val match = (ResolvePickScanUseCase(repository)(twoRowsSameItem, "ITEM-001") as AppResult.Success).data as PickScanResult.Matched

        assertEquals("b", match.row.rowName)
    }

    @Test
    fun `unknown codes are resolved by the server`() = runTest {
        coEvery { repository.resolveScan(picking.name, "XYZ") } returns AppResult.Success(PickScanMatch("prow2", "ITEM-002", "B-001", PickScanMatchType.BATCH))
        coEvery { repository.resolveScan(picking.name, "NONE") } returns AppResult.Success(PickScanMatch(null, null, null, PickScanMatchType.NONE))
        coEvery { repository.resolveScan(picking.name, "OTHER") } returns AppResult.Success(PickScanMatch(null, "ITEM-777", null, PickScanMatchType.NOT_ON_LIST))
        val useCase = ResolvePickScanUseCase(repository)

        val matched = (useCase(picking, "XYZ") as AppResult.Success).data as PickScanResult.Matched
        assertEquals("prow2", matched.row.rowName)
        assertTrue((useCase(picking, "NONE") as AppResult.Success).data is PickScanResult.Unknown)
        val notOnList = (useCase(picking, "OTHER") as AppResult.Success).data as PickScanResult.NotOnList
        assertEquals("ITEM-777", notOnList.itemCode)
    }

    @Test
    fun `a missing pick list is reported with a localized code`() = runTest {
        coEvery { repository.getPickList("X") } returns AppResult.Failure(AppError.NotFound("Pick List X not found"))

        val result = GetPickListUseCase(repository)("X")

        assertEquals(ErrorCode.PICK_LIST_NOT_FOUND, result.code())
        assertEquals(listOf("X"), result.args())
    }
}
