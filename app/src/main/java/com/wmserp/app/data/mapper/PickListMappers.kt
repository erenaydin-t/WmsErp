package com.wmserp.app.data.mapper

import com.wmserp.app.data.remote.dto.GeneratedDocumentDto
import com.wmserp.app.data.remote.dto.PickListDto
import com.wmserp.app.data.remote.dto.PickListItemDto
import com.wmserp.app.data.remote.dto.PickProgressItemRequest
import com.wmserp.app.data.remote.dto.PickScanMatchDto
import com.wmserp.app.domain.model.GeneratedDocument
import com.wmserp.app.domain.model.PickList
import com.wmserp.app.domain.model.PickListItem
import com.wmserp.app.domain.model.PickListPurpose
import com.wmserp.app.domain.model.PickProgressLine
import com.wmserp.app.domain.model.PickScanMatch
import com.wmserp.app.domain.model.PickScanMatchType
import com.wmserp.app.domain.model.PickingStatus

fun PickListDto.toDomain(): PickList = PickList(
    name = name,
    purpose = PickListPurpose.fromServer(purpose),
    purposeLabel = purpose.orEmpty(),
    company = company,
    customer = customer,
    customerName = customerName?.takeIf { it.isNotBlank() } ?: customer,
    parentWarehouse = parentWarehouse,
    targetWarehouse = targetWarehouse,
    status = status,
    pickingStatus = PickingStatus.fromServer(pickingStatus),
    picker = picker,
    assignedTo = assignedTo,
    pickingStartedAt = pickingStartedAt,
    pickingStartedBy = pickingStartedBy,
    pickingCompletedAt = pickingCompletedAt,
    pickingCompletedBy = pickingCompletedBy,
    generatedDocument = if (!generatedDoctype.isNullOrBlank() && !generatedDocname.isNullOrBlank()) {
        GeneratedDocument(generatedDoctype, generatedDocname, alreadyGenerated = true)
    } else {
        null
    },
    workOrder = workOrder,
    materialRequest = materialRequest,
    modified = modified,
    itemCount = if (items.isNotEmpty()) items.size else itemCount,
    requiredQty = if (items.isNotEmpty()) items.sumOf { it.requiredQty } else requiredQty,
    pickedQty = if (items.isNotEmpty()) items.sumOf { it.pickedQty } else pickedQty,
    items = items.map { it.toDomain() },
)

fun PickListItemDto.toDomain(): PickListItem = PickListItem(
    rowName = name,
    idx = idx,
    itemCode = itemCode,
    itemName = itemName?.takeIf { it.isNotBlank() } ?: itemCode,
    sourceWarehouse = warehouse,
    targetWarehouse = targetWarehouse,
    batchNo = batchNo?.takeIf { it.isNotBlank() },
    expiryDate = expiryDate?.takeIf { it.isNotBlank() },
    serialNo = serialNo?.takeIf { it.isNotBlank() },
    requiredQty = requiredQty,
    pickedQty = pickedQty,
    uom = uom,
    orderQty = orderQty,
    orderUom = orderUom,
    conversionFactor = if (conversionFactor > 0) conversionFactor else 1.0,
    hasBatchNo = hasBatchNo != 0,
    hasSerialNo = hasSerialNo != 0,
    optional = optional != 0,
    barcodes = barcodes,
    salesOrder = salesOrder,
    materialRequest = materialRequest,
)

fun GeneratedDocumentDto.toDomain(): GeneratedDocument = GeneratedDocument(
    doctype = doctype,
    name = name,
    alreadyGenerated = alreadyGenerated,
    docStatus = docstatus,
)

fun PickScanMatchDto.toDomain(): PickScanMatch = PickScanMatch(
    rowName = rowName?.takeIf { it.isNotBlank() },
    itemCode = itemCode,
    batchNo = batchNo?.takeIf { it.isNotBlank() },
    matchType = when (match) {
        "item_code" -> PickScanMatchType.ITEM_CODE
        "barcode" -> PickScanMatchType.BARCODE
        "batch" -> PickScanMatchType.BATCH
        "not_on_list" -> PickScanMatchType.NOT_ON_LIST
        else -> PickScanMatchType.NONE
    },
    expiryDate = expiryDate,
)

fun PickProgressLine.toRequest(): PickProgressItemRequest = PickProgressItemRequest(
    name = rowName,
    pickedQty = pickedQty,
    batchNo = batchNo?.trim()?.ifBlank { null },
    serialNo = serialNo?.trim()?.ifBlank { null },
)
