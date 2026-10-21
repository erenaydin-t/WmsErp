"""Whitelisted API of the stocktaking (physical inventory count) workflow.

Called as `/api/method/wmserp_picking.api.stocktaking.<function>`. The Android app uses the
counter endpoints (`get_my_sessions`, `get_session`, `get_items`, `lookup`, `submit_count`,
`sync_counts`); the manager endpoints back the buttons of the *Stocktaking Session* form and the
bulk actions of the *Stocktaking Item* list in the desk. All logic lives in
`wmserp_picking.stocktaking.service`.
"""

import frappe
from frappe.utils import cint

from wmserp_picking.stocktaking import service

# ---- counters (app) --------------------------------------------------------------------------


@frappe.whitelist()
def get_my_sessions():
    """Sessions in which the current user can count right now."""
    return service.my_sessions()


@frappe.whitelist()
def get_session(name):
    """Header, options, totals and the caller's own statistics of one session."""
    doc = service.get_session_doc(name)
    return service.serialize_session(doc)


@frappe.whitelist()
def get_items(name, start=0, limit=1000, mine=0, status=None, query=None):
    """A page of rows (item / warehouse / batch) with the barcodes of their items."""
    return service.get_items(name, start=start, limit=limit, mine=mine, status=status, query=query)


@frappe.whitelist()
def lookup(name, code, warehouse=None):
    """Identifies a scanned code (JSON QR label, barcode, item code or batch) in the session."""
    return service.lookup(name, code, warehouse=warehouse)


@frappe.whitelist()
def start_item(item):
    """Optional: flag a row as Counting while the counter has it open."""
    return service.start_counting_item(item)


@frappe.whitelist()
def submit_count(name, qty, item=None, item_code=None, batch_no=None, warehouse=None, client_ref=None, device_time=None, note=None, source="App"):
    """Records one physical count (count #1, count #2 or recount) and returns the outcome."""
    return service.submit_count(
        name,
        qty,
        item=item,
        item_code=item_code,
        batch_no=batch_no,
        warehouse=warehouse,
        client_ref=client_ref,
        device_time=device_time,
        note=note,
        source=source,
    )


@frappe.whitelist()
def sync_counts(name, counts):
    """Replays counts queued offline (each with its `client_ref`); one result per entry."""
    return service.sync_counts(name, counts)


@frappe.whitelist()
def get_item_history(item):
    """Every count of one row, oldest first (the audit trail)."""
    return service.item_history(item)


# ---- managers (desk) -------------------------------------------------------------------------


@frappe.whitelist()
def start_session(name):
    return service.start_session(name)


@frappe.whitelist()
def assign_items(name, user=None, items=None, filters=None, unassign=0, area=None):
    """Assigns every selected / matching assignable row to `user` in one action."""
    return service.assign_items(name, user=user, items=items, filters=filters, unassign=cint(unassign), area=area)


@frappe.whitelist()
def count_assignable(name, filters=None):
    """How many rows the Assign Items dialog filters would touch (preview)."""
    return frappe.db.count("Stocktaking Item", service.assignment_filters(name, filters))


@frappe.whitelist()
def complete_counting(name):
    return service.complete_counting(name)


@frappe.whitelist()
def request_recount(item, note=None):
    return service.request_recount(item, note=note)


@frappe.whitelist()
def request_recounts(items, note=None):
    """Bulk version for the list view: one recount request per selected row."""
    items = frappe.parse_json(items) if isinstance(items, str) else (items or [])
    return [service.request_recount(item, note=note) for item in items]


@frappe.whitelist()
def accept_item(item, note=None):
    return service.accept_item(item, note=note)


@frappe.whitelist()
def accept_items(items, note=None):
    items = frappe.parse_json(items) if isinstance(items, str) else (items or [])
    return [service.accept_item(item, note=note) for item in items]


@frappe.whitelist()
def accept_all(name, note=None):
    return service.accept_all(name, note=note)


@frappe.whitelist()
def approve_session(name):
    return service.approve_session(name)


@frappe.whitelist()
def create_reconciliation(name, submit=0):
    return service.create_reconciliation(name, submit=cint(submit))


@frappe.whitelist()
def complete_session(name):
    return service.complete_session(name)


@frappe.whitelist()
def cancel_session(name, reason=None):
    return service.cancel_session(name, reason=reason)


@frappe.whitelist()
def get_progress(name):
    doc = service.get_session_doc(name)
    return {"totals": doc.totals(), "status": doc.status, "frozen": bool(doc.frozen)}
