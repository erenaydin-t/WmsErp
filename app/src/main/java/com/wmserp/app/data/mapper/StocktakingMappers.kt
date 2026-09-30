package com.wmserp.app.data.mapper

import com.wmserp.app.data.remote.dto.CountResultDto
import com.wmserp.app.data.remote.dto.MyStocktakingStatsDto
import com.wmserp.app.data.remote.dto.StocktakingItemDto
import com.wmserp.app.data.remote.dto.StocktakingItemsPageDto
import com.wmserp.app.data.remote.dto.StocktakingLookupDto
import com.wmserp.app.data.remote.dto.StocktakingSessionDto
import com.wmserp.app.data.remote.dto.StocktakingTotalsDto
import com.wmserp.app.data.remote.dto.SyncCountsDto
import com.wmserp.app.domain.model.CountItemStatus
import com.wmserp.app.domain.model.CountOutcome
import com.wmserp.app.domain.model.CountRejection
import com.wmserp.app.domain.model.CountResult
import com.wmserp.app.domain.model.CountType
import com.wmserp.app.domain.model.CountingMode
import com.wmserp.app.domain.model.DuplicateCountPolicy
import com.wmserp.app.domain.model.MyStocktakingStats
import com.wmserp.app.domain.model.StocktakingItem
import com.wmserp.app.domain.model.StocktakingItemsPage
import com.wmserp.app.domain.model.StocktakingLookup
import com.wmserp.app.domain.model.StocktakingSession
import com.wmserp.app.domain.model.StocktakingStatus
import com.wmserp.app.domain.model.StocktakingTotals
import com.wmserp.app.domain.model.SyncOutcome
import com.wmserp.app.domain.model.WmsQrKeys

fun StocktakingTotalsDto.toDomain(): StocktakingTotals = StocktakingTotals(
    total = totalItems,
    counted = countedItems,
    uncounted = uncountedItems,
    matched = matchedItems,
    variance = varianceItems,
    recountRequired = recountRequired,
    pendingReview = pendingReview,
    approved = approvedItems,
    qtyVariance = qtyVariance,
    valueVariance = valueVariance,
)

fun MyStocktakingStatsDto.toDomain(): MyStocktakingStats = MyStocktakingStats(assigned = assigned, open = open, itemsCounted = itemsCounted, counts = counts)

fun StocktakingSessionDto.toDomain(): StocktakingSession = StocktakingSession(
    name = name,
    warehouse = warehouse,
    warehouseName = warehouseName?.takeIf { it.isNotBlank() } ?: warehouse,
    warehouses = warehouses.ifEmpty { listOf(warehouse) },
    company = company,
    postingDate = postingDate,
    status = StocktakingStatus.fromServer(status),
    mode = CountingMode.fromServer(countingMode),
    blindCount = blindCount,
    duplicatePolicy = DuplicateCountPolicy.fromServer(duplicateCountPolicy),
    requireSecondCount = requireSecondCount,
    qtyTolerance = qtyTolerance,
    frozen = frozen,
    startedAt = startedAt,
    stockReconciliation = stockReconciliation,
    totals = totals.toDomain(),
    my = my.toDomain(),
    canCount = canCount,
    isSupervisor = isSupervisor,
    counters = counters,
    qrKeys = WmsQrKeys(
        itemKey = qrItemKey?.trim()?.ifBlank { null } ?: WmsQrKeys.DEFAULT_ITEM_KEY,
        batchKey = qrBatchKey?.trim()?.ifBlank { null } ?: WmsQrKeys.DEFAULT_BATCH_KEY,
    ),
    modified = modified,
)

fun StocktakingItemDto.toDomain(): StocktakingItem = StocktakingItem(
    name = name,
    itemCode = itemCode,
    itemName = itemName?.takeIf { it.isNotBlank() } ?: itemCode,
    warehouse = warehouse,
    batchNo = batchNo?.takeIf { it.isNotBlank() },
    expiryDate = expiryDate?.takeIf { it.isNotBlank() },
    uom = stockUom,
    erpQty = erpQty,
    status = CountItemStatus.fromServer(status),
    nextCountType = CountType.fromServer(nextCountType),
    counter = counter?.takeIf { it.isNotBlank() },
    counterName = counterName?.takeIf { it.isNotBlank() },
    isMine = isMine,
    count1 = count1,
    count2 = count2,
    recountQty = recountQty,
    recountCount = recountCount,
    finalQty = finalQty,
    qtyDifference = qtyDifference,
    valueDifference = valueDifference,
    countedBy = countedBy?.takeIf { it.isNotBlank() },
    countedByName = countedByName?.takeIf { it.isNotBlank() },
    countedAt = countedAt,
    recountNote = recountNote?.takeIf { it.isNotBlank() },
    location = location?.takeIf { it.isNotBlank() },
    hasBatchNo = hasBatchNo,
    addedDuringCount = addedDuringCount,
    modified = modified,
)

fun StocktakingItemsPageDto.toDomain(): StocktakingItemsPage = StocktakingItemsPage(
    items = items.map { it.toDomain() },
    barcodes = barcodes,
    start = start,
    limit = limit,
    total = total,
    sessionStatus = StocktakingStatus.fromServer(sessionStatus),
)

fun StocktakingLookupDto.toDomain(): StocktakingLookup = StocktakingLookup(
    found = found,
    raw = raw.orEmpty(),
    itemCode = itemCode,
    itemName = itemName,
    batchNo = batchNo?.takeIf { it.isNotBlank() },
    expiryDate = expiryDate,
    hasBatchNo = hasBatchNo,
    uom = stockUom,
    canAdd = canAdd,
    inSession = inSession,
    rows = rows.map { it.toDomain() },
)

fun CountResultDto.toDomain(): CountResult = CountResult(
    outcome = CountOutcome.fromServer(outcome),
    item = item?.toDomain(),
    totals = totals?.toDomain(),
    sessionStatus = sessionStatus?.let { StocktakingStatus.fromServer(it) },
    rejection = reason?.let { CountRejection.fromServer(it) },
    message = message,
    countedBy = countedBy,
    countedByName = countedByName,
    counter = counter,
    counterName = counterName,
    clientRef = clientRef,
)

fun SyncCountsDto.toDomain(): SyncOutcome = SyncOutcome(
    results = results.map { it.toDomain() },
    totals = totals?.toDomain(),
    sessionStatus = sessionStatus?.let { StocktakingStatus.fromServer(it) },
)
