package com.wmserp.app.data.mapper

import com.wmserp.app.data.remote.dto.DocumentDto
import com.wmserp.app.data.remote.dto.DocumentLineDto
import com.wmserp.app.domain.model.OrderDoctype
import com.wmserp.app.domain.model.OrderDocument
import com.wmserp.app.domain.model.OrderLine

fun DocumentDto.toDomain(doctype: OrderDoctype): OrderDocument {
    val rows = if (doctype == OrderDoctype.PICK_LIST) locations else items
    val lines = rows.mapIndexed { index, row -> row.toDomain(doctype, index + 1) }
    val party = when (doctype) {
        OrderDoctype.PURCHASE_ORDER, OrderDoctype.PURCHASE_RECEIPT -> supplier
        else -> customer
    }.orEmpty()
    val partyName = when (doctype) {
        OrderDoctype.PURCHASE_ORDER, OrderDoctype.PURCHASE_RECEIPT -> supplierName ?: supplier
        else -> customerName ?: customer
    }.orEmpty()
    return OrderDocument(
        doctype = doctype,
        name = name,
        party = party,
        partyName = partyName,
        status = status,
        docStatus = docstatus,
        date = transactionDate ?: postingDate ?: modified?.take(10),
        dueDate = when (doctype) {
            OrderDoctype.SALES_ORDER -> deliveryDate
            OrderDoctype.PURCHASE_ORDER -> scheduleDate
            else -> null
        },
        percentDone = when (doctype) {
            OrderDoctype.SALES_ORDER -> perDelivered
            OrderDoctype.PURCHASE_ORDER -> perReceived
            else -> null
        },
        warehouse = setWarehouse ?: parentWarehouse,
        reference = when (doctype) {
            OrderDoctype.SALES_ORDER -> poNo
            OrderDoctype.PURCHASE_RECEIPT -> supplierDeliveryNote
            OrderDoctype.PICK_LIST -> purpose
            else -> null
        }?.takeIf { it.isNotBlank() },
        company = company,
        totalQty = totalQty ?: lines.sumOf { it.qty },
        itemCount = lines.size,
        items = lines,
    )
}

fun DocumentLineDto.toDomain(doctype: OrderDoctype, fallbackIdx: Int): OrderLine = OrderLine(
    rowName = name ?: "$itemCode-$fallbackIdx",
    idx = if (idx > 0) idx else fallbackIdx,
    itemCode = itemCode,
    itemName = itemName?.takeIf { it.isNotBlank() } ?: itemCode,
    qty = qty,
    uom = uom ?: stockUom,
    doneQty = when (doctype) {
        OrderDoctype.SALES_ORDER -> deliveredQty
        OrderDoctype.PURCHASE_ORDER -> receivedQty
        OrderDoctype.PICK_LIST -> pickedQty
        else -> null
    },
    warehouse = warehouse,
    batchNo = batchNo?.takeIf { it.isNotBlank() },
    against = (againstSalesOrder ?: salesOrder ?: purchaseOrder)?.takeIf { it.isNotBlank() },
)
