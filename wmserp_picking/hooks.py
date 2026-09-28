app_name = "wmserp_picking"
app_title = "WMS ERP Picking"
app_publisher = "WMS ERP"
app_description = "Physical picking workflow (Ready to Pick -> Picking -> Picked) for ERPNext Pick Lists, used by the WMS ERP Android app"
app_email = "wms@example.com"
app_license = "MIT"

required_apps = ["erpnext"]

# Custom Fields / Property Setters are created on install and re-applied on every migrate
# (idempotent), so nothing in the frappe or erpnext code base is ever modified.
after_install = "wmserp_picking.install.after_install"
after_migrate = "wmserp_picking.install.after_migrate"

doc_events = {
    "Pick List": {
        "validate": "wmserp_picking.picking.pick_list_events.validate",
        "on_submit": "wmserp_picking.picking.pick_list_events.on_submit",
        "on_update_after_submit": "wmserp_picking.picking.pick_list_events.on_update_after_submit",
    }
}
