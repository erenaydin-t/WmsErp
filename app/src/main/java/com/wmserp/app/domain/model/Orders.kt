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
) {
    val pendingQty: Double get() = (qty - deliveredQty).coerceAtLeast(0.0)
}

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
    val qty: Double,
    val warehouse: String,
    val salesOrderRow: String,
    val uom: String? = null,
    val rate: Double? = null,
)

data class DeliveryNoteDraft(
    val salesOrderName: String,
    val customer: String,
    val lines: List<DeliveryNoteLine>,
    val company: String? = null,
)
