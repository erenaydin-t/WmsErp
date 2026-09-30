package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.CountItemStatus
import com.wmserp.app.domain.model.CountOutcome
import com.wmserp.app.domain.model.CountRejection
import com.wmserp.app.domain.model.CountResult
import com.wmserp.app.domain.model.CountSubmission
import com.wmserp.app.domain.model.CountType
import com.wmserp.app.domain.model.CountingMode
import com.wmserp.app.domain.model.DuplicateCountPolicy
import com.wmserp.app.domain.model.StocktakingItemsPage
import com.wmserp.app.domain.model.StocktakingStatus
import com.wmserp.app.domain.model.SyncOutcome
import com.wmserp.app.domain.model.WmsQrKeys
import com.wmserp.app.domain.repository.StocktakingRepository
import com.wmserp.app.testutil.InMemoryStocktakingLocalStore
import com.wmserp.app.testutil.StocktakingFixtures
import com.wmserp.app.testutil.StocktakingFixtures.USER
import com.wmserp.app.testutil.qrLabel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CountScanResolverTest {
    private val items = StocktakingFixtures.items
    private val keys = WmsQrKeys.DEFAULT

    @Test
    fun `a JSON label with a batch matches exactly one row`() {
        val outcome = CountScanResolver.resolve(qrLabel("PCT-500", "PCT-260101"), keys, items, StocktakingFixtures.barcodes)
        assertEquals(CountScanOutcome.Match(StocktakingFixtures.paracetamolB2), outcome)
    }

    @Test
    fun `a label without a batch for a batch item asks which batch`() {
        val outcome = CountScanResolver.resolve(qrLabel("pct-500"), keys, items, StocktakingFixtures.barcodes)
        assertTrue(outcome is CountScanOutcome.ChooseBatch)
        assertEquals(listOf("r1", "r2"), (outcome as CountScanOutcome.ChooseBatch).rows.map { it.name })
    }

    @Test
    fun `plain barcodes resolve through the item barcodes and batch numbers`() {
        assertEquals(CountScanOutcome.Match(StocktakingFixtures.gauze), CountScanResolver.resolve("8690000000017\n", keys, items, StocktakingFixtures.barcodes))
        assertEquals(CountScanOutcome.Match(StocktakingFixtures.gauze), CountScanResolver.resolve("GZ-10", keys, items, StocktakingFixtures.barcodes))
        assertEquals(CountScanOutcome.Match(StocktakingFixtures.aspirinCounted), CountScanResolver.resolve("ASP-1", keys, items, StocktakingFixtures.barcodes))
    }

    @Test
    fun `unknown labels are reported with what the label said`() {
        val json = CountScanResolver.resolve(qrLabel("NEW-1", "B-9"), keys, items, StocktakingFixtures.barcodes)
        assertEquals(CountScanOutcome.NotInSession("NEW-1", "B-9", qrLabel("NEW-1", "B-9")), json)
        val known = CountScanResolver.resolve(qrLabel("PCT-500", "PCT-999"), keys, items, StocktakingFixtures.barcodes)
        assertEquals(CountScanOutcome.NotInSession("PCT-500", "PCT-999", qrLabel("PCT-500", "PCT-999")), known)
        val plain = CountScanResolver.resolve("0000000", keys, items, StocktakingFixtures.barcodes)
        assertEquals(CountScanOutcome.NotInSession(null, null, "0000000"), plain)
        assertTrue(CountScanResolver.resolve("  ", keys, items, StocktakingFixtures.barcodes) is CountScanOutcome.Invalid)
    }
}

class CountEvaluatorTest {
    private val session = StocktakingFixtures.session
    private val row = StocktakingFixtures.paracetamolB1

    @Test
    fun `a matching first count is accepted`() {
        val result = CountEvaluator.evaluate(row, 100.0, session, USER, "2026-09-30 10:00:00")
        assertEquals(CountOutcome.ACCEPTED, result.outcome)
        assertEquals(CountType.COUNT_1, result.countType)
        assertEquals(CountItemStatus.COUNTED, result.item.status)
        assertEquals(100.0, result.item.finalQty)
        assertEquals(0.0, result.item.qtyDifference)
        assertEquals(USER, result.item.countedBy)
    }

    @Test
    fun `a mismatch requires a second count and then goes to the manager`() {
        val first = CountEvaluator.evaluate(row, 95.0, session, USER, null)
        assertEquals(CountOutcome.SECOND_COUNT_REQUIRED, first.outcome)
        assertEquals(CountItemStatus.RECOUNT_REQUIRED, first.item.status)
        assertEquals(CountType.COUNT_2, first.item.nextCountType)
        assertNull(first.item.finalQty)
        assertEquals(95.0, first.item.count1)

        val second = CountEvaluator.evaluate(first.item, 97.0, session, USER, null)
        assertEquals(CountOutcome.MANAGER_REVIEW, second.outcome)
        assertEquals(CountType.COUNT_2, second.countType)
        assertEquals(CountItemStatus.MANAGER_REVIEW, second.item.status)
        assertEquals(97.0, second.item.finalQty)
        assertEquals(-3.0, second.item.qtyDifference)

        val matching = CountEvaluator.evaluate(first.item, 100.0, session, USER, null)
        assertEquals(CountOutcome.ACCEPTED, matching.outcome)
    }

    @Test
    fun `tolerance and the no-second-count option`() {
        val tolerant = session.copy(qtyTolerance = 1.0)
        assertEquals(CountOutcome.ACCEPTED, CountEvaluator.evaluate(row, 99.0, tolerant, USER, null).outcome)
        val direct = session.copy(requireSecondCount = false)
        assertEquals(CountOutcome.MANAGER_REVIEW, CountEvaluator.evaluate(row, 90.0, direct, USER, null).outcome)
    }

    @Test
    fun `manager recounts and additional counts`() {
        val recount = row.copy(status = CountItemStatus.RECOUNT_REQUIRED, nextCountType = CountType.RECOUNT, count1 = 95.0)
        val differing = CountEvaluator.evaluate(recount, 98.0, session, USER, null)
        assertEquals(CountOutcome.RECOUNT_RECORDED, differing.outcome)
        assertEquals(CountItemStatus.RECOUNTED, differing.item.status)
        assertEquals(1, differing.item.recountCount)
        val allow = session.copy(duplicatePolicy = DuplicateCountPolicy.ALLOW)
        val extra = CountEvaluator.evaluate(StocktakingFixtures.aspirinCounted, 11.0, allow, USER, null)
        assertEquals(CountOutcome.ADDITIONAL_COUNT, extra.outcome)
        assertEquals(CountItemStatus.MANAGER_REVIEW, extra.item.status)
    }

    @Test
    fun `rows with an unknown ERP quantity are accepted provisionally`() {
        val blind = row.copy(erpQty = null)
        val result = CountEvaluator.evaluate(blind, 42.0, session, USER, null)
        assertEquals(CountOutcome.ACCEPTED, result.outcome)
        assertEquals(CountItemStatus.COUNTED, result.item.status)
        assertEquals(42.0, result.item.finalQty)
        assertNull(result.item.qtyDifference)
    }

    @Test
    fun `refusals mirror the server rules`() {
        // Locked rows are reported as already counted even when they are also assigned to someone else.
        assertEquals(CountRefusal.ALREADY_COUNTED, CountEvaluator.refusal(StocktakingFixtures.aspirinCounted, session, USER))
        assertEquals(CountRefusal.NOT_ASSIGNED, CountEvaluator.refusal(StocktakingFixtures.aspirinCounted, session.copy(duplicatePolicy = DuplicateCountPolicy.ALLOW), USER))
        assertNull(CountEvaluator.refusal(StocktakingFixtures.aspirinCounted.copy(counter = USER), session.copy(duplicatePolicy = DuplicateCountPolicy.ALLOW), USER))
        assertEquals(CountRefusal.NOT_ASSIGNED, CountEvaluator.refusal(row.copy(counter = "reza@example.com"), session, USER))
        assertNull(CountEvaluator.refusal(row.copy(counter = "reza@example.com"), session.copy(mode = CountingMode.OPEN), USER))
        assertNull(CountEvaluator.refusal(row.copy(counter = "reza@example.com"), session.copy(isSupervisor = true), USER))
        assertEquals(CountRefusal.SESSION_CLOSED, CountEvaluator.refusal(row, session.copy(status = StocktakingStatus.MANAGER_REVIEW), USER))
        assertEquals(CountRefusal.FINALIZED, CountEvaluator.refusal(row.copy(status = CountItemStatus.FINALIZED), session, USER))
    }
}

class LoadAndSyncUseCaseTest {
    private val repository: StocktakingRepository = mockk()
    private val store = InMemoryStocktakingLocalStore()

    @Test
    fun `load downloads every page and caches the rows`() = runTest {
        coEvery { repository.getSession("ST-2026-0001") } returns AppResult.Success(StocktakingFixtures.session)
        val first = StocktakingFixtures.items.take(2)
        val second = StocktakingFixtures.items.drop(2)
        coEvery { repository.getItems("ST-2026-0001", 0, 1000, true) } returns AppResult.Success(StocktakingItemsPage(first, StocktakingFixtures.barcodes, 0, 1000, 4))
        coEvery { repository.getItems("ST-2026-0001", 2, 1000, true) } returns AppResult.Success(StocktakingItemsPage(second, emptyMap(), 2, 1000, 4))
        var progress = listOf<Pair<Int, Int>>()

        val loaded = LoadStocktakingSessionUseCase(repository, store)("ST-2026-0001", now = 5L) { l, t -> progress = progress + (l to t) }

        val data = (loaded as AppResult.Success).data
        assertFalse(data.fromCache)
        assertEquals(4, data.cache.items.size)
        assertEquals(listOf(2 to 4, 4 to 4), progress)
        assertEquals(StocktakingFixtures.barcodes, data.cache.barcodes)
        assertEquals(5L, store.sessions["ST-2026-0001"]?.cachedAtMillis)
    }

    @Test
    fun `load falls back to the device copy when the server is unreachable`() = runTest {
        store.writeSession(StocktakingFixtures.cache())
        coEvery { repository.getSession(any()) } returns AppResult.Failure(AppError.Network("offline"))

        val loaded = LoadStocktakingSessionUseCase(repository, store)("ST-2026-0001")

        val data = (loaded as AppResult.Success).data
        assertTrue(data.fromCache)
        assertEquals(4, data.cache.items.size)

        coEvery { repository.getSession(any()) } returns AppResult.Failure(AppError.NotFound("gone"))
        assertTrue(LoadStocktakingSessionUseCase(repository, store)("ST-2026-0001") is AppResult.Failure)
    }

    @Test
    fun `sync confirms what the server accepted, drops what it refused and keeps the rest`() = runTest {
        val a = CountSubmission("ref-a", "ST-2026-0001", "r1", "PCT-500", "PCT-250901", "Main - C", 100.0, "2026-09-30 10:00:00")
        val b = CountSubmission("ref-b", "ST-2026-0001", "r4", "ASP-100", "ASP-1", "Main - C", 12.0, "2026-09-30 10:01:00")
        val c = CountSubmission("ref-c", "ST-2026-0001", "r3", "GZ-10", null, "Main - C", 50.0, "2026-09-30 10:02:00")
        store.writePending("ST-2026-0001", listOf(a, b, c))
        coEvery { repository.syncCounts("ST-2026-0001", listOf(a, b, c)) } returns AppResult.Success(
            SyncOutcome(
                results = listOf(
                    CountResult(CountOutcome.ACCEPTED, StocktakingFixtures.paracetamolB1.copy(status = CountItemStatus.COUNTED), clientRef = "ref-a"),
                    CountResult(CountOutcome.REJECTED, StocktakingFixtures.aspirinCounted, rejection = CountRejection.ALREADY_COUNTED, message = "already counted by Reza", clientRef = "ref-b"),
                ),
            )
        )

        val report = SyncPendingCountsUseCase(repository, store)("ST-2026-0001")

        assertEquals(1, report.synced)
        assertEquals(listOf("ref-b"), report.refused.map { it.clientRef })
        assertEquals(listOf("ref-c"), report.remaining.map { it.clientRef })
        assertEquals(1, report.remaining.first().attempts)
        assertEquals(listOf("ref-c"), store.readPending("ST-2026-0001").map { it.clientRef })
        assertFalse(report.offline)
    }

    @Test
    fun `sync keeps the whole queue when the network is down`() = runTest {
        val a = CountSubmission("ref-a", "ST-2026-0001", "r1", "PCT-500", "PCT-250901", null, 100.0, "2026-09-30 10:00:00")
        store.writePending("ST-2026-0001", listOf(a))
        coEvery { repository.syncCounts(any(), any()) } returns AppResult.Failure(AppError.Network("offline"))

        val report = SyncPendingCountsUseCase(repository, store)("ST-2026-0001")

        assertTrue(report.offline)
        assertEquals(listOf(a), report.remaining)
        assertEquals(listOf(a), store.readPending("ST-2026-0001"))
        assertEquals(SyncReport(), SyncPendingCountsUseCase(repository, store)("EMPTY"))
    }

    @Test
    fun `the queue never holds the same client reference twice`() = runTest {
        val queue = PendingCountQueue(store)
        val a = CountSubmission("ref-a", "ST-2026-0001", "r1", "PCT-500", null, null, 1.0, "2026-09-30 10:00:00")
        queue.enqueue(a)
        queue.enqueue(a.copy(qty = 2.0))
        assertEquals(listOf(2.0), queue.pending("ST-2026-0001").map { it.qty })
        assertTrue(queue.remove("ST-2026-0001", "ref-a").isEmpty())
    }
}
