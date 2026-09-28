"""`doc_events` hooks for Pick List (registered in hooks.py).

They only default the WMS picking status and keep the custom picker in sync with the standard
Frappe assignment (ToDo), so the desk sidebar, notifications and the app all agree on who
is responsible for a pick list.
"""

import frappe
from frappe import _

from wmserp_picking.picking import rules


def validate(doc, method=None):
    if not doc.get("custom_picking_status"):
        doc.custom_picking_status = rules.STATUS_READY


def on_submit(doc, method=None):
    if not doc.get("custom_picking_status"):
        doc.db_set("custom_picking_status", rules.STATUS_READY, update_modified=False)
    sync_picker_assignment(doc)


def on_update_after_submit(doc, method=None):
    sync_picker_assignment(doc)


def sync_picker_assignment(doc):
    """Mirrors `custom_picker` into the standard Assign To of the document."""
    picker = doc.get("custom_picker")
    previous = None
    before = doc.get_doc_before_save() if hasattr(doc, "get_doc_before_save") else None
    if before is not None:
        previous = before.get("custom_picker")
    if previous and previous != picker:
        remove_assignment(doc, previous)
    if picker:
        add_assignment(doc, picker)


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
