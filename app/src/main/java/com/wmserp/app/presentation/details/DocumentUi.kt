package com.wmserp.app.presentation.details

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.wmserp.app.R
import com.wmserp.app.domain.model.OrderDoctype
import com.wmserp.app.domain.model.OrderDocument
import com.wmserp.app.presentation.theme.WmsTheme

@StringRes
fun OrderDoctype.labelRes(): Int = when (this) {
    OrderDoctype.SALES_ORDER -> R.string.doctype_sales_order
    OrderDoctype.PURCHASE_ORDER -> R.string.doctype_purchase_order
    OrderDoctype.PURCHASE_RECEIPT -> R.string.doctype_purchase_receipt
    OrderDoctype.DELIVERY_NOTE -> R.string.doctype_delivery_note
    OrderDoctype.PICK_LIST -> R.string.doctype_pick_list
}

/** The stage shown on a document chip: ERPNext's status, or the docstatus when there is none. */
@Composable
fun OrderDocument.stageText(): String = status?.takeIf { it.isNotBlank() } ?: when (docStatus) {
    1 -> androidx.compose.ui.res.stringResource(R.string.receive_submitted)
    2 -> androidx.compose.ui.res.stringResource(R.string.doc_status_cancelled)
    else -> androidx.compose.ui.res.stringResource(R.string.doc_status_draft)
}

/** Green for finished, red for cancelled / closed, amber for still open, blue for drafts. */
@Composable
fun OrderDocument.stageColor(): Color {
    val colors = WmsTheme.colors
    val s = status.orEmpty().lowercase()
    return when {
        docStatus == 2 || s == "cancelled" || s == "closed" || s == "stopped" -> colors.danger
        s == "completed" || s == "picked" || s == "delivered" || s == "received" -> colors.success
        docStatus == 0 || s == "draft" || s == "open" -> colors.info
        s.startsWith("to ") || s == "picking" || s == "partly delivered" -> colors.warning
        docStatus == 1 -> colors.success
        else -> colors.info
    }
}
