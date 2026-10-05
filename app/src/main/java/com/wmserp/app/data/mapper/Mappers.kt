package com.wmserp.app.data.mapper

import com.wmserp.app.data.remote.dto.BatchDto
import com.wmserp.app.data.remote.dto.BatchWarehouseQtyDto
import com.wmserp.app.data.remote.dto.BinDto
import com.wmserp.app.data.remote.dto.ItemDto
import com.wmserp.app.data.remote.dto.StockEntryDto
import com.wmserp.app.data.remote.dto.StockEntryItemDto
import com.wmserp.app.data.remote.dto.StockLedgerEntryDto
import com.wmserp.app.data.remote.dto.UserDto
import com.wmserp.app.data.remote.dto.WarehouseDto
import com.wmserp.app.domain.model.ActivityEntry
import com.wmserp.app.domain.model.Batch
import com.wmserp.app.domain.model.BatchWarehouseStock
import com.wmserp.app.domain.model.Item
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
    brand = brand,
    hasBatchNo = hasBatchNo == 1,
    hasSerialNo = hasSerialNo == 1,
)

fun BatchDto.toDomain(): Batch = Batch(
    name = name,
    itemCode = item.orEmpty(),
    itemName = itemName,
    expiryDate = expiryDate,
    manufacturingDate = manufacturingDate,
    disabled = disabled == 1,
    stockUom = stockUom,
    supplier = supplier,
    description = description?.let { stripHtml(it) },
)

fun BatchWarehouseQtyDto.toDomain(): BatchWarehouseStock? = warehouse?.takeIf { it.isNotBlank() }?.let { BatchWarehouseStock(it, qty) }

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
