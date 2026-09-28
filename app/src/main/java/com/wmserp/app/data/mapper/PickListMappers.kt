package com.wmserp.app.data.mapper

import com.wmserp.app.data.remote.dto.GeneratedDocumentDto
import com.wmserp.app.data.remote.dto.PickListDto
import com.wmserp.app.data.remote.dto.PickListItemDto
import com.wmserp.app.data.remote.dto.PickerKpisDto
import com.wmserp.app.data.remote.dto.RowUpdateDto
import com.wmserp.app.data.remote.dto.WmsSettingsDto
import com.wmserp.app.domain.model.GeneratedDocument
import com.wmserp.app.domain.model.PickList
import com.wmserp.app.domain.model.PickListItem
import com.wmserp.app.domain.model.PickListPurpose
import com.wmserp.app.domain.model.PickRowStatus
import com.wmserp.app.domain.model.PickerKpis
import com.wmserp.app.domain.model.PickingStatus
import com.wmserp.app.domain.model.RowUpdate
import com.wmserp.app.domain.model.WmsQrKeys

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
    cardStartedAt = cardStartedAt,
    cardCompletedAt = cardCompletedAt,
    generatedDocument = if (!generatedDoctype.isNullOrBlank() && !generatedDocname.isNullOrBlank()) {
        GeneratedDocument(generatedDoctype, generatedDocname, alreadyGenerated = true)
    } else {
        null
    },
    workOrder = workOrder,
    materialRequest = materialRequest,
    modified = modified,
    itemCount = itemCount,
    pickedRows = pickedRows,
    requiredQty = requiredQty,
    pickedQty = pickedQty,
    myRowCount = myRowCount,
    myPickedRows = myPickedRows,
    myOpenRows = myOpenRows,
    allRowsPicked = allRowsPicked,
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
    salesOrder = salesOrder,
    materialRequest = materialRequest,
    picker = picker?.takeIf { it.isNotBlank() },
    isMine = isMine,
    rowStatus = PickRowStatus.fromServer(rowStatus),
    rowStartedAt = rowStartedAt,
    rowCompletedAt = rowCompletedAt,
    durationSeconds = durationSeconds,
)

fun RowUpdateDto.toDomain(): RowUpdate = RowUpdate(
    pickList = pickList.toDomain(),
    row = row?.toDomain(),
    rowCompleted = rowCompleted,
    cardCompleted = cardCompleted,
    isLastPicker = isLastPicker,
)

fun WmsSettingsDto.toDomain(): WmsQrKeys = WmsQrKeys(
    itemKey = qrItemKey?.trim()?.ifBlank { null } ?: WmsQrKeys.DEFAULT_ITEM_KEY,
    batchKey = qrBatchKey?.trim()?.ifBlank { null } ?: WmsQrKeys.DEFAULT_BATCH_KEY,
)

fun PickerKpisDto.toDomain(): PickerKpis = PickerKpis(
    date = date.orEmpty(),
    rowsPicked = rowsPicked,
    qtyPicked = qtyPicked,
    pickListsTouched = pickListsTouched,
    pickListsCompleted = pickListsCompleted,
    openRows = openRows,
    openPickLists = openPickLists,
    totalSeconds = totalSeconds,
    avgSecondsPerRow = avgSecondsPerRow,
    fastestSeconds = fastestSeconds,
    slowestSeconds = slowestSeconds,
    rowsPerHour = rowsPerHour,
)

fun GeneratedDocumentDto.toDomain(): GeneratedDocument = GeneratedDocument(
    doctype = doctype,
    name = name,
    alreadyGenerated = alreadyGenerated,
    docStatus = docstatus,
)
