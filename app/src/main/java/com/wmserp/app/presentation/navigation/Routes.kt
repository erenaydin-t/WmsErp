package com.wmserp.app.presentation.navigation

import android.net.Uri
import com.wmserp.app.domain.model.ScanTarget
import com.wmserp.app.presentation.orders.OrdersTab

object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val DASHBOARD = "dashboard"
    const val INVENTORY = "inventory"
    const val SCAN = "scan"
    const val ORDERS = "orders"
    const val PROFILE = "profile"
    const val RECEIVE_DETAIL = "receive"
    const val PICK_LIST_DETAIL = "picklist"
    const val STOCKTAKING_DETAIL = "stocktaking"

    const val ARG_TARGET = "target"
    const val ARG_TAB = "tab"
    const val ARG_RECEIPT_NAME = "receiptName"
    const val ARG_PICK_LIST_NAME = "pickListName"
    const val ARG_SESSION_NAME = "sessionName"

    const val SCAN_PATTERN = "$SCAN?$ARG_TARGET={$ARG_TARGET}"
    const val ORDERS_PATTERN = "$ORDERS?$ARG_TAB={$ARG_TAB}"
    const val RECEIVE_PATTERN = "$RECEIVE_DETAIL/{$ARG_RECEIPT_NAME}"
    const val PICK_LIST_PATTERN = "$PICK_LIST_DETAIL/{$ARG_PICK_LIST_NAME}"
    const val STOCKTAKING_PATTERN = "$STOCKTAKING_DETAIL/{$ARG_SESSION_NAME}"

    /** Destinations that show the bottom navigation bar. */
    val topLevel: Set<String> = setOf(DASHBOARD, INVENTORY, SCAN, ORDERS, PROFILE)

    fun scan(target: ScanTarget? = null): String = if (target == null) SCAN else "$SCAN?$ARG_TARGET=${target.name}"
    fun orders(tab: OrdersTab? = null): String = if (tab == null) ORDERS else "$ORDERS?$ARG_TAB=${tab.name}"
    /** Receiving screen of one draft Purchase Receipt. */
    fun receive(receiptName: String): String = "$RECEIVE_DETAIL/${Uri.encode(receiptName)}"
    fun pickList(name: String): String = "$PICK_LIST_DETAIL/${Uri.encode(name)}"
    fun stocktaking(name: String): String = "$STOCKTAKING_DETAIL/${Uri.encode(name)}"

    /** Strips query parameters so "scan?target={target}" compares equal to "scan". */
    fun base(route: String?): String? = route?.substringBefore('?')?.substringBefore('/')
}
