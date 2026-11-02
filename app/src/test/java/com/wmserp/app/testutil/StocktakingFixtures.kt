package com.wmserp.app.testutil

import com.wmserp.app.domain.model.CachedStocktaking
import com.wmserp.app.domain.model.CountItemStatus
import com.wmserp.app.domain.model.CountResult
import com.wmserp.app.domain.model.CountSubmission
import com.wmserp.app.domain.model.CountingMode
import com.wmserp.app.domain.model.MyStocktakingStats
import com.wmserp.app.domain.model.StocktakingItem
import com.wmserp.app.domain.model.StocktakingSession
import com.wmserp.app.domain.model.StocktakingStatus
import com.wmserp.app.domain.model.StocktakingTotals
import com.wmserp.app.domain.repository.StocktakingLocalStore

/** A small pharmaceutical stocktaking: two batches of paracetamol, gauze without batch, aspirin counted by Reza. */
object StocktakingFixtures {
    const val USER = "user@example.com"
    const val OTHER = "reza@example.com"

    val session = StocktakingSession(
        name = "ST-2026-0001",
        warehouse = "Main - C",
        warehouseName = "Main Warehouse",
        company = "WM Co",
        postingDate = "2026-09-30",
        status = StocktakingStatus.COUNTING,
        mode = CountingMode.ASSIGNED,
        totals = StocktakingTotals(total = 4, counted = 1, uncounted = 3, matched = 1),
        my = MyStocktakingStats(assigned = 3, open = 3),
        canCount = true,
        counters = listOf(USER, OTHER),
    )

    val paracetamolB1 = StocktakingItem(
        name = "r1", itemCode = "PCT-500", itemName = "Paracetamol 500mg", warehouse = "Main - C", batchNo = "PCT-250901", expiryDate = "2028-09-01",
        uom = "Nos", erpQty = 100.0, status = CountItemStatus.ASSIGNED, counter = USER, counterName = "Eren", isMine = true, hasBatchNo = true,
    )
    val paracetamolB2 = paracetamolB1.copy(name = "r2", batchNo = "PCT-260101", expiryDate = "2029-01-01", erpQty = 20.0)
    val gauze = StocktakingItem(
        name = "r3", itemCode = "GZ-10", itemName = "Gauze pads", warehouse = "Main - C", uom = "Box", erpQty = 50.0,
        status = CountItemStatus.ASSIGNED, counter = USER, counterName = "Eren", isMine = true,
    )
    val aspirinCounted = StocktakingItem(
        name = "r4", itemCode = "ASP-100", itemName = "Aspirin 100mg", warehouse = "Main - C", batchNo = "ASP-1", uom = "Nos", erpQty = 12.0,
        status = CountItemStatus.COUNTED, counter = OTHER, counterName = "Reza", isMine = false, count1 = 12.0, finalQty = 12.0, qtyDifference = 0.0,
        countedBy = OTHER, countedByName = "Reza", countedAt = "2026-09-30 10:32:00", hasBatchNo = true,
    )

    val items = listOf(paracetamolB1, paracetamolB2, gauze, aspirinCounted)
    val barcodes = mapOf("GZ-10" to listOf("8690000000017"))

    fun cache(session: StocktakingSession = this.session, items: List<StocktakingItem> = this.items) = CachedStocktaking(session, items, barcodes, cachedAtMillis = 1_000L)

    fun confirmed(submission: CountSubmission, item: StocktakingItem, outcome: com.wmserp.app.domain.model.CountOutcome, totals: StocktakingTotals? = null) =
        CountResult(outcome = outcome, item = item, totals = totals, sessionStatus = StocktakingStatus.COUNTING, clientRef = submission.clientRef)
}

/** Device store without files, for view model and use case tests. */
class InMemoryStocktakingLocalStore : StocktakingLocalStore {
    val sessions = mutableMapOf<String, CachedStocktaking>()
    val pending = mutableMapOf<String, List<CountSubmission>>()
    var writes = 0

    override suspend fun readSession(name: String): CachedStocktaking? = sessions[name]
    override suspend fun writeSession(cache: CachedStocktaking) { sessions[cache.session.name] = cache; writes++ }
    override suspend fun readPending(name: String): List<CountSubmission> = pending[name].orEmpty()
    override suspend fun writePending(name: String, pending: List<CountSubmission>) { if (pending.isEmpty()) this.pending.remove(name) else this.pending[name] = pending }
    override suspend fun clear(name: String) { sessions.remove(name); pending.remove(name) }
}
