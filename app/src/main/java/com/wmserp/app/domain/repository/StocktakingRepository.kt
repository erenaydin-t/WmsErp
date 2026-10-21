package com.wmserp.app.domain.repository

import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.CachedStocktaking
import com.wmserp.app.domain.model.CountResult
import com.wmserp.app.domain.model.CountSubmission
import com.wmserp.app.domain.model.StocktakingItemsPage
import com.wmserp.app.domain.model.StocktakingLookup
import com.wmserp.app.domain.model.StocktakingSession
import com.wmserp.app.domain.model.SyncOutcome

/** Backed by the `wmserp_picking.api.stocktaking` whitelisted methods of the ERPNext custom app. */
interface StocktakingRepository {
    /** Sessions the signed-in user can count in right now (Counting / Recount). */
    suspend fun getMySessions(): AppResult<List<StocktakingSession>>
    suspend fun getSession(name: String): AppResult<StocktakingSession>

    /** One page of rows with the barcodes of their items; [mineOnly] limits to the caller's rows. */
    suspend fun getItems(name: String, start: Int = 0, limit: Int = 1000, mineOnly: Boolean = false): AppResult<StocktakingItemsPage>

    /** Server-side identification of a scan that the device cache could not resolve. */
    suspend fun lookup(name: String, code: String): AppResult<StocktakingLookup>

    suspend fun submitCount(submission: CountSubmission): AppResult<CountResult>

    /** Replays queued counts in order; one result per submission (matched by `clientRef`). */
    suspend fun syncCounts(name: String, submissions: List<CountSubmission>): AppResult<SyncOutcome>
}

/**
 * Device-side storage for one session: the downloaded rows (so scans resolve without a network)
 * and the counts that still have to reach ERPNext. A submitted count must never look lost because
 * the warehouse Wi-Fi dropped, so the queue is written before the network is tried.
 */
interface StocktakingLocalStore {
    suspend fun readSession(name: String): CachedStocktaking?
    suspend fun writeSession(cache: CachedStocktaking)
    suspend fun readPending(name: String): List<CountSubmission>
    suspend fun writePending(name: String, pending: List<CountSubmission>)
    suspend fun clear(name: String)
}
