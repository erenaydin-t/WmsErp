package com.wmserp.app.data.mapper

import com.wmserp.app.data.remote.dto.BatchCountDto
import com.wmserp.app.data.remote.dto.BatchItemFactsDto
import com.wmserp.app.data.remote.dto.BatchLookupDto
import com.wmserp.app.data.remote.dto.CreatedBatchDto
import com.wmserp.app.data.remote.dto.FoundBatchDto
import com.wmserp.app.data.remote.dto.MissingFieldsDto
import com.wmserp.app.data.remote.dto.PurchaseReceiptDto
import com.wmserp.app.data.remote.dto.PurchaseReceiptItemDto
import com.wmserp.app.data.remote.dto.ReceiptDifferenceDto
import com.wmserp.app.data.remote.dto.SalesOrderDto
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.model.Batch
import com.wmserp.app.domain.model.BatchCount
import com.wmserp.app.domain.model.BatchItemFacts
import com.wmserp.app.domain.model.BatchLookup
import com.wmserp.app.domain.model.CreatedBatch
import com.wmserp.app.domain.model.PurchaseReceipt
import com.wmserp.app.domain.model.PurchaseReceiptItem
import com.wmserp.app.domain.model.ReceiptDifference
import com.wmserp.app.domain.model.ReceiveResult
import com.wmserp.app.domain.model.RequiredField
import com.wmserp.app.domain.model.SalesOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

fun PurchaseReceiptDto.toDomain(): PurchaseReceipt = PurchaseReceipt(
    name = name,
    supplier = supplier.orEmpty(),
    supplierName = supplierName ?: supplier.orEmpty(),
    postingDate = postingDate,
    company = company,
    setWarehouse = setWarehouse,
    status = status,
    workflowState = workflowState,
    docStatus = docstatus,
    supplierDeliveryNote = supplierDeliveryNote,
    itemCount = if (items.isNotEmpty()) items.size else itemCount,
    totalQty = if (items.isNotEmpty()) items.sumOf { it.qty } else totalQty,
    canReceive = canReceive,
    hasWorkflow = hasWorkflow,
    batchQcRequired = batchQcRequired,
    items = items.map { it.toDomain() },
)

fun PurchaseReceiptItemDto.toDomain(): PurchaseReceiptItem = PurchaseReceiptItem(
    rowName = name,
    idx = idx,
    itemCode = itemCode,
    itemName = itemName ?: itemCode,
    qty = qty,
    countedQty = countedQty,
    receivedQty = receivedQty,
    uom = uom,
    stockUom = stockUom,
    conversionFactor = conversionFactor.takeIf { it > 0.0 } ?: 1.0,
    warehouse = warehouse,
    batchNo = batchNo,
    batchLabel = batchLabel?.takeIf { it.isNotBlank() } ?: batchNo,
    countedBatches = countedBatches.map { it.toDomain() },
    hasBatchNo = hasBatchNo,
    hasSerialNo = hasSerialNo,
    isStockItem = isStockItem,
    isFixedAsset = isFixedAsset,
    hasExpiryDate = hasExpiryDate,
    shelfLifeDays = shelfLifeInDays,
    createNewBatch = createNewBatch,
    needsBatch = needsBatch,
    purchaseOrder = purchaseOrder,
    barcodes = barcodes,
)

fun BatchCountDto.toDomain(): BatchCount = BatchCount(batchNo = batchNo, qty = qty, batchLabel = batchLabel?.takeIf { it.isNotBlank() })

fun FoundBatchDto.toDomain(): Batch = Batch(
    name = name,
    itemCode = item.orEmpty(),
    itemName = itemName,
    expiryDate = expiryDate,
    manufacturingDate = manufacturingDate,
    disabled = disabled,
    supplier = supplier,
    batchNo = batchNo?.takeIf { it.isNotBlank() },
    qcNo = qcNo?.takeIf { it.isNotBlank() },
)

fun BatchItemFactsDto.toDomain(): BatchItemFacts = BatchItemFacts(
    itemCode = itemCode,
    itemName = itemName,
    hasBatchNo = hasBatchNo,
    hasExpiryDate = hasExpiryDate,
    shelfLifeDays = shelfLifeInDays,
    createNewBatch = createNewBatch,
)

fun BatchLookupDto.toDomain(): BatchLookup = BatchLookup(code = code, batches = batches.map { it.toDomain() }, item = item?.toDomain())

fun CreatedBatchDto.toDomain(): CreatedBatch = CreatedBatch(batch = batch.toDomain(), created = created)

fun ReceiptDifferenceDto.toDomain(): ReceiptDifference = ReceiptDifference(rowName = row, itemCode = itemCode.orEmpty(), expected = expected, counted = counted)

fun PurchaseReceiptDto.toReceiveResult(): ReceiveResult = ReceiveResult(
    receipt = toDomain(),
    submitted = submitted || docstatus == 1,
    differences = differences.map { it.toDomain() },
    removedRows = removedRows,
)

fun SalesOrderDto.toDomain(): SalesOrder = SalesOrder(
    name = name,
    customer = customer.orEmpty(),
    customerName = customerName ?: customer.orEmpty(),
    status = status.orEmpty(),
    transactionDate = transactionDate.orEmpty(),
    deliveryDate = deliveryDate,
    perDelivered = perDelivered,
    company = company,
)

fun MissingFieldsDto.toRequiredFields(): List<RequiredField> = missingFields.map { field ->
    RequiredField(
        doctype = field.doctype,
        fieldname = field.fieldname,
        label = field.label?.takeIf { it.isNotBlank() } ?: field.fieldname,
        fieldtype = field.fieldtype ?: "Data",
        options = field.options?.takeIf { it.isNotBlank() },
    )
}

/**
 * The backend answers HTTP 200 with `missing_fields` when a site requires values the app must ask
 * for (see `wmserp_picking.documents`). Returns the matching [AppError] or null when the message
 * is an ordinary result.
 */
fun JsonElement?.missingRequiredFieldsOrNull(json: Json): AppError.MissingRequiredFields? {
    val obj = this as? JsonObject ?: return null
    val missing = obj["missing_fields"] as? JsonArray ?: return null
    if (missing.isEmpty()) return null
    val fields = json.decodeFromJsonElement(MissingFieldsDto.serializer(), obj).toRequiredFields()
    return if (fields.isEmpty()) null else AppError.MissingRequiredFields(fields)
}
