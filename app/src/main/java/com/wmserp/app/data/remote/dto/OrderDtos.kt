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

@Serializable
data class PurchaseReceiptRequest(
    val supplier: String,
    val company: String? = null,
    val items: List<PurchaseReceiptItemRequest>,
)

@Serializable
data class PurchaseReceiptItemRequest(
    @SerialName("item_code") val itemCode: String,
    val qty: Double,
    val warehouse: String,
    @SerialName("purchase_order") val purchaseOrder: String,
    @SerialName("purchase_order_item") val purchaseOrderItem: String,
    val uom: String? = null,
    val rate: Double? = null,
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

@Serializable
data class DeliveryNoteRequest(
    val customer: String,
    val company: String? = null,
    val items: List<DeliveryNoteItemRequest>,
)

@Serializable
data class DeliveryNoteItemRequest(
    @SerialName("item_code") val itemCode: String,
    val qty: Double,
    val warehouse: String,
    @SerialName("against_sales_order") val againstSalesOrder: String,
    @SerialName("so_detail") val soDetail: String,
    val uom: String? = null,
    val rate: Double? = null,
)
