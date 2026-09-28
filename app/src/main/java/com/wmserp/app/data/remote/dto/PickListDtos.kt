package com.wmserp.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Pick List as serialized by `wmserp_picking.api.pick_list` (header with optional `items`). */
@Serializable
data class PickListDto(
    val name: String,
    val purpose: String? = null,
    val company: String? = null,
    val customer: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("parent_warehouse") val parentWarehouse: String? = null,
    @SerialName("target_warehouse") val targetWarehouse: String? = null,
    val status: String? = null,
    @SerialName("picking_status") val pickingStatus: String? = null,
    val picker: String? = null,
    @SerialName("assigned_to") val assignedTo: List<String> = emptyList(),
    @SerialName("picking_started_at") val pickingStartedAt: String? = null,
    @SerialName("picking_started_by") val pickingStartedBy: String? = null,
    @SerialName("picking_completed_at") val pickingCompletedAt: String? = null,
    @SerialName("picking_completed_by") val pickingCompletedBy: String? = null,
    @SerialName("generated_doctype") val generatedDoctype: String? = null,
    @SerialName("generated_docname") val generatedDocname: String? = null,
    @SerialName("work_order") val workOrder: String? = null,
    @SerialName("material_request") val materialRequest: String? = null,
    val modified: String? = null,
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("required_qty") val requiredQty: Double = 0.0,
    @SerialName("picked_qty") val pickedQty: Double = 0.0,
    val items: List<PickListItemDto> = emptyList(),
)

@Serializable
data class PickListItemDto(
    val name: String,
    val idx: Int = 0,
    @SerialName("item_code") val itemCode: String,
    @SerialName("item_name") val itemName: String? = null,
    val description: String? = null,
    val warehouse: String? = null,
    @SerialName("target_warehouse") val targetWarehouse: String? = null,
    @SerialName("batch_no") val batchNo: String? = null,
    @SerialName("expiry_date") val expiryDate: String? = null,
    @SerialName("serial_no") val serialNo: String? = null,
    @SerialName("required_qty") val requiredQty: Double = 0.0,
    @SerialName("picked_qty") val pickedQty: Double = 0.0,
    val uom: String? = null,
    @SerialName("order_qty") val orderQty: Double = 0.0,
    @SerialName("order_uom") val orderUom: String? = null,
    @SerialName("conversion_factor") val conversionFactor: Double = 1.0,
    @SerialName("has_batch_no") val hasBatchNo: Int = 0,
    @SerialName("has_serial_no") val hasSerialNo: Int = 0,
    val optional: Int = 0,
    val barcodes: List<String> = emptyList(),
    @SerialName("sales_order") val salesOrder: String? = null,
    @SerialName("material_request") val materialRequest: String? = null,
    @SerialName("row_status") val rowStatus: String? = null,
)

/** Response of `generate_document`. */
@Serializable
data class GeneratedDocumentDto(
    val doctype: String,
    val name: String,
    val docstatus: Int = 0,
    @SerialName("already_generated") val alreadyGenerated: Boolean = false,
)

/** Response of `resolve_scan`. */
@Serializable
data class PickScanMatchDto(
    @SerialName("row_name") val rowName: String? = null,
    @SerialName("item_code") val itemCode: String? = null,
    @SerialName("batch_no") val batchNo: String? = null,
    val match: String? = null,
    @SerialName("expiry_date") val expiryDate: String? = null,
)

/** One row of the `items` payload of `save_progress` / `complete_picking`. */
@Serializable
data class PickProgressItemRequest(
    val name: String,
    @SerialName("picked_qty") val pickedQty: Double,
    @SerialName("batch_no") val batchNo: String? = null,
    @SerialName("serial_no") val serialNo: String? = null,
)
