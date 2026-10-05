package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.common.ErrorCode
import com.wmserp.app.domain.model.PickListPurpose
import com.wmserp.app.domain.model.PickRowStatus
import com.wmserp.app.domain.model.PickingStatus
import com.wmserp.app.domain.model.WmsQrKeys
import com.wmserp.app.domain.repository.PickListRepository
import com.wmserp.app.domain.repository.SettingsRepository
import com.wmserp.app.testutil.TestFixtures
import com.wmserp.app.testutil.qrLabel
import com.wmserp.app.testutil.rowUpdate
import com.wmserp.app.testutil.withRow
import io.mockk.coEvery
import io.mockk.every
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PickListUseCasesTest {

    private val repository: PickListRepository = mockk()
    private val settingsRepository: SettingsRepository = mockk { every { documentFieldDefaults } returns flowOf(emptyMap()) }
    private val pickList = TestFixtures.pickList
    private val mine = pickList.items.first { it.rowName == "prow1" }
    private val notMine = pickList.items.first { it.rowName == "prow3" }

    private fun AppResult<*>.code(): ErrorCode? = (this as AppResult.Failure).error.code
    private fun AppResult<*>.args(): List<String> = (this as AppResult.Failure).error.args

    // ---- strict scan validation ------------------------------------------------------------

    private val validate = ValidatePickScanUseCase()

    @Test
    fun `matching item and batch selects the row`() {
        val outcome = validate(qrLabel("ITEM-001", "B-001"), WmsQrKeys.DEFAULT, pickList.items) as PickScanOutcome.Match
        assertEquals("prow1", outcome.row.rowName)
        assertEquals("ITEM-001", outcome.label.itemCode)
        assertEquals("B-001", outcome.label.batchNo)

        // Item and batch comparison ignores case; the label keeps what was scanned.
        val relaxed = validate(qrLabel("item-001", "b-001"), WmsQrKeys.DEFAULT, pickList.items) as PickScanOutcome.Match
        assertEquals("prow1", relaxed.row.rowName)
        assertEquals("b-001", relaxed.label.batchNo)
    }

    @Test
    fun `wrong or missing batch is reported with the expected value`() {
        val wrong = validate(qrLabel("ITEM-001", "B-2"), WmsQrKeys.DEFAULT, pickList.items) as PickScanOutcome.WrongBatch
        assertEquals("prow1", wrong.row.rowName)
        assertEquals("B-001", wrong.expected)
        assertEquals("B-2", wrong.scanned)

        val missing = validate(qrLabel("ITEM-001"), WmsQrKeys.DEFAULT, pickList.items) as PickScanOutcome.WrongBatch
        assertEquals("B-001", missing.expected)
        assertNull(missing.scanned)
    }

    @Test
    fun `rows without an allocated batch accept any label of the item`() {
        assertTrue(validate(qrLabel("ITEM-002"), WmsQrKeys.DEFAULT, pickList.items) is PickScanOutcome.Match)
        assertTrue(validate(qrLabel("ITEM-002", "ANY"), WmsQrKeys.DEFAULT, pickList.items) is PickScanOutcome.Match)
    }

    @Test
    fun `items on other pickers rows or off the list are not assigned`() {
        val other = validate(qrLabel("ITEM-003", "B-777"), WmsQrKeys.DEFAULT, pickList.items) as PickScanOutcome.NotAssigned
        assertEquals("ITEM-003", other.label.itemCode)
        assertTrue(validate(qrLabel("ITEM-999"), WmsQrKeys.DEFAULT, pickList.items) is PickScanOutcome.NotAssigned)
    }

    @Test
    fun `complete rows refuse further scans`() {
        val done = pickList.withRow("prow1", 10.0, PickRowStatus.PICKED)
        val outcome = validate(qrLabel("ITEM-001", "B-001"), WmsQrKeys.DEFAULT, done.items)
        assertTrue(outcome is PickScanOutcome.AlreadyComplete)

        // Device-side count is ahead of the server: still refused.
        val local = validate(qrLabel("ITEM-001", "B-001"), WmsQrKeys.DEFAULT, pickList.items, currentQty = { 10.0 })
        assertTrue(local is PickScanOutcome.AlreadyComplete)
    }

    @Test
    fun `non JSON scans and custom keys`() {
        assertEquals(QrError.NOT_JSON, (validate("ITEM-001", WmsQrKeys.DEFAULT, pickList.items) as PickScanOutcome.InvalidQr).error)
        assertEquals(QrError.MISSING_ITEM, (validate("""{"batch_no":"B-001"}""", WmsQrKeys.DEFAULT, pickList.items) as PickScanOutcome.InvalidQr).error)

        val keys = WmsQrKeys(itemKey = "sku", batchKey = "lot")
        val match = validate("""{"sku":"ITEM-001","lot":"B-001"}""", keys, pickList.items) as PickScanOutcome.Match
        assertEquals("prow1", match.row.rowName)
        assertTrue(validate(qrLabel("ITEM-001", "B-001"), keys, pickList.items) is PickScanOutcome.InvalidQr)
    }

    @Test
    fun `the active row wins when several rows carry the same item`() {
        val twoRows = pickList.copy(items = pickList.items + mine.copy(rowName = "prow4", idx = 4))
        val outcome = validate(qrLabel("ITEM-001", "B-001"), WmsQrKeys.DEFAULT, twoRows.items, activeRowName = "prow4") as PickScanOutcome.Match
        assertEquals("prow4", outcome.row.rowName)
    }

    // ---- row use cases ---------------------------------------------------------------------

    @Test
    fun `starting a row requires it to be mine and open`() = runTest {
        assertEquals(ErrorCode.ROW_NOT_ASSIGNED, StartPickRowUseCase(repository)(pickList, notMine).code())
        assertEquals(ErrorCode.ROW_ALREADY_PICKED, StartPickRowUseCase(repository)(pickList, mine.copy(rowStatus = PickRowStatus.PICKED)).code())
        coVerify(exactly = 0) { repository.startRow(any(), any()) }
    }

    @Test
    fun `over picking is refused locally`() = runTest {
        val result = SavePickRowProgressUseCase(repository)(pickList, mine, 11.0)
        assertEquals(ErrorCode.OVER_PICK, result.code())
        assertEquals(listOf("ITEM-001", "11", "10"), result.args())
        assertEquals(ErrorCode.ROW_NOT_ASSIGNED, SavePickRowProgressUseCase(repository)(pickList, notMine, 1.0).code())
        coVerify(exactly = 0) { repository.saveRowProgress(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `completing a row needs the exact required quantity`() = runTest {
        val short = CompletePickRowUseCase(repository)(pickList, mine, 9.0)
        assertEquals(ErrorCode.ROW_INCOMPLETE, short.code())
        assertEquals(listOf("ITEM-001", "9", "10"), short.args())

        val finished = pickList.withRow("prow1", 10.0, PickRowStatus.PICKED)
        coEvery { repository.completeRow(pickList.name, "prow1", 10.0, null, "B-001", 120.0) } returns AppResult.Success(rowUpdate(finished, "prow1", true))
        val ok = CompletePickRowUseCase(repository)(pickList, mine, 10.0, 120.0)
        assertTrue(ok is AppResult.Success)
    }

    @Test
    fun `document generation needs a picked card with a supported purpose`() = runTest {
        assertEquals(ErrorCode.PICKING_NOT_COMPLETED, GeneratePickDocumentUseCase(repository, settingsRepository)(pickList).code())
        val picked = pickList.copy(pickingStatus = PickingStatus.PICKED, allRowsPicked = true)
        val unsupported = GeneratePickDocumentUseCase(repository, settingsRepository)(picked.copy(purpose = PickListPurpose.OTHER, purposeLabel = "Weird"))
        assertEquals(ErrorCode.UNSUPPORTED_PICK_PURPOSE, unsupported.code())
        assertEquals(listOf("Weird"), unsupported.args())
        coVerify(exactly = 0) { repository.generateDocument(any(), any()) }
    }

    @Test
    fun `a missing pick list is reported with a localized code`() = runTest {
        coEvery { repository.getPickList("X") } returns AppResult.Failure(AppError.NotFound("Pick List X not found"))

        val result = GetPickListUseCase(repository)("X")

        assertEquals(ErrorCode.PICK_LIST_NOT_FOUND, result.code())
        assertEquals(listOf("X"), result.args())
    }
}
