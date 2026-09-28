package com.wmserp.app.domain.model

/** WMS picking state kept in `Pick List.custom_picking_status` (independent of the standard status). */
enum class PickingStatus(val serverValue: String) {
    READY_TO_PICK("Ready to Pick"),
    PICKING("Picking"),
    PICKED("Picked");

    companion object {
        fun fromServer(value: String?): PickingStatus =
            entries.firstOrNull { it.serverValue.equals(value?.trim(), ignoreCase = true) } ?: READY_TO_PICK
    }
}

/** Standard `Pick List.purpose` (plus the "Material Issue" option added by the wmserp_picking app). */
enum class PickListPurpose(val serverValue: String) {
    DELIVERY("Delivery"),
    MATERIAL_TRANSFER("Material Transfer"),
    MATERIAL_ISSUE("Material Issue"),
    MATERIAL_TRANSFER_FOR_MANUFACTURE("Material Transfer for Manufacture"),
    OTHER("");

    /** Draft document that `generate_document` creates for this purpose, or null when unsupported. */
    val targetDocument: PickTargetDocument?
        get() = when (this) {
            DELIVERY -> PickTargetDocument.DELIVERY_NOTE
            MATERIAL_TRANSFER, MATERIAL_TRANSFER_FOR_MANUFACTURE -> PickTargetDocument.STOCK_ENTRY_MATERIAL_TRANSFER
            MATERIAL_ISSUE -> PickTargetDocument.STOCK_ENTRY_MATERIAL_ISSUE
            OTHER -> null
        }

    companion object {
        fun fromServer(value: String?): PickListPurpose =
            entries.firstOrNull { it != OTHER && it.serverValue.equals(value?.trim(), ignoreCase = true) } ?: OTHER
    }
}

enum class PickTargetDocument { DELIVERY_NOTE, STOCK_ENTRY_MATERIAL_TRANSFER, STOCK_ENTRY_MATERIAL_ISSUE }

enum class PickRowStatus { NOT_PICKED, PARTIAL, PICKED }

const val PICK_QTY_TOLERANCE = 1e-6

fun pickRowStatus(pickedQty: Double, requiredQty: Double): PickRowStatus = when {
    pickedQty <= PICK_QTY_TOLERANCE -> PickRowStatus.NOT_PICKED
    pickedQty + PICK_QTY_TOLERANCE >= requiredQty -> PickRowStatus.PICKED
    else -> PickRowStatus.PARTIAL
}

/** Mandatory rows must be picked exactly; optional rows may be short but never over. */
fun isPickComplete(pickedQty: Double, requiredQty: Double, optional: Boolean): Boolean = when {
    requiredQty <= PICK_QTY_TOLERANCE -> true
    optional -> pickedQty <= requiredQty + PICK_QTY_TOLERANCE
    else -> kotlin.math.abs(pickedQty - requiredQty) <= PICK_QTY_TOLERANCE
}

/** Draft document generated from a picked list (Delivery Note or Stock Entry). */
data class GeneratedDocument(
    val doctype: String,
    val name: String,
    val alreadyGenerated: Boolean = false,
    val docStatus: Int = 0,
)

/** One `Pick List Item` row. Quantities are in the stock UOM, as returned by the backend. */
data class PickListItem(
    val rowName: String,
    val idx: Int,
    val itemCode: String,
    val itemName: String,
    val sourceWarehouse: String?,
    val targetWarehouse: String?,
    val batchNo: String?,
    val expiryDate: String?,
    val serialNo: String?,
    val requiredQty: Double,
    val pickedQty: Double,
    val uom: String?,
    val orderQty: Double = requiredQty,
    val orderUom: String? = uom,
    val conversionFactor: Double = 1.0,
    val hasBatchNo: Boolean = false,
    val hasSerialNo: Boolean = false,
    val optional: Boolean = false,
    val barcodes: List<String> = emptyList(),
    val salesOrder: String? = null,
    val materialRequest: String? = null,
) {
    val status: PickRowStatus get() = pickRowStatus(pickedQty, requiredQty)
    val isComplete: Boolean get() = isPickComplete(pickedQty, requiredQty, optional)
}

/** ERPNext `Pick List` with the WMS picking fields. */
data class PickList(
    val name: String,
    val purpose: PickListPurpose,
    val purposeLabel: String,
    val company: String? = null,
    val customer: String? = null,
    val customerName: String? = null,
    val parentWarehouse: String? = null,
    val targetWarehouse: String? = null,
    val status: String? = null,
    val pickingStatus: PickingStatus = PickingStatus.READY_TO_PICK,
    val picker: String? = null,
    val assignedTo: List<String> = emptyList(),
    val pickingStartedAt: String? = null,
    val pickingStartedBy: String? = null,
    val pickingCompletedAt: String? = null,
    val pickingCompletedBy: String? = null,
    val generatedDocument: GeneratedDocument? = null,
    val workOrder: String? = null,
    val materialRequest: String? = null,
    val modified: String? = null,
    val itemCount: Int = 0,
    val requiredQty: Double = 0.0,
    val pickedQty: Double = 0.0,
    val items: List<PickListItem> = emptyList(),
) {
    val progress: Float get() = if (requiredQty <= 0.0) 0f else (pickedQty / requiredQty).toFloat().coerceIn(0f, 1f)
    val allRowsComplete: Boolean get() = items.all { it.isComplete }
}

/** Quantity (stock UOM) picked for one row, sent to `save_progress` / `complete_picking`. */
data class PickProgressLine(
    val rowName: String,
    val pickedQty: Double,
    val batchNo: String? = null,
    val serialNo: String? = null,
)

enum class PickScanMatchType { ITEM_CODE, BARCODE, BATCH, NOT_ON_LIST, NONE }

/** Server-side resolution of a scanned code against a pick list (`resolve_scan`). */
data class PickScanMatch(
    val rowName: String?,
    val itemCode: String?,
    val batchNo: String?,
    val matchType: PickScanMatchType,
    val expiryDate: String? = null,
)
