"""Custom Fields, Property Setters and the label Print Format added to standard DocTypes.

Everything here is additive (Custom Field / Property Setter / Print Format records), so no file
in `frappe` or `erpnext` is modified. Re-running is safe: `create_custom_fields(update=True)`
updates existing fields in place, the Property Setter is only created when missing and the
print format is updated to the version shipped with the app.

Apply manually (without installing the app) with:

    bench --site <site> execute wmserp_picking.setup.custom_fields.setup_customizations
"""

import os

import frappe
from frappe.custom.doctype.custom_field.custom_field import create_custom_fields
from frappe.custom.doctype.property_setter.property_setter import make_property_setter

from wmserp_picking.picking import rules

PICKING_STATUS_OPTIONS = "\n".join(rules.PICKING_STATUSES)
ROW_STATUS_OPTIONS = "\n".join(rules.ROW_STATUSES)

PRINT_FORMAT_NAME = "WMS Batch QR Label"

# Fields from the first (card-level) design that the row-level workflow replaced.
OBSOLETE_CUSTOM_FIELDS = [
    "Pick List-custom_picker",
    "Pick List-custom_picking_started_at",
    "Pick List-custom_picking_started_by",
    "Pick List-custom_picking_completed_at",
    "Pick List-custom_picking_completed_by",
    "Pick List Item-custom_optional",
]


def get_custom_fields():
    return {
        "Pick List": [
            dict(
                fieldname="custom_wms_picking_section",
                fieldtype="Section Break",
                label="WMS Picking",
                insert_after="amended_from",
                collapsible=0,
            ),
            dict(
                fieldname="custom_picking_status",
                fieldtype="Select",
                options=PICKING_STATUS_OPTIONS,
                default=rules.STATUS_READY,
                label="Picking Status",
                insert_after="custom_wms_picking_section",
                allow_on_submit=1,
                in_list_view=1,
                in_standard_filter=1,
                description="Physical picking state of the whole card, maintained by the WMS app (independent of Status).",
            ),
            dict(
                fieldname="custom_target_warehouse",
                fieldtype="Link",
                options="Warehouse",
                label="Target Warehouse",
                insert_after="custom_picking_status",
                allow_on_submit=1,
                depends_on="eval:doc.purpose=='Material Transfer'",
                description="Destination of the Material Transfer Stock Entry generated from this Pick List.",
            ),
            dict(
                fieldname="custom_wms_picking_column",
                fieldtype="Column Break",
                insert_after="custom_target_warehouse",
            ),
            dict(
                fieldname="custom_card_started_at",
                fieldtype="Datetime",
                label="Card Started At",
                insert_after="custom_wms_picking_column",
                read_only=1,
                allow_on_submit=1,
                no_copy=1,
                description="When the first row of this card was started.",
            ),
            dict(
                fieldname="custom_card_completed_at",
                fieldtype="Datetime",
                label="Card Completed At",
                insert_after="custom_card_started_at",
                read_only=1,
                allow_on_submit=1,
                no_copy=1,
                description="When the last row of this card was picked.",
            ),
            dict(
                fieldname="custom_generated_doctype",
                fieldtype="Link",
                options="DocType",
                label="Generated Document Type",
                insert_after="custom_card_completed_at",
                read_only=1,
                allow_on_submit=1,
                no_copy=1,
            ),
            dict(
                fieldname="custom_generated_docname",
                fieldtype="Dynamic Link",
                options="custom_generated_doctype",
                label="Generated Document",
                insert_after="custom_generated_doctype",
                read_only=1,
                allow_on_submit=1,
                no_copy=1,
            ),
        ],
        "Stock Reconciliation": [
            dict(
                fieldname="custom_stocktaking_session",
                fieldtype="Link",
                options="Stocktaking Session",
                label="Stocktaking Session",
                insert_after="purpose",
                read_only=1,
                no_copy=1,
                in_standard_filter=1,
                description="Set when the reconciliation was created from a WMS stocktaking session; submitting it completes that session and unfreezes the warehouse.",
            ),
        ],
        "Pick List Item": [
            dict(
                fieldname="custom_picker",
                fieldtype="Link",
                options="User",
                label="Picker",
                insert_after="picked_qty",
                allow_on_submit=1,
                in_list_view=1,
                description="User who physically picks this row. Several pickers can share one Pick List.",
            ),
            dict(
                fieldname="custom_row_status",
                fieldtype="Select",
                options=ROW_STATUS_OPTIONS,
                default=rules.ROW_NOT_PICKED,
                label="Row Status",
                insert_after="custom_picker",
                allow_on_submit=1,
                in_list_view=1,
                no_copy=1,
            ),
            dict(
                fieldname="custom_wms_picked_qty",
                fieldtype="Float",
                label="WMS Picked Qty (Stock UOM)",
                insert_after="custom_row_status",
                read_only=1,
                allow_on_submit=1,
                no_copy=1,
                default="0",
                description="Quantity physically picked through the WMS app. Copied into Picked Qty when the row completes.",
            ),
            dict(
                fieldname="custom_row_started_at",
                fieldtype="Datetime",
                label="Row Started At",
                insert_after="custom_wms_picked_qty",
                read_only=1,
                allow_on_submit=1,
                no_copy=1,
            ),
            dict(
                fieldname="custom_row_completed_at",
                fieldtype="Datetime",
                label="Row Completed At",
                insert_after="custom_row_started_at",
                read_only=1,
                allow_on_submit=1,
                no_copy=1,
            ),
            dict(
                fieldname="custom_picking_duration_seconds",
                fieldtype="Float",
                label="Picking Duration (seconds)",
                insert_after="custom_row_completed_at",
                read_only=1,
                allow_on_submit=1,
                no_copy=1,
                description="Row Completed At minus Row Started At.",
            ),
        ],
    }


def setup_customizations():
    create_custom_fields(get_custom_fields(), ignore_validate=True, update=True)
    remove_obsolete_fields()
    extend_purpose_options()
    ensure_print_format()
    frappe.clear_cache(doctype="Pick List")
    frappe.clear_cache(doctype="Pick List Item")
    frappe.clear_cache(doctype="Stock Reconciliation")


def remove_obsolete_fields():
    """Drops the card-level assignment fields of the first design (the DB columns are kept)."""
    for name in OBSOLETE_CUSTOM_FIELDS:
        if frappe.db.exists("Custom Field", name):
            try:
                frappe.delete_doc("Custom Field", name, ignore_permissions=True, force=True)
            except Exception:
                frappe.log_error(title=f"WMS picking: could not remove {name}", message=frappe.get_traceback())


def extend_purpose_options():
    """Adds "Material Issue" to Pick List > Purpose through a Property Setter (no core change)."""
    field = frappe.get_meta("Pick List").get_field("purpose")
    if not field:
        return
    options = [option for option in (field.options or "").split("\n") if option]
    if rules.PURPOSE_MATERIAL_ISSUE in options:
        return
    make_property_setter(
        "Pick List",
        "purpose",
        "options",
        "\n".join(options + [rules.PURPOSE_MATERIAL_ISSUE]),
        "Text",
        validate_fields_for_doctype=False,
    )


def label_template_html() -> str:
    path = os.path.join(os.path.dirname(os.path.dirname(__file__)), "templates", "print_formats", "wms_batch_qr_label.html")
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def ensure_print_format():
    """Creates/updates the Batch print format that renders the JSON QR label."""
    html = label_template_html()
    if frappe.db.exists("Print Format", PRINT_FORMAT_NAME):
        doc = frappe.get_doc("Print Format", PRINT_FORMAT_NAME)
        if doc.html != html or doc.disabled:
            doc.html = html
            doc.disabled = 0
            doc.save(ignore_permissions=True)
        return
    frappe.get_doc(
        {
            "doctype": "Print Format",
            "name": PRINT_FORMAT_NAME,
            "doc_type": "Batch",
            "module": "WMS ERP Picking",
            "print_format_type": "Jinja",
            "custom_format": 1,
            "standard": "No",
            "html": html,
            "font_size": 10,
            "margin_top": 2,
            "margin_bottom": 2,
            "margin_left": 2,
            "margin_right": 2,
            "disabled": 0,
        }
    ).insert(ignore_permissions=True)
