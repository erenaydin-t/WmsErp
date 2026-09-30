app_name = "wmserp_picking"
app_title = "WMS ERP Picking"
app_publisher = "WMS ERP"
app_description = "Row-level physical picking (Ready to Pick -> Picking -> Picked) with JSON QR labels and picker KPIs for ERPNext Pick Lists, plus stocktaking sessions (warehouse freeze, scan-to-count, second counts, manager review, Stock Reconciliation), used by the WMS ERP Android app"
app_email = "wms@example.com"
app_license = "MIT"

required_apps = ["erpnext"]

# Custom Fields / Property Setters / the label Print Format are created on install and re-applied
# on every migrate (idempotent), so nothing in the frappe or erpnext code base is ever modified.
after_install = "wmserp_picking.install.after_install"
after_migrate = "wmserp_picking.install.after_migrate"

doc_events = {
    "Pick List": {
        "validate": "wmserp_picking.picking.pick_list_events.validate",
        "on_submit": "wmserp_picking.picking.pick_list_events.on_submit",
        "on_update_after_submit": "wmserp_picking.picking.pick_list_events.on_update_after_submit",
    },
    # Stocktaking: a warehouse under an active session is frozen. Every stock transaction creates
    # Stock Ledger Entries, so refusing their insertion blocks all of them (only the session's own
    # Stock Reconciliation may post). Submitting that reconciliation completes the session.
    "Stock Ledger Entry": {
        "before_insert": "wmserp_picking.stocktaking.freeze.check_stock_ledger_entry",
    },
    "Stock Reconciliation": {
        "on_submit": "wmserp_picking.stocktaking.freeze.on_reconciliation_submit",
        "on_cancel": "wmserp_picking.stocktaking.freeze.on_reconciliation_cancel",
    },
}

# Jinja helpers for print formats (WMS Batch QR Label) and custom labels.
jinja = {
    "methods": [
        "wmserp_picking.qr.wms_qr_keys",
        "wmserp_picking.qr.wms_qr_payload",
        "wmserp_picking.qr.wms_batch_qr_payload",
        "wmserp_picking.qr.wms_qr_svg",
        "wmserp_picking.qr.wms_batch_qr_svg",
    ]
}
