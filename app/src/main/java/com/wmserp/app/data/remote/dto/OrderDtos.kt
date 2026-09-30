package com.wmserp.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** ERPNext `Purchase Order` DocType (subset). */
@Serializable
data class PurchaseOrderDto(
    val name: String,
    val supplier: String? = null,
    @SerialName("supplier_name") val supplierName: String? = null,
    val status: String? = null,
    @SerialName("transaction_date") val transactionDate: String? = null,
    @SerialName("schedule_date") val scheduleDate: String? = null,
    @SerialName("grand_total") val grandTotal: Double = 0.0,
    val currency: String? = null,
    @SerialName("per_received") val perReceived: Double = 0.0,
    @SerialName("set_warehouse") val setWarehouse: String? = null,
    val company: String? = null,
    val docstatus: Int = 0,
    val items: List<PurchaseOrderItemDto> = emptyList(),
)

@Serializable
data class PurchaseOrderItemDto(
    val name: String,
    @SerialName("item_code") val itemCode: String,
    @SerialName("item_name") val itemName: String? = null,
    val qty: Double = 0.0,
    @SerialName("received_qty") val receivedQty: Double = 0.0,
    val uom: String? = null,
    val warehouse: String? = null,
    val rate: Double = 0.0,
    val amount: Double = 0.0,
    @SerialName("schedule_date") val scheduleDate: String? = null,
)

/** ERPNext `Sales Order` DocType (subset). */
@Serializable
data class SalesOrderDto(
    val name: String,
    val customer: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    val status: String? = null,
    @SerialName("transaction_date") val transactionDate: String? = null,
    @SerialName("delivery_date") val deliveryDate: String? = null,
    @SerialName("grand_total") val grandTotal: Double = 0.0,
    val currency: String? = null,
    @SerialName("per_delivered") val perDelivered: Double = 0.0,
    @SerialName("set_warehouse") val setWarehouse: String? = null,
    val company: String? = null,
    val docstatus: Int = 0,
    val items: List<SalesOrderItemDto> = emptyList(),
)

@Serializable
data class SalesOrderItemDto(
    val name: String,
    @SerialName("item_code") val itemCode: String,
    @SerialName("item_name") val itemName: String? = null,
    val qty: Double = 0.0,
    @SerialName("delivered_qty") val deliveredQty: Double = 0.0,
    val uom: String? = null,
    val warehouse: String? = null,
    val rate: Double = 0.0,
    @SerialName("conversion_factor") val conversionFactor: Double = 1.0,
)

/** `Item` tracking flags (`has_batch_no` / `has_serial_no`) used before creating a Delivery Note. */
@Serializable
data class ItemTrackingDto(
    val name: String,
    @SerialName("has_batch_no") val hasBatchNo: Int = 0,
    @SerialName("has_serial_no") val hasSerialNo: Int = 0,
)

/** One row returned by `erpnext.stock.doctype.batch.batch.get_batch_qty(item_code, warehouse)`. */
@Serializable
data class BatchQtyDto(
    @SerialName("batch_no") val batchNo: String? = null,
    val qty: Double = 0.0,
)

/** ERPNext `Batch` DocType (subset). */
@Serializable
data class BatchDto(
    val name: String,
    @SerialName("expiry_date") val expiryDate: String? = null,
    val disabled: Int = 0,
)

/** ERPNext `Purchase Receipt` DocType (subset). */
@Serializable
data class PurchaseReceiptDto(
    val name: String,
    val supplier: String? = null,
    val status: String? = null,
    @SerialName("posting_date") val postingDate: String? = null,
    val docstatus: Int = 0,
)

/** ERPNext `Delivery Note` DocType (subset). */
@Serializable
data class DeliveryNoteDto(
    val name: String,
    val customer: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    val status: String? = null,
    @SerialName("posting_date") val postingDate: String? = null,
    val docstatus: Int = 0,
    @SerialName("grand_total") val grandTotal: Double = 0.0,
    val currency: String? = null,
)

/** Name of any document, for filling Link fields. */
@Serializable
data class LinkNameDto(val name: String)
