package com.wmserp.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Purchase Receipt as serialized by `wmserp_picking.api.purchase_receipt` (header; `items` on `get_receipt`). */
@Serializable
data class PurchaseReceiptDto(
    val name: String,
    val supplier: String? = null,
    @SerialName("supplier_name") val supplierName: String? = null,
    @SerialName("posting_date") val postingDate: String? = null,
    val company: String? = null,
    @SerialName("set_warehouse") val setWarehouse: String? = null,
    val status: String? = null,
    @SerialName("workflow_state") val workflowState: String? = null,
    val docstatus: Int = 0,
    @SerialName("supplier_delivery_note") val supplierDeliveryNote: String? = null,
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("total_qty") val totalQty: Double = 0.0,
    @SerialName("can_receive") val canReceive: Boolean = true,
    @SerialName("has_workflow") val hasWorkflow: Boolean = false,
    val items: List<PurchaseReceiptItemDto> = emptyList(),
    // Present on the answer of `receive`.
    val created: Boolean? = null,
    val submitted: Boolean = false,
    val differences: List<ReceiptDifferenceDto> = emptyList(),
    @SerialName("removed_rows") val removedRows: List<String> = emptyList(),
)

@Serializable
data class PurchaseReceiptItemDto(
    val name: String,
    val idx: Int = 0,
    @SerialName("item_code") val itemCode: String,
    @SerialName("item_name") val itemName: String? = null,
    val description: String? = null,
    val qty: Double = 0.0,
    /** Counted so far through the app and saved as progress (`custom_wms_counted_qty`); 0 on backends before 0.4.1. */
    @SerialName("counted_qty") val countedQty: Double = 0.0,
    @SerialName("received_qty") val receivedQty: Double = 0.0,
    @SerialName("rejected_qty") val rejectedQty: Double = 0.0,
    val uom: String? = null,
    @SerialName("stock_uom") val stockUom: String? = null,
    @SerialName("conversion_factor") val conversionFactor: Double = 1.0,
    val warehouse: String? = null,
    @SerialName("batch_no") val batchNo: String? = null,
    @SerialName("batch_label") val batchLabel: String? = null,
    @SerialName("counted_batches") val countedBatches: List<BatchCountDto> = emptyList(),
    @SerialName("has_batch_no") val hasBatchNo: Boolean = false,
    @SerialName("has_serial_no") val hasSerialNo: Boolean = false,
    @SerialName("is_stock_item") val isStockItem: Boolean = true,
    @SerialName("is_fixed_asset") val isFixedAsset: Boolean = false,
    @SerialName("has_expiry_date") val hasExpiryDate: Boolean = false,
    @SerialName("shelf_life_in_days") val shelfLifeInDays: Int = 0,
    @SerialName("create_new_batch") val createNewBatch: Boolean = false,
    @SerialName("needs_batch") val needsBatch: Boolean = false,
    @SerialName("purchase_order") val purchaseOrder: String? = null,
    @SerialName("purchase_order_item") val purchaseOrderItem: String? = null,
    val barcodes: List<String> = emptyList(),
)

/** One batch counted under a row (`counted_batches` of `get_receipt`, `batches` sent to `receive`). */
@Serializable
data class BatchCountDto(
    @SerialName("batch_no") val batchNo: String,
    val qty: Double = 0.0,
    @SerialName("batch_label") val batchLabel: String? = null,
)

/** A Batch as `find_batch` / `create_batch` return it: `batch_no` is the supplier's number (the name on sites without one). */
@Serializable
data class FoundBatchDto(
    val name: String,
    @SerialName("batch_no") val batchNo: String? = null,
    val item: String? = null,
    @SerialName("item_name") val itemName: String? = null,
    @SerialName("expiry_date") val expiryDate: String? = null,
    @SerialName("manufacturing_date") val manufacturingDate: String? = null,
    val supplier: String? = null,
    val disabled: Boolean = false,
)

@Serializable
data class BatchItemFactsDto(
    @SerialName("item_code") val itemCode: String,
    @SerialName("item_name") val itemName: String? = null,
    @SerialName("has_batch_no") val hasBatchNo: Boolean = false,
    @SerialName("has_expiry_date") val hasExpiryDate: Boolean = false,
    @SerialName("shelf_life_in_days") val shelfLifeInDays: Int = 0,
    @SerialName("create_new_batch") val createNewBatch: Boolean = false,
)

/** Answer of `find_batch`. */
@Serializable
data class BatchLookupDto(
    val code: String = "",
    val found: Boolean = false,
    val batches: List<FoundBatchDto> = emptyList(),
    val item: BatchItemFactsDto? = null,
)

/** Answer of `create_batch`. */
@Serializable
data class CreatedBatchDto(
    val created: Boolean = true,
    val batch: FoundBatchDto,
    val item: BatchItemFactsDto? = null,
)

@Serializable
data class ReceiptDifferenceDto(
    val row: String,
    @SerialName("item_code") val itemCode: String? = null,
    val expected: Double = 0.0,
    val counted: Double = 0.0,
)

/**
 * The backend's answer when a document cannot be created or saved yet because the site requires
 * fields the app has to ask for: `{"doctype": ..., "missing_fields": [...], "created": false}`.
 */
@Serializable
data class MissingFieldsDto(
    val doctype: String? = null,
    @SerialName("missing_fields") val missingFields: List<MissingFieldDto> = emptyList(),
    val created: Boolean? = null,
)

@Serializable
data class MissingFieldDto(
    val doctype: String,
    val fieldname: String,
    val label: String? = null,
    val fieldtype: String? = null,
    val options: String? = null,
)

/** ERPNext `Sales Order` DocType (subset for the delivery delay analytics). */
@Serializable
data class SalesOrderDto(
    val name: String,
    val customer: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    val status: String? = null,
    @SerialName("transaction_date") val transactionDate: String? = null,
    @SerialName("delivery_date") val deliveryDate: String? = null,
    @SerialName("per_delivered") val perDelivered: Double = 0.0,
    val company: String? = null,
    val docstatus: Int = 0,
)

/** Name of any document, for filling Link fields. */
@Serializable
data class LinkNameDto(val name: String)
