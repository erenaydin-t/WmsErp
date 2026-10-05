package com.wmserp.app.data.mapper

import com.wmserp.app.data.remote.dto.MissingFieldsDto
import com.wmserp.app.data.remote.dto.PurchaseReceiptDto
import com.wmserp.app.data.remote.dto.PurchaseReceiptItemDto
import com.wmserp.app.data.remote.dto.ReceiptDifferenceDto
import com.wmserp.app.data.remote.dto.SalesOrderDto
import com.wmserp.app.domain.common.AppError
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
    items = items.map { it.toDomain() },
)

fun PurchaseReceiptItemDto.toDomain(): PurchaseReceiptItem = PurchaseReceiptItem(
    rowName = name,
    idx = idx,
    itemCode = itemCode,
    itemName = itemName ?: itemCode,
    qty = qty,
    receivedQty = receivedQty,
    uom = uom,
    stockUom = stockUom,
    conversionFactor = conversionFactor.takeIf { it > 0.0 } ?: 1.0,
    warehouse = warehouse,
    batchNo = batchNo,
    hasBatchNo = hasBatchNo,
    hasSerialNo = hasSerialNo,
    needsBatch = needsBatch,
    purchaseOrder = purchaseOrder,
    barcodes = barcodes,
)

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
