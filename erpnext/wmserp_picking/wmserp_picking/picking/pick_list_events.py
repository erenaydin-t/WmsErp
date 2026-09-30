"""`doc_events` hooks for Pick List (registered in hooks.py).

They default the WMS statuses and mirror the row-level pickers into the standard Frappe
assignment (ToDo) of the Pick List, so the desk sidebar, notifications and the app agree on who
works on a card.
"""

import frappe
from frappe import _

from wmserp_picking.picking import rules


def validate(doc, method=None):
    if not doc.get("custom_picking_status"):
        doc.custom_picking_status = rules.STATUS_READY
    for row in doc.get("locations") or []:
        if not row.get("custom_row_status"):
            row.custom_row_status = rules.ROW_NOT_PICKED


def on_submit(doc, method=None):
    if not doc.get("custom_picking_status"):
        doc.db_set("custom_picking_status", rules.STATUS_READY, update_modified=False)
    for row in doc.get("locations") or []:
        if not row.get("custom_row_status"):
            row.db_set("custom_row_status", rules.ROW_NOT_PICKED, update_modified=False)
    sync_picker_assignments(doc)


def on_update_after_submit(doc, method=None):
    sync_picker_assignments(doc)


def row_pickers(doc) -> set:
    return {row.get("custom_picker") for row in (doc.get("locations") or []) if row.get("custom_picker")}


def sync_picker_assignments(doc, previous_pickers=None):
    """Every distinct row picker gets a standard assignment; pickers removed from all rows lose it."""
    current = row_pickers(doc)
    if previous_pickers is None:
        before = doc.get_doc_before_save() if hasattr(doc, "get_doc_before_save") else None
        previous_pickers = row_pickers(before) if before is not None else set()
    for user in set(previous_pickers) - current:
        remove_assignment(doc, user)
    for user in current:
        add_assignment(doc, user)


def add_assignment(doc, user):
    from frappe.desk.form import assign_to

    if user in rules.assigned_users(doc.get("_assign")):
        return
    args = {
        "assign_to": [user],
        "doctype": doc.doctype,
        "name": doc.name,
        "description": _("Pick {0}").format(doc.name),
        "notify": 1,
    }
    try:
        try:
            assign_to.add(args, ignore_permissions=True)
        except TypeError:  # Frappe < 14 has no ignore_permissions argument
            assign_to.add(args)
    except assign_to.DuplicateToDoError:
        pass


def remove_assignment(doc, user):
    from frappe.desk.form import assign_to

    try:
        try:
            assign_to.remove(doc.doctype, doc.name, user, ignore_permissions=True)
        except TypeError:
            assign_to.remove(doc.doctype, doc.name, user)
    except Exception:
        frappe.log_error(title="WMS picking: could not remove assignment", message=frappe.get_traceback())
