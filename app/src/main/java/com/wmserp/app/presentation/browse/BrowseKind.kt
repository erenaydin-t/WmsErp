package com.wmserp.app.presentation.browse

import androidx.annotation.StringRes
import com.wmserp.app.R
import com.wmserp.app.domain.model.DocumentFilter
import com.wmserp.app.domain.model.OrderDoctype

/**
 * What a dashboard / inventory card opens: a filtered list that matches the card's number.
 * [doctypes] is empty for the item list; [period] cards take the start date from the route.
 */
enum class BrowseKind(
    val doctypes: List<OrderDoctype>,
    val open: Boolean,
    val period: Boolean,
    @StringRes val titleRes: Int,
) {
    /** Dashboard *Total Items*: every active item. */
    ITEMS(emptyList(), open = false, period = false, titleRes = R.string.browse_title_items),

    /** Dashboard *Pending Orders*: submitted Sales and Purchase Orders still waiting for stock to move. */
    PENDING_ORDERS(listOf(OrderDoctype.SALES_ORDER, OrderDoctype.PURCHASE_ORDER), open = true, period = false, titleRes = R.string.browse_title_pending_orders),

    /** Inventory *Pending Deliveries*: open Sales Orders. */
    PENDING_DELIVERIES(listOf(OrderDoctype.SALES_ORDER), open = true, period = false, titleRes = R.string.browse_title_pending_deliveries),

    /** *Receipts*: Purchase Receipts submitted since the card's date. */
    RECEIPTS(listOf(OrderDoctype.PURCHASE_RECEIPT), open = false, period = true, titleRes = R.string.browse_title_receipts),

    /** Dashboard *Dispatched*: Delivery Notes submitted since the card's date. */
    DISPATCHED(listOf(OrderDoctype.DELIVERY_NOTE), open = false, period = true, titleRes = R.string.browse_title_dispatched),

    /** Inventory *Picklists*: open Pick Lists of everyone. */
    PICK_LISTS(listOf(OrderDoctype.PICK_LIST), open = true, period = false, titleRes = R.string.browse_title_pick_lists);

    val isItemList: Boolean get() = doctypes.isEmpty()

    fun filter(since: String?): DocumentFilter = DocumentFilter(open = open, submittedSince = if (period) since else null)

    companion object {
        fun fromName(name: String?): BrowseKind? = name?.let { n -> entries.firstOrNull { it.name == n } }
    }
}

/** Sub-filter of [BrowseKind.PENDING_ORDERS]. */
enum class OrderSegment(val doctype: OrderDoctype?, @StringRes val labelRes: Int) {
    ALL(null, R.string.browse_segment_all),
    SALES(OrderDoctype.SALES_ORDER, R.string.browse_segment_sales),
    PURCHASE(OrderDoctype.PURCHASE_ORDER, R.string.browse_segment_purchase),
}
