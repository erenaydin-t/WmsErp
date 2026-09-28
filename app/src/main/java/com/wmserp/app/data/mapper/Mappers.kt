package com.wmserp.app.data.mapper

import com.wmserp.app.data.remote.dto.BinDto
import com.wmserp.app.data.remote.dto.DeliveryNoteDto
import com.wmserp.app.data.remote.dto.DeliveryNoteItemRequest
import com.wmserp.app.data.remote.dto.DeliveryNoteRequest
import com.wmserp.app.data.remote.dto.ItemDto
import com.wmserp.app.data.remote.dto.PurchaseOrderDto
import com.wmserp.app.data.remote.dto.PurchaseOrderItemDto
import com.wmserp.app.data.remote.dto.PurchaseReceiptDto
import com.wmserp.app.data.remote.dto.PurchaseReceiptItemRequest
import com.wmserp.app.data.remote.dto.PurchaseReceiptRequest
import com.wmserp.app.data.remote.dto.SalesOrderDto
import com.wmserp.app.data.remote.dto.SalesOrderItemDto
import com.wmserp.app.data.remote.dto.StockEntryDto
import com.wmserp.app.data.remote.dto.StockEntryItemDto
import com.wmserp.app.data.remote.dto.StockLedgerEntryDto
import com.wmserp.app.data.remote.dto.UserDto
import com.wmserp.app.data.remote.dto.UserUpdateRequest
import com.wmserp.app.data.remote.dto.WarehouseDto
import com.wmserp.app.domain.model.ActivityEntry
import com.wmserp.app.domain.model.DeliveryNote
import com.wmserp.app.domain.model.DeliveryNoteDraft
import com.wmserp.app.domain.model.Item
import com.wmserp.app.domain.model.ProfileUpdate
import com.wmserp.app.domain.model.PurchaseOrder
import com.wmserp.app.domain.model.PurchaseOrderItem
import com.wmserp.app.domain.model.PurchaseReceipt
import com.wmserp.app.domain.model.PurchaseReceiptDraft
import com.wmserp.app.domain.model.SalesOrder
import com.wmserp.app.domain.model.SalesOrderItem
import com.wmserp.app.domain.model.StockEntry
import com.wmserp.app.domain.model.StockEntryItem
import com.wmserp.app.domain.model.StockEntryType
import com.wmserp.app.domain.model.StockLevel
import com.wmserp.app.domain.model.UserProfile
import com.wmserp.app.domain.model.Warehouse

/** Resolves ERPNext relative file URLs ("/files/x.png") against the server base URL. */
fun String?.toAbsoluteUrl(baseUrl: String?): String? {
    if (this.isNullOrBlank()) return null
    if (startsWith("http://") || startsWith("https://")) return this
    if (baseUrl.isNullOrBlank()) return this
    return baseUrl.trimEnd('/') + "/" + trimStart('/')
}

fun UserDto.toDomain(baseUrl: String?): UserProfile {
    val first = firstName.orEmpty()
    val last = lastName.orEmpty()
    val full = fullName?.takeIf { it.isNotBlank() } ?: listOf(first, last).filter { it.isNotBlank() }.joinToString(" ").ifBlank { name }
    return UserProfile(
        email = email ?: name,
        firstName = first,
        lastName = last,
        fullName = full,
        username = username,
        phone = phone,
        mobileNo = mobileNo,
        location = location,
        imageUrl = userImage.toAbsoluteUrl(baseUrl),
        roleProfile = roleProfileName,
        roles = roles.map { it.role },
        language = language,
    )
}

fun ProfileUpdate.toRequest(): UserUpdateRequest = UserUpdateRequest(
    firstName = firstName,
    lastName = lastName,
    phone = phone,
    mobileNo = mobileNo,
    location = location,
)

fun ItemDto.toDomain(baseUrl: String?): Item = Item(
    code = itemCode ?: name,
    name = itemName ?: itemCode ?: name,
    group = itemGroup,
    stockUom = stockUom,
    description = description?.let { stripHtml(it) },
    barcodes = barcodes.map { it.barcode },
    imageUrl = image.toAbsoluteUrl(baseUrl),
    disabled = disabled == 1,
    isStockItem = isStockItem == 1,
    valuationRate = valuationRate,
    standardRate = standardRate,
    brand = brand,
)

fun BinDto.toDomain(itemName: String? = null, uom: String? = null): StockLevel = StockLevel(
    itemCode = itemCode,
    warehouse = warehouse,
    actualQty = actualQty,
    reservedQty = reservedQty,
    orderedQty = orderedQty,
    projectedQty = projectedQty,
    itemName = itemName,
    stockUom = stockUom ?: uom,
)

fun WarehouseDto.toDomain(): Warehouse = Warehouse(
    name = name,
    warehouseName = warehouseName ?: name,
    company = company,
    isGroup = isGroup == 1,
    parentWarehouse = parentWarehouse,
    disabled = disabled == 1,
    warehouseType = warehouseType,
    city = city,
)

fun PurchaseOrderDto.toDomain(): PurchaseOrder = PurchaseOrder(
    name = name,
    supplier = supplier.orEmpty(),
    supplierName = supplierName ?: supplier.orEmpty(),
    status = status.orEmpty(),
    transactionDate = transactionDate.orEmpty(),
    scheduleDate = scheduleDate,
    grandTotal = grandTotal,
    currency = currency,
    perReceived = perReceived,
    setWarehouse = setWarehouse,
    company = company,
    items = items.map { it.toDomain() },
)

fun PurchaseOrderItemDto.toDomain(): PurchaseOrderItem = PurchaseOrderItem(
    rowName = name,
    itemCode = itemCode,
    itemName = itemName ?: itemCode,
    qty = qty,
    receivedQty = receivedQty,
    uom = uom,
    warehouse = warehouse,
    rate = rate,
    amount = amount,
    scheduleDate = scheduleDate,
)

fun SalesOrderDto.toDomain(): SalesOrder = SalesOrder(
    name = name,
    customer = customer.orEmpty(),
    customerName = customerName ?: customer.orEmpty(),
    status = status.orEmpty(),
    transactionDate = transactionDate.orEmpty(),
    deliveryDate = deliveryDate,
    grandTotal = grandTotal,
    currency = currency,
    perDelivered = perDelivered,
    setWarehouse = setWarehouse,
    company = company,
    items = items.map { it.toDomain() },
)

fun SalesOrderItemDto.toDomain(): SalesOrderItem = SalesOrderItem(
    rowName = name,
    itemCode = itemCode,
    itemName = itemName ?: itemCode,
    qty = qty,
    deliveredQty = deliveredQty,
    uom = uom,
    warehouse = warehouse,
    rate = rate,
)

fun PurchaseReceiptDto.toDomain(): PurchaseReceipt = PurchaseReceipt(
    name = name,
    supplier = supplier.orEmpty(),
    status = status ?: if (docstatus == 1) "Submitted" else "Draft",
    postingDate = postingDate,
    docStatus = docstatus,
)

fun PurchaseReceiptDraft.toRequest(): PurchaseReceiptRequest = PurchaseReceiptRequest(
    supplier = supplier,
    company = company,
    items = lines.map {
        PurchaseReceiptItemRequest(
            itemCode = it.itemCode,
            qty = it.qty,
            warehouse = it.warehouse,
            purchaseOrder = purchaseOrderName,
            purchaseOrderItem = it.purchaseOrderRow,
            uom = it.uom,
            rate = it.rate,
        )
    },
)

fun DeliveryNoteDto.toDomain(): DeliveryNote = DeliveryNote(
    name = name,
    customer = customer.orEmpty(),
    customerName = customerName ?: customer.orEmpty(),
    status = status ?: if (docstatus == 1) "Submitted" else "Draft",
    postingDate = postingDate,
    docStatus = docstatus,
    grandTotal = grandTotal,
    currency = currency,
)

fun DeliveryNoteDraft.toRequest(): DeliveryNoteRequest = DeliveryNoteRequest(
    customer = customer,
    company = company,
    items = lines.map {
        DeliveryNoteItemRequest(
            itemCode = it.itemCode,
            qty = it.qty,
            warehouse = it.warehouse,
            againstSalesOrder = salesOrderName,
            soDetail = it.salesOrderRow,
            uom = it.uom,
            rate = it.rate,
        )
    },
)

fun StockEntry.toDto(): StockEntryDto = StockEntryDto(
    stockEntryType = type.erpName,
    purpose = type.erpName,
    remarks = remarks,
    items = items.map {
        StockEntryItemDto(
            itemCode = it.itemCode,
            qty = it.qty,
            sourceWarehouse = it.sourceWarehouse,
            targetWarehouse = it.targetWarehouse,
            uom = it.uom,
        )
    },
)

fun StockEntryDto.toDomain(): StockEntry = StockEntry(
    name = name,
    type = StockEntryType.fromErpName(stockEntryType) ?: StockEntryType.MATERIAL_TRANSFER,
    items = items.map { StockEntryItem(it.itemCode, it.qty, it.sourceWarehouse, it.targetWarehouse, it.uom) },
    postingDate = postingDate,
    docStatus = docstatus,
    remarks = remarks,
)

fun StockLedgerEntryDto.toDomain(itemName: String?): ActivityEntry = ActivityEntry(
    id = name,
    itemCode = itemCode,
    itemName = itemName,
    warehouse = warehouse,
    qtyChange = actualQty,
    voucherType = voucherType.orEmpty(),
    voucherNo = voucherNo.orEmpty(),
    postingDate = postingDate.orEmpty(),
    postingTime = postingTime.orEmpty(),
)

private val htmlTagRegex = Regex("<[^>]+>")

fun stripHtml(value: String): String = value
    .replace("<br>", "\n", ignoreCase = true)
    .replace("</p>", "\n", ignoreCase = true)
    .replace(htmlTagRegex, "")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .trim()
