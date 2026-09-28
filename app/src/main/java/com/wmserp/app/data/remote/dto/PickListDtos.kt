package com.wmserp.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Pick List as serialized by `wmserp_picking.api.pick_list` (header, with `items` on detail calls). */
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
    @SerialName("card_started_at") val cardStartedAt: String? = null,
    @SerialName("card_completed_at") val cardCompletedAt: String? = null,
    @SerialName("generated_doctype") val generatedDoctype: String? = null,
    @SerialName("generated_docname") val generatedDocname: String? = null,
    @SerialName("work_order") val workOrder: String? = null,
    @SerialName("material_request") val materialRequest: String? = null,
    val modified: String? = null,
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("picked_rows") val pickedRows: Int = 0,
    @SerialName("required_qty") val requiredQty: Double = 0.0,
    @SerialName("picked_qty") val pickedQty: Double = 0.0,
    @SerialName("my_row_count") val myRowCount: Int = 0,
    @SerialName("my_picked_rows") val myPickedRows: Int = 0,
    @SerialName("my_open_rows") val myOpenRows: Int = 0,
    @SerialName("all_rows_picked") val allRowsPicked: Boolean = false,
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
    @SerialName("sales_order") val salesOrder: String? = null,
    @SerialName("material_request") val materialRequest: String? = null,
    val picker: String? = null,
    @SerialName("is_mine") val isMine: Boolean = false,
    @SerialName("row_status") val rowStatus: String? = null,
    @SerialName("row_started_at") val rowStartedAt: String? = null,
    @SerialName("row_completed_at") val rowCompletedAt: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
)

/** Response of `start_row`, `save_row_progress` and `complete_row`. */
@Serializable
data class RowUpdateDto(
    @SerialName("pick_list") val pickList: PickListDto,
    val row: PickListItemDto? = null,
    @SerialName("row_completed") val rowCompleted: Boolean = false,
    @SerialName("card_completed") val cardCompleted: Boolean = false,
    @SerialName("is_last_picker") val isLastPicker: Boolean = false,
)

/** Response of `get_settings`. */
@Serializable
data class WmsSettingsDto(
    @SerialName("qr_item_key") val qrItemKey: String? = null,
    @SerialName("qr_batch_key") val qrBatchKey: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
)

/** Response of `get_picker_kpis`. */
@Serializable
data class PickerKpisDto(
    val date: String? = null,
    val user: String? = null,
    @SerialName("rows_picked") val rowsPicked: Int = 0,
    @SerialName("qty_picked") val qtyPicked: Double = 0.0,
    @SerialName("pick_lists_touched") val pickListsTouched: Int = 0,
    @SerialName("pick_lists_completed") val pickListsCompleted: Int = 0,
    @SerialName("open_rows") val openRows: Int = 0,
    @SerialName("open_pick_lists") val openPickLists: Int = 0,
    @SerialName("total_seconds") val totalSeconds: Double = 0.0,
    @SerialName("avg_seconds_per_row") val avgSecondsPerRow: Double? = null,
    @SerialName("fastest_seconds") val fastestSeconds: Double? = null,
    @SerialName("slowest_seconds") val slowestSeconds: Double? = null,
    @SerialName("rows_per_hour") val rowsPerHour: Double? = null,
)

/** Response of `generate_document`. */
@Serializable
data class GeneratedDocumentDto(
    val doctype: String,
    val name: String,
    val docstatus: Int = 0,
    @SerialName("already_generated") val alreadyGenerated: Boolean = false,
)
