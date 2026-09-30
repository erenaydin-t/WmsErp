package com.wmserp.app.data.local

import com.wmserp.app.domain.model.CachedStocktaking
import com.wmserp.app.domain.model.CountItemStatus
import com.wmserp.app.domain.model.CountSubmission
import com.wmserp.app.domain.model.CountType
import com.wmserp.app.domain.model.CountingMode
import com.wmserp.app.domain.model.DuplicateCountPolicy
import com.wmserp.app.domain.model.MyStocktakingStats
import com.wmserp.app.domain.model.StocktakingItem
import com.wmserp.app.domain.model.StocktakingSession
import com.wmserp.app.domain.model.StocktakingStatus
import com.wmserp.app.domain.model.StocktakingTotals
import com.wmserp.app.domain.model.WmsQrKeys
import com.wmserp.app.domain.repository.StocktakingLocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * JSON files under a private directory (`filesDir/stocktaking` on Android): one for the session's
 * rows, one for its pending counts. Writes go to a temporary file first and are renamed into
 * place, so a crash mid-write never corrupts the queue.
 */
class FileStocktakingLocalStore(
    private val directory: File,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false },
) : StocktakingLocalStore {

    private val mutex = Mutex()

    override suspend fun readSession(name: String): CachedStocktaking? = mutex.withLock {
        withContext(Dispatchers.IO) {
            read(sessionFile(name))?.let { runCatching { json.decodeFromString(CachedStocktakingFile.serializer(), it).toDomain() }.getOrNull() }
        }
    }

    override suspend fun writeSession(cache: CachedStocktaking) = mutex.withLock {
        withContext(Dispatchers.IO) { write(sessionFile(cache.session.name), json.encodeToString(CachedStocktakingFile.serializer(), cache.toFile())) }
    }

    override suspend fun readPending(name: String): List<CountSubmission> = mutex.withLock {
        withContext(Dispatchers.IO) {
            read(pendingFile(name))?.let { runCatching { json.decodeFromString(PendingCountsFile.serializer(), it).counts.map { c -> c.toDomain() } }.getOrNull() }.orEmpty()
        }
    }

    override suspend fun writePending(name: String, pending: List<CountSubmission>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (pending.isEmpty()) pendingFile(name).delete() else write(pendingFile(name), json.encodeToString(PendingCountsFile.serializer(), PendingCountsFile(pending.map { it.toFile() })))
            Unit
        }
    }

    override suspend fun clear(name: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            sessionFile(name).delete()
            pendingFile(name).delete()
            Unit
        }
    }

    private fun sessionFile(name: String) = File(directory, "${safe(name)}.session.json")
    private fun pendingFile(name: String) = File(directory, "${safe(name)}.pending.json")
    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun read(file: File): String? = if (file.isFile) runCatching { file.readText() }.getOrNull() else null

    private fun write(file: File, content: String) {
        directory.mkdirs()
        val temp = File(directory, file.name + ".tmp")
        temp.writeText(content)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}

// ---- file format (kept apart from the domain models so the schema can evolve) --------------

@Serializable
internal data class PendingCountsFile(val counts: List<PendingCountFile> = emptyList())

@Serializable
internal data class PendingCountFile(
    val clientRef: String,
    val sessionName: String,
    val itemName: String? = null,
    val itemCode: String,
    val batchNo: String? = null,
    val warehouse: String? = null,
    val qty: Double,
    val deviceTime: String,
    val note: String? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
)

@Serializable
internal data class CachedStocktakingFile(
    val session: SessionFile,
    val items: List<ItemFile> = emptyList(),
    val barcodes: Map<String, List<String>> = emptyMap(),
    val cachedAtMillis: Long = 0L,
)

@Serializable
internal data class SessionFile(
    val name: String,
    val warehouse: String,
    val warehouseName: String? = null,
    val warehouses: List<String> = emptyList(),
    val company: String? = null,
    val postingDate: String? = null,
    val status: String = "",
    val mode: String = "",
    val blindCount: Boolean = false,
    val duplicatePolicy: String = "",
    val requireSecondCount: Boolean = true,
    val qtyTolerance: Double = 0.0,
    val frozen: Boolean = false,
    val startedAt: String? = null,
    val stockReconciliation: String? = null,
    val totals: TotalsFile = TotalsFile(),
    val my: MyFile = MyFile(),
    val canCount: Boolean = false,
    val isSupervisor: Boolean = false,
    val counters: List<String> = emptyList(),
    val qrItemKey: String = WmsQrKeys.DEFAULT_ITEM_KEY,
    val qrBatchKey: String = WmsQrKeys.DEFAULT_BATCH_KEY,
    val modified: String? = null,
)

@Serializable
internal data class TotalsFile(
    val total: Int = 0,
    val counted: Int = 0,
    val uncounted: Int = 0,
    val matched: Int = 0,
    val variance: Int = 0,
    val recountRequired: Int = 0,
    val pendingReview: Int = 0,
    val approved: Int = 0,
    val qtyVariance: Double? = null,
    val valueVariance: Double? = null,
)

@Serializable
internal data class MyFile(val assigned: Int = 0, val open: Int = 0, val itemsCounted: Int = 0, val counts: Int = 0)

@Serializable
internal data class ItemFile(
    val name: String,
    val itemCode: String,
    val itemName: String,
    val warehouse: String,
    val batchNo: String? = null,
    val expiryDate: String? = null,
    val uom: String? = null,
    val erpQty: Double? = null,
    val status: String = "",
    val nextCountType: String = "",
    val counter: String? = null,
    val counterName: String? = null,
    val isMine: Boolean = false,
    val count1: Double? = null,
    val count2: Double? = null,
    val recountQty: Double? = null,
    val recountCount: Int = 0,
    val finalQty: Double? = null,
    val qtyDifference: Double? = null,
    val valueDifference: Double? = null,
    val countedBy: String? = null,
    val countedByName: String? = null,
    val countedAt: String? = null,
    val recountNote: String? = null,
    val location: String? = null,
    val hasBatchNo: Boolean = false,
    val addedDuringCount: Boolean = false,
    val modified: String? = null,
)

internal fun CountSubmission.toFile() = PendingCountFile(clientRef, sessionName, itemName, itemCode, batchNo, warehouse, qty, deviceTime, note, attempts, lastError)
internal fun PendingCountFile.toDomain() = CountSubmission(clientRef, sessionName, itemName, itemCode, batchNo, warehouse, qty, deviceTime, note, attempts, lastError)

internal fun CachedStocktaking.toFile() = CachedStocktakingFile(session.toFile(), items.map { it.toFile() }, barcodes, cachedAtMillis)
internal fun CachedStocktakingFile.toDomain() = CachedStocktaking(session.toDomain(), items.map { it.toDomain() }, barcodes, cachedAtMillis)

internal fun StocktakingSession.toFile() = SessionFile(
    name, warehouse, warehouseName, warehouses, company, postingDate, status.serverValue, mode.serverValue, blindCount, duplicatePolicy.serverValue,
    requireSecondCount, qtyTolerance, frozen, startedAt, stockReconciliation,
    TotalsFile(totals.total, totals.counted, totals.uncounted, totals.matched, totals.variance, totals.recountRequired, totals.pendingReview, totals.approved, totals.qtyVariance, totals.valueVariance),
    MyFile(my.assigned, my.open, my.itemsCounted, my.counts), canCount, isSupervisor, counters, qrKeys.itemKey, qrKeys.batchKey, modified,
)

internal fun SessionFile.toDomain() = StocktakingSession(
    name = name,
    warehouse = warehouse,
    warehouseName = warehouseName ?: warehouse,
    warehouses = warehouses.ifEmpty { listOf(warehouse) },
    company = company,
    postingDate = postingDate,
    status = StocktakingStatus.fromServer(status),
    mode = CountingMode.fromServer(mode),
    blindCount = blindCount,
    duplicatePolicy = DuplicateCountPolicy.fromServer(duplicatePolicy),
    requireSecondCount = requireSecondCount,
    qtyTolerance = qtyTolerance,
    frozen = frozen,
    startedAt = startedAt,
    stockReconciliation = stockReconciliation,
    totals = StocktakingTotals(totals.total, totals.counted, totals.uncounted, totals.matched, totals.variance, totals.recountRequired, totals.pendingReview, totals.approved, totals.qtyVariance, totals.valueVariance),
    my = MyStocktakingStats(my.assigned, my.open, my.itemsCounted, my.counts),
    canCount = canCount,
    isSupervisor = isSupervisor,
    counters = counters,
    qrKeys = WmsQrKeys(qrItemKey, qrBatchKey),
    modified = modified,
)

internal fun StocktakingItem.toFile() = ItemFile(
    name, itemCode, itemName, warehouse, batchNo, expiryDate, uom, erpQty, status.serverValue, nextCountType.serverValue, counter, counterName, isMine,
    count1, count2, recountQty, recountCount, finalQty, qtyDifference, valueDifference, countedBy, countedByName, countedAt, recountNote, location,
    hasBatchNo, addedDuringCount, modified,
)

internal fun ItemFile.toDomain() = StocktakingItem(
    name = name,
    itemCode = itemCode,
    itemName = itemName,
    warehouse = warehouse,
    batchNo = batchNo,
    expiryDate = expiryDate,
    uom = uom,
    erpQty = erpQty,
    status = CountItemStatus.fromServer(status),
    nextCountType = CountType.fromServer(nextCountType),
    counter = counter,
    counterName = counterName,
    isMine = isMine,
    count1 = count1,
    count2 = count2,
    recountQty = recountQty,
    recountCount = recountCount,
    finalQty = finalQty,
    qtyDifference = qtyDifference,
    valueDifference = valueDifference,
    countedBy = countedBy,
    countedByName = countedByName,
    countedAt = countedAt,
    recountNote = recountNote,
    location = location,
    hasBatchNo = hasBatchNo,
    addedDuringCount = addedDuringCount,
    modified = modified,
)
