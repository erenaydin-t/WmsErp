"""Stocktaking Uncounted Items: what still has to be counted (or recounted) in a session, so the
count is never considered complete while rows are missing."""

import frappe
from frappe import _
from frappe.utils import flt

from wmserp_picking.stocktaking import rules

OPEN_STATUSES = rules.UNCOUNTED_STATUSES + (rules.ITEM_RECOUNT_REQUIRED,)


def execute(filters=None):
    filters = frappe._dict(filters or {})
    if not filters.get("session"):
        frappe.throw(_("Please select a Stocktaking Session."))
    conditions = {"session": filters.session, "status": ["in", list(OPEN_STATUSES)]}
    if filters.get("status"):
        conditions["status"] = filters.status
    if filters.get("counter") == "__unassigned__":
        conditions["counter"] = ["is", "not set"]
    elif filters.get("counter"):
        conditions["counter"] = filters.counter
    if filters.get("item_group"):
        conditions["item_group"] = filters.item_group
    if filters.get("location"):
        conditions["location"] = ["like", f"%{filters.location}%"]
    rows = frappe.get_all(
        "Stocktaking Item",
        filters=conditions,
        fields=[
            "name", "item_code", "item_name", "warehouse", "batch_no", "expiry_date", "stock_uom", "erp_qty", "status",
            "next_count_type", "counter", "location", "item_group", "brand", "count_1", "recount_note", "recount_requested_by",
        ],
        order_by="location asc, item_name asc, batch_no asc",
        limit_page_length=0,
    )
    totals = frappe.get_doc("Stocktaking Session", filters.session).totals()
    message = _("Total {0} · Counted {1} · Not counted {2} · Recount required {3}").format(
        totals["total_items"], totals["counted_items"], totals["uncounted_items"], totals["recount_required"]
    )
    data = [
        {
            "stocktaking_item": r.name,
            "item_code": r.item_code,
            "item_name": r.item_name,
            "warehouse": r.warehouse,
            "batch_no": r.batch_no,
            "expiry_date": r.expiry_date,
            "location": r.location,
            "item_group": r.item_group,
            "brand": r.brand,
            "erp_qty": flt(r.erp_qty),
            "stock_uom": r.stock_uom,
            "status": r.status,
            "next_count_type": r.next_count_type,
            "counter": r.counter,
            "count_1": r.count_1,
            "recount_note": r.recount_note,
            "recount_requested_by": r.recount_requested_by,
        }
        for r in rows
    ]
    return columns(), data, message


def columns():
    return [
        {"label": _("Row"), "fieldname": "stocktaking_item", "fieldtype": "Link", "options": "Stocktaking Item", "width": 90},
        {"label": _("Item"), "fieldname": "item_code", "fieldtype": "Link", "options": "Item", "width": 130},
        {"label": _("Item Name"), "fieldname": "item_name", "fieldtype": "Data", "width": 220},
        {"label": _("Warehouse"), "fieldname": "warehouse", "fieldtype": "Link", "options": "Warehouse", "width": 140},
        {"label": _("Batch"), "fieldname": "batch_no", "fieldtype": "Link", "options": "Batch", "width": 130},
        {"label": _("Expiry"), "fieldname": "expiry_date", "fieldtype": "Date", "width": 100},
        {"label": _("Location"), "fieldname": "location", "fieldtype": "Data", "width": 100},
        {"label": _("Item Group"), "fieldname": "item_group", "fieldtype": "Link", "options": "Item Group", "width": 120},
        {"label": _("Brand"), "fieldname": "brand", "fieldtype": "Link", "options": "Brand", "width": 100},
        {"label": _("ERP Qty"), "fieldname": "erp_qty", "fieldtype": "Float", "width": 90},
        {"label": _("UOM"), "fieldname": "stock_uom", "fieldtype": "Link", "options": "UOM", "width": 70},
        {"label": _("Status"), "fieldname": "status", "fieldtype": "Data", "width": 130},
        {"label": _("Next Count"), "fieldname": "next_count_type", "fieldtype": "Data", "width": 90},
        {"label": _("Counter"), "fieldname": "counter", "fieldtype": "Link", "options": "User", "width": 150},
        {"label": _("Count #1"), "fieldname": "count_1", "fieldtype": "Float", "width": 90},
        {"label": _("Recount Requested By"), "fieldname": "recount_requested_by", "fieldtype": "Link", "options": "User", "width": 150},
        {"label": _("Recount Note"), "fieldname": "recount_note", "fieldtype": "Data", "width": 200},
    ]
