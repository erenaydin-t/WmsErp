package com.wmserp.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The fields the app reads from a Sales Order, Purchase Order, Purchase Receipt, Delivery Note or
 * Pick List header. Doctypes without a field leave it null. No amounts or rates are requested.
 */
@Serializable
data class DocumentDto(
    val name: String,
    val customer: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    val supplier: String? = null,
    @SerialName("supplier_name") val supplierName: String? = null,
    val status: String? = null,
    val docstatus: Int = 0,
    @SerialName("transaction_date") val transactionDate: String? = null,
    @SerialName("posting_date") val postingDate: String? = null,
    @SerialName("delivery_date") val deliveryDate: String? = null,
    @SerialName("schedule_date") val scheduleDate: String? = null,
    @SerialName("per_delivered") val perDelivered: Double? = null,
    @SerialName("per_received") val perReceived: Double? = null,
    @SerialName("set_warehouse") val setWarehouse: String? = null,
    @SerialName("parent_warehouse") val parentWarehouse: String? = null,
    @SerialName("po_no") val poNo: String? = null,
    @SerialName("supplier_delivery_note") val supplierDeliveryNote: String? = null,
    val purpose: String? = null,
    val company: String? = null,
    @SerialName("total_qty") val totalQty: Double? = null,
    val modified: String? = null,
    val items: List<DocumentLineDto> = emptyList(),
    /** Pick List rows live in `locations`. */
    val locations: List<DocumentLineDto> = emptyList(),
)

/** A child row of any of the doctypes above (`... Item` / `Pick List Item`). */
@Serializable
data class DocumentLineDto(
    val name: String? = null,
    val idx: Int = 0,
    @SerialName("item_code") val itemCode: String,
    @SerialName("item_name") val itemName: String? = null,
    val qty: Double = 0.0,
    val uom: String? = null,
    @SerialName("stock_uom") val stockUom: String? = null,
    val warehouse: String? = null,
    @SerialName("batch_no") val batchNo: String? = null,
    @SerialName("delivered_qty") val deliveredQty: Double? = null,
    @SerialName("received_qty") val receivedQty: Double? = null,
    @SerialName("picked_qty") val pickedQty: Double? = null,
    @SerialName("against_sales_order") val againstSalesOrder: String? = null,
    @SerialName("sales_order") val salesOrder: String? = null,
    @SerialName("purchase_order") val purchaseOrder: String? = null,
)
