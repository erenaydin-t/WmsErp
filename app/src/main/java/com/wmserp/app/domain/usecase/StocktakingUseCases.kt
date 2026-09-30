package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.common.ErrorCode
import com.wmserp.app.domain.model.CachedStocktaking
import com.wmserp.app.domain.model.CountItemStatus
import com.wmserp.app.domain.model.CountOutcome
import com.wmserp.app.domain.model.CountRejection
import com.wmserp.app.domain.model.CountResult
import com.wmserp.app.domain.model.CountSubmission
import com.wmserp.app.domain.model.CountType
import com.wmserp.app.domain.model.CountingMode
import com.wmserp.app.domain.model.DuplicateCountPolicy
import com.wmserp.app.domain.model.StocktakingItem
import com.wmserp.app.domain.model.StocktakingSession
import com.wmserp.app.domain.model.WmsQrKeys
import com.wmserp.app.domain.model.countMatches
import com.wmserp.app.domain.repository.StocktakingLocalStore
import com.wmserp.app.domain.repository.StocktakingRepository
import javax.inject.Inject

class GetMyStocktakingSessionsUseCase @Inject constructor(private val repository: StocktakingRepository) {
    suspend operator fun invoke(): AppResult<List<StocktakingSession>> = repository.getMySessions()
}

/** A session with its rows: fresh from the server when reachable, else the copy kept on the device. */
data class LoadedStocktaking(val cache: CachedStocktaking, val fromCache: Boolean)

/**
 * Downloads the session header and every row the counter works with (paged, thousands of rows
 * are normal) and stores them on the device. When the server cannot be reached the device copy is
 * used instead, so counting can go on and the counts queue up.
 */
class LoadStocktakingSessionUseCase @Inject constructor(
    private val repository: StocktakingRepository,
    private val store: StocktakingLocalStore,
) {
    suspend operator fun invoke(
        name: String,
        now: Long = System.currentTimeMillis(),
        onProgress: (loaded: Int, total: Int) -> Unit = { _, _ -> },
    ): AppResult<LoadedStocktaking> {
        val session = when (val result = repository.getSession(name.trim())) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return fallback(name, result.error)
        }
        val items = ArrayList<StocktakingItem>()
        val barcodes = HashMap<String, List<String>>()
        var start = 0
        var total: Int
        do {
            val page = when (val result = repository.getItems(session.name, start = start, limit = PAGE_SIZE, mineOnly = session.mineOnly)) {
                is AppResult.Success -> result.data
                is AppResult.Failure -> return fallback(name, result.error)
            }
            items += page.items
            barcodes += page.barcodes
            total = page.total
            start += page.items.size
            onProgress(items.size, total)
        } while (page.items.isNotEmpty() && items.size < total)
        val cache = CachedStocktaking(session = session, items = items.distinctBy { it.name }, barcodes = barcodes, cachedAtMillis = now)
        store.writeSession(cache)
        return AppResult.Success(LoadedStocktaking(cache, fromCache = false))
    }

    private suspend fun fallback(name: String, error: AppError): AppResult<LoadedStocktaking> {
        if (error !is AppError.Network) return AppResult.Failure(error)
        val cached = store.readSession(name.trim()) ?: return AppResult.Failure(error)
        return AppResult.Success(LoadedStocktaking(cached, fromCache = true))
    }

    companion object {
        const val PAGE_SIZE = 1000
    }
}

/** Where a scan landed against the rows kept on the device. */
sealed class CountScanOutcome {
    /** Exactly one row: item and (when the label carried one) batch. */
    data class Match(val row: StocktakingItem) : CountScanOutcome()

    /** A plain barcode / item code of a batch-tracked item: the counter picks the batch from the physical label. */
    data class ChooseBatch(val itemCode: String, val itemName: String, val rows: List<StocktakingItem>) : CountScanOutcome()

    /** A JSON label (or a known item) that is not among the downloaded rows; the server can add it. */
    data class NotInSession(val itemCode: String?, val batchNo: String?, val raw: String) : CountScanOutcome()

    /** Empty scan. */
    data class Invalid(val raw: String) : CountScanOutcome()
}

/**
 * Identifies a scan without the network: a JSON QR label gives item + batch, anything else is
 * matched against the item codes and the item barcodes of the downloaded rows.
 */
object CountScanResolver {
    fun resolve(rawCode: String, keys: WmsQrKeys, items: List<StocktakingItem>, barcodes: Map<String, List<String>>): CountScanOutcome {
        val text = ScanCodeSanitizer.sanitize(rawCode)
        if (text.isEmpty()) return CountScanOutcome.Invalid(rawCode)
        val parsed = QrLabelParser.parse(text, keys)
        if (parsed is QrParseResult.Valid) {
            val label = parsed.label
            val byItem = items.filter { it.itemCode.equals(label.itemCode, ignoreCase = true) }
            if (byItem.isEmpty()) return CountScanOutcome.NotInSession(label.itemCode, label.batchNo, text)
            val batch = label.batchNo
            if (batch != null) {
                val exact = byItem.filter { it.batchNo.equals(batch, ignoreCase = true) }
                return if (exact.size == 1) CountScanOutcome.Match(exact.first()) else if (exact.isEmpty()) CountScanOutcome.NotInSession(label.itemCode, batch, text) else CountScanOutcome.ChooseBatch(label.itemCode, exact.first().itemName, exact)
            }
            return single(byItem, label.itemCode, text)
        }
        // Plain barcode: item barcode, item code or batch number.
        val byBarcode = barcodes.filterValues { list -> list.any { it.equals(text, ignoreCase = true) } }.keys
        val byItem = items.filter { it.itemCode.equals(text, ignoreCase = true) || it.itemCode in byBarcode }
        if (byItem.isNotEmpty()) return single(byItem, byItem.first().itemCode, text)
        val byBatch = items.filter { it.batchNo.equals(text, ignoreCase = true) }
        if (byBatch.size == 1) return CountScanOutcome.Match(byBatch.first())
        if (byBatch.size > 1) return CountScanOutcome.ChooseBatch(byBatch.first().itemCode, byBatch.first().itemName, byBatch)
        return CountScanOutcome.NotInSession(null, null, text)
    }

    private fun single(rows: List<StocktakingItem>, itemCode: String, raw: String): CountScanOutcome {
        if (rows.size == 1) return CountScanOutcome.Match(rows.first())
        val withoutBatch = rows.filter { it.batchNo == null }
        // Several warehouses or batches: the counter chooses; a single batch-less row of a batch
        // item still needs the batch label, so ChooseBatch lists what the session holds.
        return if (withoutBatch.size == 1 && rows.size == 1) CountScanOutcome.Match(withoutBatch.first()) else CountScanOutcome.ChooseBatch(itemCode, rows.first().itemName, rows)
    }
}

enum class CountRefusal { ALREADY_COUNTED, NOT_ASSIGNED, SESSION_CLOSED, FINALIZED }

/** The device-side prediction of a count, applied immediately so the counter never waits for the network. */
data class LocalCountEvaluation(val outcome: CountOutcome, val countType: CountType, val item: StocktakingItem)

/** Mirrors `wmserp_picking.stocktaking.rules.evaluate_count` so the screen can move on before the server answers. */
object CountEvaluator {
    fun refusal(item: StocktakingItem, session: StocktakingSession, user: String?): CountRefusal? {
        if (!session.status.acceptsCounts) return CountRefusal.SESSION_CLOSED
        if (item.status == CountItemStatus.FINALIZED) return CountRefusal.FINALIZED
        // Who counted it matters more to the counter than who it was assigned to, so the lock comes first.
        if (item.status.isCounted && session.duplicatePolicy == DuplicateCountPolicy.LOCK) return CountRefusal.ALREADY_COUNTED
        if (session.mode == CountingMode.ASSIGNED && !session.isSupervisor && item.counter != null && user != null && item.counter != user) return CountRefusal.NOT_ASSIGNED
        return null
    }

    /**
     * The device's prediction. A row whose ERP quantity is unknown here (blind count, a row the
     * session does not hold yet) is accepted provisionally; the server's verdict arrives with the sync.
     */
    fun evaluate(item: StocktakingItem, qty: Double, session: StocktakingSession, user: String?, countedAt: String?): LocalCountEvaluation {
        val matched = item.erpQty == null || countMatches(qty, item.erpQty, session.qtyTolerance)
        fun updated(status: CountItemStatus, type: CountType, final: Double?, next: CountType?): StocktakingItem {
            val diff = if (final == null || item.erpQty == null) 0.0 else final - item.erpQty
            return item.copy(
                status = status,
                nextCountType = next ?: CountType.COUNT_1,
                count1 = if (type == CountType.COUNT_1) qty else item.count1,
                count2 = if (type == CountType.COUNT_2) qty else item.count2,
                recountQty = if (type == CountType.RECOUNT) qty else item.recountQty,
                recountCount = if (type == CountType.RECOUNT) item.recountCount + 1 else item.recountCount,
                finalQty = final,
                qtyDifference = if (item.erpQty == null) null else diff,
                countedBy = user ?: item.countedBy,
                countedAt = countedAt ?: item.countedAt,
                counter = item.counter ?: if (session.mode == CountingMode.ASSIGNED) user else null,
                isMine = item.isMine || (session.mode == CountingMode.ASSIGNED && item.counter == null),
            )
        }
        return when {
            item.status.needsCount && item.status != CountItemStatus.RECOUNT_REQUIRED -> when {
                matched -> LocalCountEvaluation(CountOutcome.ACCEPTED, CountType.COUNT_1, updated(CountItemStatus.COUNTED, CountType.COUNT_1, qty, null))
                !session.requireSecondCount -> LocalCountEvaluation(CountOutcome.MANAGER_REVIEW, CountType.COUNT_1, updated(CountItemStatus.MANAGER_REVIEW, CountType.COUNT_1, qty, null))
                else -> LocalCountEvaluation(CountOutcome.SECOND_COUNT_REQUIRED, CountType.COUNT_1, updated(CountItemStatus.RECOUNT_REQUIRED, CountType.COUNT_1, null, CountType.COUNT_2))
            }
            item.status == CountItemStatus.RECOUNT_REQUIRED && item.nextCountType == CountType.COUNT_2 -> when {
                matched -> LocalCountEvaluation(CountOutcome.ACCEPTED, CountType.COUNT_2, updated(CountItemStatus.COUNTED, CountType.COUNT_2, qty, null))
                else -> LocalCountEvaluation(CountOutcome.MANAGER_REVIEW, CountType.COUNT_2, updated(CountItemStatus.MANAGER_REVIEW, CountType.COUNT_2, qty, null))
            }
            item.status == CountItemStatus.RECOUNT_REQUIRED -> when {
                matched -> LocalCountEvaluation(CountOutcome.ACCEPTED, CountType.RECOUNT, updated(CountItemStatus.COUNTED, CountType.RECOUNT, qty, null))
                else -> LocalCountEvaluation(CountOutcome.RECOUNT_RECORDED, CountType.RECOUNT, updated(CountItemStatus.RECOUNTED, CountType.RECOUNT, qty, null))
            }
            // Already counted and the session allows additional counts.
            else -> LocalCountEvaluation(
                CountOutcome.ADDITIONAL_COUNT,
                CountType.RECOUNT,
                updated(if (matched) CountItemStatus.COUNTED else CountItemStatus.MANAGER_REVIEW, CountType.RECOUNT, qty, null),
            )
        }
    }
}

/** Sends one count; a network failure is reported as such so the caller can queue it. */
class SubmitStocktakingCountUseCase @Inject constructor(private val repository: StocktakingRepository) {
    suspend operator fun invoke(submission: CountSubmission): AppResult<CountResult> {
        if (submission.qty < 0.0) return AppResult.Failure(AppError.Validation("A count cannot be negative", ErrorCode.COUNT_QTY_INVALID))
        return repository.submitCount(submission)
    }
}

/** What a sync pass did: confirmed counts, counts the server refused (dropped from the queue), and what is still waiting. */
data class SyncReport(
    val confirmed: List<CountResult> = emptyList(),
    val refused: List<CountResult> = emptyList(),
    val remaining: List<CountSubmission> = emptyList(),
    /** The server could not be reached; everything stays queued. */
    val offline: Boolean = false,
    val error: AppError? = null,
) {
    val synced: Int get() = confirmed.size
}

/**
 * Replays the counts queued for a session, oldest first. Rejections (already counted by someone
 * else meanwhile, assigned to another counter...) are final: the count leaves the queue and the
 * caller shows the server's message. A network failure keeps the whole queue.
 */
class SyncPendingCountsUseCase @Inject constructor(
    private val repository: StocktakingRepository,
    private val store: StocktakingLocalStore,
) {
    suspend operator fun invoke(sessionName: String): SyncReport {
        val pending = store.readPending(sessionName)
        if (pending.isEmpty()) return SyncReport()
        return when (val result = repository.syncCounts(sessionName, pending)) {
            is AppResult.Failure -> SyncReport(remaining = pending, offline = result.error is AppError.Network, error = result.error)
            is AppResult.Success -> {
                val byRef = result.data.results.associateBy { it.clientRef }
                val confirmed = ArrayList<CountResult>()
                val refused = ArrayList<CountResult>()
                val remaining = ArrayList<CountSubmission>()
                for (submission in pending) {
                    val outcome = byRef[submission.clientRef]
                    when {
                        outcome == null -> remaining += submission.copy(attempts = submission.attempts + 1)
                        outcome.isRejected -> refused += outcome
                        else -> confirmed += outcome
                    }
                }
                store.writePending(sessionName, remaining)
                SyncReport(confirmed = confirmed, refused = refused, remaining = remaining)
            }
        }
    }
}

/** The offline queue of a session. */
class PendingCountQueue @Inject constructor(private val store: StocktakingLocalStore) {
    suspend fun pending(sessionName: String): List<CountSubmission> = store.readPending(sessionName)

    suspend fun enqueue(submission: CountSubmission): List<CountSubmission> {
        val pending = store.readPending(submission.sessionName).filterNot { it.clientRef == submission.clientRef } + submission
        store.writePending(submission.sessionName, pending)
        return pending
    }

    suspend fun remove(sessionName: String, clientRef: String): List<CountSubmission> {
        val pending = store.readPending(sessionName).filterNot { it.clientRef == clientRef }
        store.writePending(sessionName, pending)
        return pending
    }
}

/** Keeps the device copy of the rows in step with what the server confirmed. */
class UpdateCachedStocktakingUseCase @Inject constructor(private val store: StocktakingLocalStore) {
    suspend operator fun invoke(cache: CachedStocktaking) = store.writeSession(cache)
}

/** Which rejection the server sent, as a refusal the screen already knows how to show. */
fun CountRejection.toRefusal(): CountRefusal? = when (this) {
    CountRejection.ALREADY_COUNTED -> CountRefusal.ALREADY_COUNTED
    CountRejection.NOT_ASSIGNED -> CountRefusal.NOT_ASSIGNED
    CountRejection.SESSION_CLOSED -> CountRefusal.SESSION_CLOSED
    CountRejection.FINALIZED -> CountRefusal.FINALIZED
    CountRejection.VALIDATION, CountRejection.PERMISSION, CountRejection.UNKNOWN -> null
}

/** Asks the server to identify a label the device cache could not resolve (needs the network). */
class LookupStocktakingScanUseCase @Inject constructor(private val repository: StocktakingRepository) {
    suspend operator fun invoke(sessionName: String, rawCode: String): AppResult<com.wmserp.app.domain.model.StocktakingLookup> {
        val code = ScanCodeSanitizer.sanitize(rawCode)
        if (code.isEmpty()) return AppResult.Failure(AppError.Validation("Empty barcode", ErrorCode.EMPTY_BARCODE))
        return repository.lookup(sessionName, code)
    }
}
