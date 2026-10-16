package com.wmserp.app.domain.model

/** The ERPNext transaction documents the app lists and opens read-only from its KPI cards. */
enum class OrderDoctype(val erpName: String) {
    SALES_ORDER("Sales Order"),
    PURCHASE_ORDER("Purchase Order"),
    PURCHASE_RECEIPT("Purchase Receipt"),
    DELIVERY_NOTE("Delivery Note"),
    PICK_LIST("Pick List");

    companion object {
        fun fromErpName(name: String?): OrderDoctype? = entries.firstOrNull { it.erpName == name }
    }
}

/**
 * Which documents of a doctype a list shows. [open] selects the submitted documents still waiting
 * for stock to move (the dashboard's *Pending Orders*, *Pending Deliveries*, open pick lists);
 * [submittedSince] the documents submitted on or after an ISO date (*Receipts* / *Dispatched*).
 */
data class DocumentFilter(
    val open: Boolean = false,
    val submittedSince: String? = null,
)

/**
 * A Sales Order, Purchase Order, Purchase Receipt, Delivery Note or Pick List as the app shows it:
 * party, stage, dates, quantities and rows. No amounts, rates or values (warehouse users see no
 * monetary data).
 */
data class OrderDocument(
    val doctype: OrderDoctype,
    val name: String,
    /** Customer or supplier id; empty for a Pick List without a customer. */
    val party: String = "",
    val partyName: String = "",
    val status: String? = null,
    val docStatus: Int = 0,
    /** Transaction or posting date (ISO). */
    val date: String? = null,
    /** Delivery date (Sales Order) or expected date (Purchase Order), ISO. */
    val dueDate: String? = null,
    /** `per_delivered` / `per_received`, 0..100, when the doctype tracks it. */
    val percentDone: Double? = null,
    val warehouse: String? = null,
    /** The customer's PO number, the supplier's delivery note number, or the pick list purpose. */
    val reference: String? = null,
    val company: String? = null,
    val totalQty: Double = 0.0,
    val itemCount: Int = 0,
    val items: List<OrderLine> = emptyList(),
) {
    val isSubmitted: Boolean get() = docStatus == 1
    val isCancelled: Boolean get() = docStatus == 2
}

/** One row of an [OrderDocument]; quantities in the row UOM. */
data class OrderLine(
    val rowName: String,
    val idx: Int,
    val itemCode: String,
    val itemName: String,
    val qty: Double,
    val uom: String? = null,
    /** Delivered / received / picked so far, when the doctype tracks it. */
    val doneQty: Double? = null,
    val warehouse: String? = null,
    val batchNo: String? = null,
    /** The Sales Order or Purchase Order the row comes from. */
    val against: String? = null,
) {
    val remainingQty: Double get() = doneQty?.let { (qty - it).coerceAtLeast(0.0) } ?: qty
}

/** An item with its stock per warehouse, for the item detail sheet. */
data class ItemDetail(
    val item: Item,
    val stock: List<StockLevel>,
) {
    val totalQty: Double get() = stock.sumOf { it.actualQty }
    val reservedQty: Double get() = stock.sumOf { it.reservedQty }
}
