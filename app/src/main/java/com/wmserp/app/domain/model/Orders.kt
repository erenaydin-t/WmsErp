package com.wmserp.app.domain.model

/** Maps to the ERPNext `Purchase Order` DocType. */
data class PurchaseOrder(
    val name: String,
    val supplier: String,
    val supplierName: String,
    val status: String,
    val transactionDate: String,
    val scheduleDate: String? = null,
    val grandTotal: Double = 0.0,
    val currency: String? = null,
    val perReceived: Double = 0.0,
    val setWarehouse: String? = null,
    val company: String? = null,
    val items: List<PurchaseOrderItem> = emptyList(),
) {
    val isFullyReceived: Boolean get() = perReceived >= 100.0
}

data class PurchaseOrderItem(
    val rowName: String,
    val itemCode: String,
    val itemName: String,
    val qty: Double,
    val receivedQty: Double,
    val uom: String? = null,
    val warehouse: String? = null,
    val rate: Double = 0.0,
    val amount: Double = 0.0,
    val scheduleDate: String? = null,
) {
    val pendingQty: Double get() = (qty - receivedQty).coerceAtLeast(0.0)
}

/** Maps to the ERPNext `Sales Order` DocType. */
data class SalesOrder(
    val name: String,
    val customer: String,
    val customerName: String,
    val status: String,
    val transactionDate: String,
    val deliveryDate: String? = null,
    val grandTotal: Double = 0.0,
    val currency: String? = null,
    val perDelivered: Double = 0.0,
    val setWarehouse: String? = null,
    val company: String? = null,
    val items: List<SalesOrderItem> = emptyList(),
)

data class SalesOrderItem(
    val rowName: String,
    val itemCode: String,
    val itemName: String,
    val qty: Double,
    val deliveredQty: Double,
    val uom: String? = null,
    val warehouse: String? = null,
    val rate: Double = 0.0,
    /** Stock units per [uom] (ERPNext `conversion_factor`); batches are allocated in stock units. */
    val conversionFactor: Double = 1.0,
) {
    val pendingQty: Double get() = (qty - deliveredQty).coerceAtLeast(0.0)
}

/** Tracking flags of an ERPNext `Item` (`has_batch_no` / `has_serial_no`). */
data class ItemTracking(
    val itemCode: String,
    val hasBatchNo: Boolean,
    val hasSerialNo: Boolean,
)

/** Stock of one batch in one warehouse, in the item's stock UOM. */
data class BatchStock(
    val batchNo: String,
    val qty: Double,
    /** ISO date (`yyyy-MM-dd`) or null when the batch does not expire. */
    val expiryDate: String? = null,
)

/** The part of a delivery line taken from one batch, in stock UOM. */
data class BatchAllocation(
    val batchNo: String,
    val qty: Double,
)

/** Maps to the ERPNext `Purchase Receipt` DocType (result of a receive flow). */
data class PurchaseReceipt(
    val name: String,
    val supplier: String,
    val status: String,
    val postingDate: String?,
    val docStatus: Int,
)

data class PurchaseReceiptLine(
    val itemCode: String,
    val qty: Double,
    val warehouse: String,
    val purchaseOrderRow: String,
    val uom: String? = null,
    val rate: Double? = null,
)

data class PurchaseReceiptDraft(
    val purchaseOrderName: String,
    val supplier: String,
    val lines: List<PurchaseReceiptLine>,
    val company: String? = null,
    /** Values for required fields the site added, keyed by [RequiredField.key]. */
    val fieldValues: Map<String, String> = emptyMap(),
)

/** Maps to the ERPNext `Delivery Note` DocType (result of a dispatch flow). */
data class DeliveryNote(
    val name: String,
    val customer: String,
    val customerName: String,
    val status: String,
    val postingDate: String?,
    val docStatus: Int,
    val grandTotal: Double = 0.0,
    val currency: String? = null,
)

data class DeliveryNoteLine(
    val itemCode: String,
    /** Quantity in the sales order row's UOM. */
    val qty: Double,
    val warehouse: String,
    val salesOrderRow: String,
    val uom: String? = null,
    val rate: Double? = null,
    val conversionFactor: Double = 1.0,
    /** Batch split of [qty] (stock UOM) for batch-tracked items; empty for plain items. */
    val batches: List<BatchAllocation> = emptyList(),
) {
    /** [qty] expressed in stock units. */
    val stockQty: Double get() = qty * conversionFactor
}

data class DeliveryNoteDraft(
    val salesOrderName: String,
    val customer: String,
    val lines: List<DeliveryNoteLine>,
    val company: String? = null,
    /** Values for required fields the site added, keyed by [RequiredField.key]. */
    val fieldValues: Map<String, String> = emptyMap(),
)

/**
 * A field ERPNext requires on a document header or child row (from the DocType meta, custom fields
 * included) that the app cannot fill from the order, e.g. a mandatory *Department*.
 */
data class RequiredField(
    val doctype: String,
    val fieldname: String,
    val label: String,
    val fieldtype: String,
    /** Link target DocType, or the newline-separated options of a Select. */
    val options: String? = null,
    val default: String? = null,
) {
    /** Key of stored answers: `Purchase Receipt Item.department`. */
    val key: String get() = "$doctype.$fieldname"
    val isLink: Boolean get() = fieldtype == "Link"
    val selectOptions: List<String>
        get() = if (fieldtype == "Select") options.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
}
