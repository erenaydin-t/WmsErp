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
    /** Counted so far and saved as progress from the app; the count continues from here. */
    val countedQty: Double = 0.0,
    val receivedQty: Double = qty,
    val uom: String? = null,
    val stockUom: String? = null,
    val conversionFactor: Double = 1.0,
    val warehouse: String? = null,
    /** ERPNext name of the batch purchasing put on the row, if any. */
    val batchNo: String? = null,
    /** The supplier's batch number of [batchNo] as printed on the material (the name itself on sites without that field). */
    val batchLabel: String? = batchNo,
    /** Per-batch counts saved as progress from the app; the count continues from here. */
    val countedBatches: List<BatchCount> = emptyList(),
    val hasBatchNo: Boolean = false,
    val hasSerialNo: Boolean = false,
    /** The item holds stock: the row needs a warehouse and ERPNext writes a stock ledger entry. Fixed assets and services do not. */
    val isStockItem: Boolean = true,
    /** Fixed-asset item: counted like any row; the Asset record is the asset accountant's job (or ERPNext's, for auto-created assets). */
    val isFixedAsset: Boolean = false,
    /** The item's batches carry an expiry date (defaulting to manufacturing date + [shelfLifeDays]). */
    val hasExpiryDate: Boolean = false,
    val shelfLifeDays: Int = 0,
    /** ERPNext creates the batch itself on submit; the row may be counted without one. */
    val createNewBatch: Boolean = false,
    /** Batch tracked item without a batch that ERPNext will not create on submit: one must be scanned or entered. */
    val needsBatch: Boolean = false,
    val purchaseOrder: String? = null,
    /** Barcodes of the item (`Item Barcode`), so a plain barcode scan finds the row offline. */
    val barcodes: List<String> = emptyList(),
) {
    fun matches(code: String): Boolean =
        itemCode.equals(code, ignoreCase = true) || barcodes.any { it.equals(code, ignoreCase = true) }
}

/** One batch counted under a batch tracked row (row UOM). [batchNo] is the ERPNext name, [batchLabel] what the label says. */
data class BatchCount(
    val batchNo: String,
    val qty: Double,
    val batchLabel: String? = null,
) {
    val label: String get() = batchLabel?.takeIf { it.isNotBlank() } ?: batchNo
}

/** What the warehouse counted for one row (row UOM); [batches] when a batch tracked row came in one or more batches. */
data class ReceiptCount(
    val rowName: String,
    val qty: Double,
    val warehouse: String? = null,
    val batchNo: String? = null,
    val batches: List<BatchCount> = emptyList(),
)

/** What `find_batch` knows about an item whose batch is being looked up or created. */
data class BatchItemFacts(
    val itemCode: String,
    val itemName: String? = null,
    val hasBatchNo: Boolean = false,
    val hasExpiryDate: Boolean = false,
    val shelfLifeDays: Int = 0,
    val createNewBatch: Boolean = false,
)

/** The batches a scanned or typed batch number stands for (by ERPNext name or by the supplier's number). */
data class BatchLookup(
    val code: String,
    val batches: List<Batch>,
    val item: BatchItemFacts? = null,
) {
    val found: Boolean get() = batches.isNotEmpty()
}

/** A batch to create for a batch number the supplier printed on the material. Dates are ISO `yyyy-MM-dd`. */
data class NewBatch(
    val itemCode: String,
    val batchNo: String,
    val manufacturingDate: String? = null,
    val expiryDate: String? = null,
    val supplier: String? = null,
    /** Draft Purchase Receipt the batch is created for (becomes its reference document). */
    val receipt: String? = null,
    /** Quality-control number of the delivery, on sites that keep one per batch (`qc_no`). */
    val qcNo: String? = null,
)

/** Result of `create_batch`: the batch, new or already existing for that number. */
data class CreatedBatch(val batch: Batch, val created: Boolean)

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
