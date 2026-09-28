package com.wmserp.app.presentation.common

import androidx.annotation.StringRes
import com.wmserp.app.R
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.ErrorCode
import com.wmserp.app.domain.model.AnalyticsSection
import com.wmserp.app.domain.model.AppLanguage
import com.wmserp.app.domain.model.PickListPurpose
import com.wmserp.app.domain.model.PickRowStatus
import com.wmserp.app.domain.model.PickTargetDocument
import com.wmserp.app.domain.model.PickingStatus
import com.wmserp.app.domain.model.ScanTarget
import com.wmserp.app.domain.model.ScannerMode
import com.wmserp.app.domain.usecase.QrError
import com.wmserp.app.presentation.inventory.AnalyticsTab
import com.wmserp.app.presentation.orders.OrdersTab

/**
 * Text that ViewModels can produce without touching Android resources directly, resolved to a
 * localized string in the UI (see `UiText.asString()`).
 * [Plain] carries text that is already final, e.g. messages coming from the ERPNext server.
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plain(val value: String) : UiText

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
        fun plain(value: String): UiText = Plain(value)
    }
}

/** Maps app-generated error codes to string resources; server-provided text is shown verbatim. */
@StringRes
fun ErrorCode.stringRes(): Int = when (this) {
    ErrorCode.INVALID_URL -> R.string.error_invalid_url
    ErrorCode.USERNAME_REQUIRED -> R.string.error_username_required
    ErrorCode.PASSWORD_REQUIRED -> R.string.error_password_required
    ErrorCode.API_TOKEN_REQUIRED -> R.string.error_api_token_required
    ErrorCode.INVALID_CREDENTIALS -> R.string.error_invalid_credentials
    ErrorCode.INVALID_API_TOKEN -> R.string.error_invalid_api_token
    ErrorCode.NO_SESSION_COOKIE -> R.string.error_no_session_cookie
    ErrorCode.CURRENT_PASSWORD_REQUIRED -> R.string.error_current_password_required
    ErrorCode.PASSWORD_TOO_SHORT -> R.string.error_password_too_short
    ErrorCode.PASSWORDS_DO_NOT_MATCH -> R.string.error_passwords_do_not_match
    ErrorCode.PASSWORD_UNCHANGED -> R.string.error_password_unchanged
    ErrorCode.CURRENT_PASSWORD_INCORRECT -> R.string.error_current_password_incorrect
    ErrorCode.FIRST_NAME_REQUIRED -> R.string.error_first_name_required
    ErrorCode.ITEM_REQUIRED -> R.string.error_item_required
    ErrorCode.QTY_MUST_BE_POSITIVE -> R.string.error_qty_positive
    ErrorCode.WAREHOUSES_REQUIRED -> R.string.error_warehouses_required
    ErrorCode.WAREHOUSES_MUST_DIFFER -> R.string.error_warehouses_differ
    ErrorCode.TARGET_WAREHOUSE_REQUIRED -> R.string.error_target_warehouse_required
    ErrorCode.SOURCE_WAREHOUSE_REQUIRED -> R.string.error_source_warehouse_required
    ErrorCode.UNKNOWN_ORDER_ROW -> R.string.error_unknown_order_row
    ErrorCode.OVER_RECEIVE -> R.string.error_over_receive
    ErrorCode.OVER_DISPATCH -> R.string.error_over_dispatch
    ErrorCode.SELECT_WAREHOUSE_FOR_ITEM -> R.string.error_select_warehouse
    ErrorCode.NOTHING_TO_RECEIVE -> R.string.error_nothing_to_receive
    ErrorCode.NOTHING_TO_DISPATCH -> R.string.error_nothing_to_dispatch
    ErrorCode.ORDER_ROW_UNAVAILABLE -> R.string.error_order_row_unavailable
    ErrorCode.SERIAL_ITEM_UNSUPPORTED -> R.string.error_serial_item_unsupported
    ErrorCode.INSUFFICIENT_BATCH_STOCK -> R.string.error_insufficient_batch_stock
    ErrorCode.EMPTY_BARCODE -> R.string.error_empty_barcode
    ErrorCode.PURCHASE_ORDER_NOT_FOUND -> R.string.error_po_not_found
    ErrorCode.SALES_ORDER_NOT_FOUND -> R.string.error_so_not_found
    ErrorCode.NO_COMPANY_CONFIGURED -> R.string.error_no_company
    ErrorCode.NETWORK_UNREACHABLE -> R.string.error_network_unreachable
    ErrorCode.NETWORK_DNS -> R.string.error_network_dns
    ErrorCode.NETWORK_TIMEOUT -> R.string.error_network_timeout
    ErrorCode.NETWORK_TLS -> R.string.error_network_tls
    ErrorCode.NO_SERVER_CONFIGURED -> R.string.error_no_server_configured
    ErrorCode.UNAUTHORIZED -> R.string.error_unauthorized
    ErrorCode.FORBIDDEN -> R.string.error_forbidden
    ErrorCode.NOT_FOUND -> R.string.error_not_found
    ErrorCode.BAD_REQUEST -> R.string.error_bad_request
    ErrorCode.SERVER_ERROR -> R.string.error_server_error
    ErrorCode.SERVER_UNAVAILABLE -> R.string.error_server_unavailable
    ErrorCode.INVALID_RESPONSE -> R.string.error_invalid_response
    ErrorCode.OVER_PICK -> R.string.error_over_pick
    ErrorCode.PICKING_NOT_COMPLETED -> R.string.error_picking_not_completed
    ErrorCode.ROW_INCOMPLETE -> R.string.error_row_incomplete
    ErrorCode.ROW_ALREADY_PICKED -> R.string.error_row_already_picked
    ErrorCode.ROW_NOT_ASSIGNED -> R.string.error_row_not_assigned
    ErrorCode.PICK_LIST_NOT_FOUND -> R.string.error_pick_list_not_found
    ErrorCode.UNSUPPORTED_PICK_PURPOSE -> R.string.error_unsupported_pick_purpose
    ErrorCode.UNKNOWN -> R.string.error_unknown
}

@StringRes
fun PickingStatus.labelRes(): Int = when (this) {
    PickingStatus.READY_TO_PICK -> R.string.pick_status_ready
    PickingStatus.PICKING -> R.string.pick_status_picking
    PickingStatus.PICKED -> R.string.pick_status_picked
}

@StringRes
fun PickListPurpose.labelRes(): Int = when (this) {
    PickListPurpose.DELIVERY -> R.string.pick_purpose_delivery
    PickListPurpose.MATERIAL_TRANSFER -> R.string.pick_purpose_material_transfer
    PickListPurpose.MATERIAL_ISSUE -> R.string.pick_purpose_material_issue
    PickListPurpose.MATERIAL_TRANSFER_FOR_MANUFACTURE -> R.string.pick_purpose_manufacture
    PickListPurpose.OTHER -> R.string.pick_purpose_other
}

@StringRes
fun PickRowStatus.labelRes(): Int = when (this) {
    PickRowStatus.NOT_PICKED -> R.string.pick_row_not_picked
    PickRowStatus.PICKING -> R.string.pick_row_picking
    PickRowStatus.PICKED -> R.string.pick_row_picked
}

/** Why a scan was rejected as a QR label (strict JSON mode). */
@StringRes
fun QrError.messageRes(): Int = when (this) {
    QrError.EMPTY -> R.string.qr_error_empty
    QrError.NOT_JSON -> R.string.qr_error_not_json
    QrError.NOT_OBJECT -> R.string.qr_error_not_object
    QrError.MISSING_ITEM -> R.string.qr_error_missing_item
}

/** Label of the "create document" call to action, or null when the purpose has no target document. */
@StringRes
fun PickTargetDocument.createLabelRes(): Int = when (this) {
    PickTargetDocument.DELIVERY_NOTE -> R.string.pick_create_delivery_note
    PickTargetDocument.STOCK_ENTRY_MATERIAL_TRANSFER -> R.string.pick_create_material_transfer
    PickTargetDocument.STOCK_ENTRY_MATERIAL_ISSUE -> R.string.pick_create_material_issue
}

fun AppError.toUiText(): UiText {
    val code = code ?: return UiText.Plain(message)
    return UiText.Res(code.stringRes(), args)
}

@StringRes
fun ScanTarget.labelRes(): Int = when (this) {
    ScanTarget.ITEM -> R.string.scan_target_item
    ScanTarget.WAREHOUSE -> R.string.scan_target_warehouse
    ScanTarget.PURCHASE_ORDER -> R.string.scan_target_purchase_order
}

@StringRes
fun ScanTarget.hintRes(): Int = when (this) {
    ScanTarget.ITEM -> R.string.scan_hint_item
    ScanTarget.WAREHOUSE -> R.string.scan_hint_warehouse
    ScanTarget.PURCHASE_ORDER -> R.string.scan_hint_purchase_order
}

@StringRes
fun AnalyticsTab.titleRes(): Int = when (this) {
    AnalyticsTab.DELIVERY_DELAYS -> R.string.tab_delivery_delays
    AnalyticsTab.ACTIVITY_HEATMAP -> R.string.tab_activity_heatmap
    AnalyticsTab.STOCK_AGING -> R.string.tab_stock_aging
}

@StringRes
fun AnalyticsSection.titleRes(): Int = when (this) {
    AnalyticsSection.KPIS -> R.string.analytics_section_kpis
    AnalyticsSection.DELIVERY_DELAYS -> R.string.analytics_section_delays
    AnalyticsSection.ACTIVITY_HEATMAP -> R.string.analytics_section_heatmap
    AnalyticsSection.STOCK_AGING -> R.string.analytics_section_aging
}

@StringRes
fun OrdersTab.titleRes(): Int = when (this) {
    OrdersTab.RECEIVE -> R.string.tab_receive
    OrdersTab.DISPATCH -> R.string.tab_dispatch
    OrdersTab.PICK -> R.string.tab_pick
}

@StringRes
fun ScannerMode.labelRes(): Int = when (this) {
    ScannerMode.AUTO -> R.string.scanner_mode_auto
    ScannerMode.HARDWARE -> R.string.scanner_mode_hardware
    ScannerMode.CAMERA -> R.string.scanner_mode_camera
}

@StringRes
fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.SYSTEM -> R.string.language_system
    AppLanguage.ENGLISH -> R.string.language_english
    AppLanguage.PERSIAN -> R.string.language_persian
}
