package com.wmserp.app.domain.model

/** Card (Pick List) state kept in `Pick List.custom_picking_status`, independent of the standard status. */
enum class PickingStatus(val serverValue: String) {
    READY_TO_PICK("Ready to Pick"),
    PICKING("Picking"),
    PICKED("Picked");

    companion object {
        fun fromServer(value: String?): PickingStatus =
            entries.firstOrNull { it.serverValue.equals(value?.trim(), ignoreCase = true) } ?: READY_TO_PICK
    }
}

/** Row state kept in `Pick List Item.custom_row_status`. */
enum class PickRowStatus(val serverValue: String) {
    NOT_PICKED("Not Picked"),
    PICKING("Picking"),
    PICKED("Picked");

    companion object {
        fun fromServer(value: String?): PickRowStatus =
            entries.firstOrNull { it.serverValue.equals(value?.trim(), ignoreCase = true) } ?: NOT_PICKED
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

const val PICK_QTY_TOLERANCE = 1e-6

/** A row is complete when the picked quantity equals the required quantity (never more). */
fun isPickComplete(pickedQty: Double, requiredQty: Double): Boolean =
    requiredQty <= PICK_QTY_TOLERANCE || kotlin.math.abs(pickedQty - requiredQty) <= PICK_QTY_TOLERANCE

/** One batch picked on a row that takes several batches, in the stock UOM. */
data class PickedBatch(val batchNo: String, val qty: Double, val expiryDate: String? = null)

/** Total picked over the batches. */
val List<PickedBatch>.totalQty: Double get() = sumOf { it.qty }

/** [qty] more of [batchNo] (case ignored): added to its entry, or a new entry at the end. */
fun List<PickedBatch>.plusQty(batchNo: String, qty: Double): List<PickedBatch> {
    val index = indexOfFirst { it.batchNo.equals(batchNo, ignoreCase = true) }
    return if (index >= 0) mapIndexed { i, b -> if (i == index) b.copy(qty = b.qty + qty) else b } else this + PickedBatch(batchNo, qty)
}

/** Stock reservation made when the card was completed (`reservation` of `complete_row`). */
data class StockReservation(val reserved: Boolean, val entries: Int = 0, val error: String? = null)

/** Draft document generated from a picked card (Delivery Note or Stock Entry). */
data class GeneratedDocument(
    val doctype: String,
    val name: String,
    val alreadyGenerated: Boolean = false,
    val docStatus: Int = 0,
)

/**
 * One `Pick List Item` row. Quantities are in the stock UOM, as returned by the backend.
 * [requiredQty] is the target: the row's *Picked Qty (in Stock UOM)* set by the Senior Warehouse User.
 */
data class PickListItem(
    val rowName: String,
    val idx: Int,
    val itemCode: String,
    val itemName: String,
    val sourceWarehouse: String?,
    val targetWarehouse: String?,
    /** Batch allocated by ERPNext; the scanned label must carry exactly this batch. Null on a [multiBatch] row. */
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
    val salesOrder: String? = null,
    val materialRequest: String? = null,
    val picker: String? = null,
    val isMine: Boolean = false,
    val rowStatus: PickRowStatus = PickRowStatus.NOT_PICKED,
    val rowStartedAt: String? = null,
    val rowCompletedAt: String? = null,
    val durationSeconds: Double? = null,
    /** The batch was assigned by a scan in the app (ERPNext left the row without one); a reset clears it. */
    val batchFromWms: Boolean = false,
    /** Batch tracked row the Pick List left without a batch: picked from any number of batches. */
    val multiBatch: Boolean = false,
    /** The batches picked so far on a [multiBatch] row. */
    val pickedBatches: List<PickedBatch> = emptyList(),
    /** Stock quantity ordered on the row (the target may differ when the senior user changed Picked Qty). */
    val stockQty: Double = requiredQty,
    /** Stock reserved in ERPNext for this row. */
    val reservedQty: Double = 0.0,
) {
    val isComplete: Boolean get() = rowStatus == PickRowStatus.PICKED || isPickComplete(pickedQty, requiredQty)
}

/** ERPNext `Pick List` with the WMS row-level picking fields. */
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
    val cardStartedAt: String? = null,
    val cardCompletedAt: String? = null,
    val generatedDocument: GeneratedDocument? = null,
    val workOrder: String? = null,
    val materialRequest: String? = null,
    val modified: String? = null,
    val itemCount: Int = 0,
    val pickedRows: Int = 0,
    val requiredQty: Double = 0.0,
    val pickedQty: Double = 0.0,
    val myRowCount: Int = 0,
    val myPickedRows: Int = 0,
    val myOpenRows: Int = 0,
    val allRowsPicked: Boolean = false,
    val items: List<PickListItem> = emptyList(),
) {
    val progress: Float get() = if (requiredQty <= 0.0) 0f else (pickedQty / requiredQty).toFloat().coerceIn(0f, 1f)
    val myProgress: Float get() = if (myRowCount <= 0) 0f else (myPickedRows.toFloat() / myRowCount).coerceIn(0f, 1f)
    val myItems: List<PickListItem> get() = items.filter { it.isMine }
    val isCardPicked: Boolean get() = pickingStatus == PickingStatus.PICKED || allRowsPicked

    /** Nothing left to do on the device: every row is picked or the document already exists. */
    val isDone: Boolean get() = isCardPicked || generatedDocument != null
}

/** Result of `start_row` / `save_row_progress` / `complete_row`. */
data class RowUpdate(
    val pickList: PickList,
    val row: PickListItem?,
    val rowCompleted: Boolean,
    val cardCompleted: Boolean,
    /** True only for the request that completed the very last row of the card. */
    val isLastPicker: Boolean,
    /** Stock reservation run by the request that completed the card; null when none was due. */
    val reservation: StockReservation? = null,
)

/** JSON keys of the QR labels, configured in ERPNext > WMS Settings. */
data class WmsQrKeys(
    val itemKey: String = DEFAULT_ITEM_KEY,
    val batchKey: String = DEFAULT_BATCH_KEY,
) {
    companion object {
        const val DEFAULT_ITEM_KEY = "item_code"
        const val DEFAULT_BATCH_KEY = "batch_no"
        val DEFAULT = WmsQrKeys()
    }
}

/** Content of a scanned QR label after strict JSON parsing. */
data class QrLabel(val itemCode: String, val batchNo: String?, val raw: String)

/** Today's picking statistics of the signed-in user (`get_picker_kpis`). */
data class PickerKpis(
    val date: String,
    val rowsPicked: Int = 0,
    val qtyPicked: Double = 0.0,
    val pickListsTouched: Int = 0,
    val pickListsCompleted: Int = 0,
    val openRows: Int = 0,
    val openPickLists: Int = 0,
    val totalSeconds: Double = 0.0,
    val avgSecondsPerRow: Double? = null,
    val fastestSeconds: Double? = null,
    val slowestSeconds: Double? = null,
    val rowsPerHour: Double? = null,
)
