"""Custom Fields and Property Setters added to the standard Pick List DocType.

Everything here is additive (Custom Field / Property Setter records), so no file in
`frappe` or `erpnext` is modified. Re-running is safe: `create_custom_fields(update=True)`
updates existing fields in place and the Property Setter is only created when missing.

Apply manually (without installing the app) with:

    bench --site <site> execute wmserp_picking.setup.custom_fields.setup_customizations
"""

import frappe
from frappe.custom.doctype.custom_field.custom_field import create_custom_fields
from frappe.custom.doctype.property_setter.property_setter import make_property_setter

from wmserp_picking.picking import rules

PICKING_STATUS_OPTIONS = "\n".join(rules.PICKING_STATUSES)


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
                fieldname="custom_picker",
                fieldtype="Link",
                options="User",
                label="Picker",
                insert_after="custom_wms_picking_section",
                allow_on_submit=1,
                in_standard_filter=1,
                description="User who physically picks this list. Also assigned through the standard Assign To.",
            ),
            dict(
                fieldname="custom_picking_status",
                fieldtype="Select",
                options=PICKING_STATUS_OPTIONS,
                default=rules.STATUS_READY,
                label="Picking Status",
                insert_after="custom_picker",
                allow_on_submit=1,
                in_list_view=1,
                in_standard_filter=1,
                description="Physical picking state maintained by the WMS app, independent of the standard Status.",
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
                fieldname="custom_picking_started_at",
                fieldtype="Datetime",
                label="Picking Started At",
                insert_after="custom_wms_picking_column",
                read_only=1,
                allow_on_submit=1,
                no_copy=1,
            ),
            dict(
                fieldname="custom_picking_started_by",
                fieldtype="Link",
                options="User",
                label="Picking Started By",
                insert_after="custom_picking_started_at",
                read_only=1,
                allow_on_submit=1,
                no_copy=1,
            ),
            dict(
                fieldname="custom_picking_completed_at",
                fieldtype="Datetime",
                label="Picking Completed At",
                insert_after="custom_picking_started_by",
                read_only=1,
                allow_on_submit=1,
                no_copy=1,
            ),
            dict(
                fieldname="custom_picking_completed_by",
                fieldtype="Link",
                options="User",
                label="Picking Completed By",
                insert_after="custom_picking_completed_at",
                read_only=1,
                allow_on_submit=1,
                no_copy=1,
            ),
            dict(
                fieldname="custom_generated_doctype",
                fieldtype="Link",
                options="DocType",
                label="Generated Document Type",
                insert_after="custom_picking_completed_by",
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
        "Pick List Item": [
            dict(
                fieldname="custom_wms_picked_qty",
                fieldtype="Float",
                label="WMS Picked Qty (Stock UOM)",
                insert_after="picked_qty",
                read_only=1,
                allow_on_submit=1,
                no_copy=1,
                default="0",
                description="Quantity physically picked through the WMS app. Copied into Picked Qty when picking completes.",
            ),
            dict(
                fieldname="custom_optional",
                fieldtype="Check",
                label="Optional (partial pick allowed)",
                insert_after="custom_wms_picked_qty",
                allow_on_submit=1,
                description="When checked, picking can complete with less than the required quantity on this row.",
            ),
        ],
    }


def setup_customizations():
    create_custom_fields(get_custom_fields(), ignore_validate=True, update=True)
    extend_purpose_options()
    frappe.clear_cache(doctype="Pick List")
    frappe.clear_cache(doctype="Pick List Item")


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
