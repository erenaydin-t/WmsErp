"""Stocktaking Variance: every counted row of a session with its quantity and value difference,
the count history (count #1 / #2 / recount), who counted it and the review state."""

import frappe
from frappe import _
from frappe.utils import flt

from wmserp_picking.stocktaking import rules


def execute(filters=None):
    filters = frappe._dict(filters or {})
    if not filters.get("session"):
        frappe.throw(_("Please select a Stocktaking Session."))
    conditions = {"session": filters.session}
    if filters.get("status"):
        conditions["status"] = filters.status
    if filters.get("counter"):
        conditions["counter"] = filters.counter
    if filters.get("item_group"):
        conditions["item_group"] = filters.item_group
    rows = frappe.get_all(
        "Stocktaking Item",
        filters=conditions,
        fields=[
            "name", "item_code", "item_name", "warehouse", "batch_no", "expiry_date", "stock_uom", "erp_qty", "valuation_rate",
            "count_1", "count_2", "recount_qty", "recount_count", "final_qty", "qty_difference", "value_difference", "status",
            "counter", "counted_by", "counted_at", "reviewed_by", "recount_note", "review_note", "location", "added_during_count",
        ],
        order_by="item_name asc, batch_no asc",
        limit_page_length=0,
    )
    rows.sort(key=lambda r: -abs(flt(r.value_difference)))
    only_variance = filters.get("only_variance", 1)
    data = []
    for row in rows:
        if only_variance and row.status in rules.UNCOUNTED_STATUSES:
            continue
        if only_variance and abs(flt(row.qty_difference)) <= rules.QTY_TOLERANCE and row.status not in rules.PENDING_REVIEW_STATUSES + (rules.ITEM_RECOUNT_REQUIRED,):
            continue
        data.append(
            {
                "stocktaking_item": row.name,
                "item_code": row.item_code,
                "item_name": row.item_name,
                "warehouse": row.warehouse,
                "batch_no": row.batch_no,
                "expiry_date": row.expiry_date,
                "location": row.location,
                "erp_qty": flt(row.erp_qty),
                "count_1": row.count_1,
                "count_2": row.count_2,
                "recount_qty": row.recount_qty,
                "recount_count": row.recount_count,
                "final_qty": row.final_qty,
                "qty_difference": flt(row.qty_difference),
                "valuation_rate": flt(row.valuation_rate),
                "value_difference": flt(row.value_difference),
                "status": row.status,
                "counter": row.counter,
                "counted_by": row.counted_by,
                "counted_at": row.counted_at,
                "reviewed_by": row.reviewed_by,
                "added_during_count": row.added_during_count,
                "note": row.review_note or row.recount_note,
            }
        )
    return columns(), data


def columns():
    return [
        {"label": _("Row"), "fieldname": "stocktaking_item", "fieldtype": "Link", "options": "Stocktaking Item", "width": 90},
        {"label": _("Item"), "fieldname": "item_code", "fieldtype": "Link", "options": "Item", "width": 130},
        {"label": _("Item Name"), "fieldname": "item_name", "fieldtype": "Data", "width": 200},
        {"label": _("Warehouse"), "fieldname": "warehouse", "fieldtype": "Link", "options": "Warehouse", "width": 140},
        {"label": _("Batch"), "fieldname": "batch_no", "fieldtype": "Link", "options": "Batch", "width": 130},
        {"label": _("Expiry"), "fieldname": "expiry_date", "fieldtype": "Date", "width": 100},
        {"label": _("Location"), "fieldname": "location", "fieldtype": "Data", "width": 100},
        {"label": _("ERP Qty"), "fieldname": "erp_qty", "fieldtype": "Float", "width": 90},
        {"label": _("Count #1"), "fieldname": "count_1", "fieldtype": "Float", "width": 90},
        {"label": _("Count #2"), "fieldname": "count_2", "fieldtype": "Float", "width": 90},
        {"label": _("Recount"), "fieldname": "recount_qty", "fieldtype": "Float", "width": 90},
        {"label": _("Recounts"), "fieldname": "recount_count", "fieldtype": "Int", "width": 80},
        {"label": _("Final Qty"), "fieldname": "final_qty", "fieldtype": "Float", "width": 90},
        {"label": _("Difference"), "fieldname": "qty_difference", "fieldtype": "Float", "width": 100},
        {"label": _("Valuation Rate"), "fieldname": "valuation_rate", "fieldtype": "Currency", "width": 110},
        {"label": _("Value Difference"), "fieldname": "value_difference", "fieldtype": "Currency", "width": 130},
        {"label": _("Status"), "fieldname": "status", "fieldtype": "Data", "width": 130},
        {"label": _("Counter"), "fieldname": "counter", "fieldtype": "Link", "options": "User", "width": 150},
        {"label": _("Last Counted By"), "fieldname": "counted_by", "fieldtype": "Link", "options": "User", "width": 150},
        {"label": _("Counted At"), "fieldname": "counted_at", "fieldtype": "Datetime", "width": 150},
        {"label": _("Reviewed By"), "fieldname": "reviewed_by", "fieldtype": "Link", "options": "User", "width": 150},
        {"label": _("Added During Count"), "fieldname": "added_during_count", "fieldtype": "Check", "width": 80},
        {"label": _("Note"), "fieldname": "note", "fieldtype": "Data", "width": 200},
    ]
