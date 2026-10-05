package com.wmserp.app.domain.model

/**
 * A draft ERPNext `Purchase Receipt` waiting for the warehouse (served by
 * `wmserp_picking.api.purchase_receipt`). Purchasing creates it from the Purchase Order and runs
 * it through the site's approval workflow; the warehouse counts the goods against its rows in the
 * app and confirms, which submits it and puts the stock in.
 */
data class PurchaseReceipt(
    val name: String,
    val supplier: String,
    val supplierName: String,
    val postingDate: String?,
    val company: String? = null,
    val setWarehouse: String? = null,
    val status: String? = null,
    /** State of the site's Purchase Receipt workflow, when one exists. */
    val workflowState: String? = null,
    val docStatus: Int = 0,
    val supplierDeliveryNote: String? = null,
    val itemCount: Int = 0,
    val totalQty: Double = 0.0,
    /** The signed-in user may count and confirm this receipt (draft, at their stage, write permission). */
    val canReceive: Boolean = true,
    val hasWorkflow: Boolean = false,
    val items: List<PurchaseReceiptItem> = emptyList(),
) {
    val isDraft: Boolean get() = docStatus == 0
    val isSubmitted: Boolean get() = docStatus == 1
}

/** One `Purchase Receipt Item` row. Quantities are in the row's UOM. */
data class PurchaseReceiptItem(
    val rowName: String,
    val idx: Int,
    val itemCode: String,
    val itemName: String,
    /** Expected (accepted) quantity on the draft. */
    val qty: Double,
    val receivedQty: Double = qty,
    val uom: String? = null,
    val stockUom: String? = null,
    val conversionFactor: Double = 1.0,
    val warehouse: String? = null,
    val batchNo: String? = null,
    val hasBatchNo: Boolean = false,
    val hasSerialNo: Boolean = false,
    /** Batch tracked item without a batch that ERPNext will not create on submit: one must be scanned or entered. */
    val needsBatch: Boolean = false,
    val purchaseOrder: String? = null,
    /** Barcodes of the item (`Item Barcode`), so a plain barcode scan finds the row offline. */
    val barcodes: List<String> = emptyList(),
) {
    fun matches(code: String): Boolean =
        itemCode.equals(code, ignoreCase = true) || barcodes.any { it.equals(code, ignoreCase = true) }
}

/** What the warehouse counted for one row (row UOM). */
data class ReceiptCount(
    val rowName: String,
    val qty: Double,
    val warehouse: String? = null,
    val batchNo: String? = null,
)

/** A row whose counted quantity differs from the draft (0 means the row was not received and is dropped). */
data class ReceiptDifference(
    val rowName: String,
    val itemCode: String,
    val expected: Double,
    val counted: Double,
)

/** Result of `receive`: the receipt as saved (and possibly submitted). */
data class ReceiveResult(
    val receipt: PurchaseReceipt,
    val submitted: Boolean,
    val differences: List<ReceiptDifference> = emptyList(),
    val removedRows: List<String> = emptyList(),
)

/** Maps to the ERPNext `Sales Order` DocType (used by the delivery delay analytics). */
data class SalesOrder(
    val name: String,
    val customer: String,
    val customerName: String,
    val status: String,
    val transactionDate: String,
    val deliveryDate: String? = null,
    val perDelivered: Double = 0.0,
    val company: String? = null,
)

/**
 * A field ERPNext requires on a document header or child row (from the DocType meta, custom fields
 * included) that cannot be filled from the source document, e.g. a mandatory *Department*. The
 * backend reports them as `missing_fields`; the app asks once and remembers the answers.
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
