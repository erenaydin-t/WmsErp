package com.wmserp.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** ERPNext `Item` DocType (subset; no prices or valuation, the app shows no monetary data). */
@Serializable
data class ItemDto(
    val name: String,
    @SerialName("item_code") val itemCode: String? = null,
    @SerialName("item_name") val itemName: String? = null,
    @SerialName("item_group") val itemGroup: String? = null,
    @SerialName("stock_uom") val stockUom: String? = null,
    val description: String? = null,
    val image: String? = null,
    val disabled: Int = 0,
    @SerialName("is_stock_item") val isStockItem: Int = 1,
    @SerialName("has_batch_no") val hasBatchNo: Int = 0,
    @SerialName("has_serial_no") val hasSerialNo: Int = 0,
    val brand: String? = null,
    val barcodes: List<ItemBarcodeDto> = emptyList(),
)

/** ERPNext `Item Barcode` child DocType. */
@Serializable
data class ItemBarcodeDto(
    val barcode: String,
    @SerialName("barcode_type") val barcodeType: String? = null,
)

/** ERPNext `Batch` DocType (subset). */
@Serializable
data class BatchDto(
    val name: String,
    @SerialName("batch_id") val batchId: String? = null,
    val item: String? = null,
    @SerialName("item_name") val itemName: String? = null,
    @SerialName("expiry_date") val expiryDate: String? = null,
    @SerialName("manufacturing_date") val manufacturingDate: String? = null,
    val disabled: Int = 0,
    @SerialName("stock_uom") val stockUom: String? = null,
    val supplier: String? = null,
    val description: String? = null,
)

/** One row returned by `erpnext.stock.doctype.batch.batch.get_batch_qty(batch_no)`: stock per warehouse. */
@Serializable
data class BatchWarehouseQtyDto(
    val warehouse: String? = null,
    @SerialName("batch_no") val batchNo: String? = null,
    val qty: Double = 0.0,
)

/** ERPNext `Bin` DocType. */
@Serializable
data class BinDto(
    val name: String? = null,
    @SerialName("item_code") val itemCode: String,
    val warehouse: String,
    @SerialName("actual_qty") val actualQty: Double = 0.0,
    @SerialName("reserved_qty") val reservedQty: Double = 0.0,
    @SerialName("ordered_qty") val orderedQty: Double = 0.0,
    @SerialName("projected_qty") val projectedQty: Double = 0.0,
    @SerialName("stock_uom") val stockUom: String? = null,
)

/** ERPNext `Warehouse` DocType. */
@Serializable
data class WarehouseDto(
    val name: String,
    @SerialName("warehouse_name") val warehouseName: String? = null,
    val company: String? = null,
    @SerialName("is_group") val isGroup: Int = 0,
    @SerialName("parent_warehouse") val parentWarehouse: String? = null,
    val disabled: Int = 0,
    @SerialName("warehouse_type") val warehouseType: String? = null,
    val city: String? = null,
)

/** ERPNext `Stock Entry` DocType, used both for reading and creating. */
@Serializable
data class StockEntryDto(
    val name: String? = null,
    @SerialName("stock_entry_type") val stockEntryType: String,
    val purpose: String? = null,
    @SerialName("posting_date") val postingDate: String? = null,
    val docstatus: Int = 0,
    val remarks: String? = null,
    val company: String? = null,
    val items: List<StockEntryItemDto> = emptyList(),
)

@Serializable
data class StockEntryItemDto(
    @SerialName("item_code") val itemCode: String,
    val qty: Double,
    @SerialName("s_warehouse") val sourceWarehouse: String? = null,
    @SerialName("t_warehouse") val targetWarehouse: String? = null,
    val uom: String? = null,
)

/** ERPNext `Stock Ledger Entry` DocType (subset). */
@Serializable
data class StockLedgerEntryDto(
    val name: String,
    @SerialName("item_code") val itemCode: String,
    val warehouse: String,
    @SerialName("actual_qty") val actualQty: Double = 0.0,
    @SerialName("voucher_type") val voucherType: String? = null,
    @SerialName("voucher_no") val voucherNo: String? = null,
    @SerialName("posting_date") val postingDate: String? = null,
    @SerialName("posting_time") val postingTime: String? = null,
)
