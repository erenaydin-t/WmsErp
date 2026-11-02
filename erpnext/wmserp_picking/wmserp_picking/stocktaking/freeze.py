"""Warehouse freeze during a stocktaking session.

ERPNext has no per-warehouse freeze, so the session blocks stock movements at the one place every
transaction passes through: the insertion of *Stock Ledger Entries* (`doc_events` in hooks.py).
Only the session's own Stock Reconciliation is allowed to post into the frozen warehouse.
"""

import frappe
from frappe import _
from frappe.utils import cint

from wmserp_picking.stocktaking import rules

SESSION = "Stocktaking Session"
CACHE_KEY = "wms_stocktaking_frozen_sessions"


def invalidate():
    """Forget the per-request cache after a session changes state."""
    if hasattr(frappe.local, CACHE_KEY):
        delattr(frappe.local, CACHE_KEY)


def frozen_sessions() -> list:
    cached = getattr(frappe.local, CACHE_KEY, None)
    if cached is None:
        try:
            cached = frappe.get_all(
                SESSION,
                filters={"frozen": 1, "status": ["in", list(rules.FROZEN_SESSION_STATUSES)]},
                fields=["name", "warehouse", "status", "stock_reconciliation"],
            )
        except Exception:
            # DocType not synced yet (first migrate): nothing can be frozen.
            cached = []
        setattr(frappe.local, CACHE_KEY, cached)
    return cached


def session_freezing(warehouse):
    """The active session whose warehouse (or warehouse group) contains `warehouse`, or None."""
    sessions = frozen_sessions()
    if not sessions or not warehouse:
        return None
    for session in sessions:
        if session.warehouse == warehouse:
            return session
    bounds = frappe.db.get_value("Warehouse", warehouse, ["lft", "rgt"], as_dict=True)
    if not bounds:
        return None
    for session in sessions:
        group = frappe.db.get_value("Warehouse", session.warehouse, ["lft", "rgt", "is_group"], as_dict=True)
        if group and cint(group.is_group) and group.lft < bounds.lft and group.rgt > bounds.rgt:
            return session
    return None


def check_stock_ledger_entry(doc, method=None):
    """`Stock Ledger Entry.before_insert`: refuse movements in a frozen warehouse."""
    warehouse = doc.get("warehouse")
    if not warehouse:
        return
    session = session_freezing(warehouse)
    if not session:
        return
    if doc.get("voucher_type") == "Stock Reconciliation" and session.stock_reconciliation and doc.get("voucher_no") == session.stock_reconciliation:
        return
    frappe.throw(
        _("Warehouse {0} is frozen for stocktaking {1} ({2}). Stock transactions are blocked until the count is completed or cancelled.").format(
            frappe.bold(warehouse), frappe.bold(session.name), _(session.status)
        ),
        title=_("Warehouse Frozen"),
    )


def on_reconciliation_submit(doc, method=None):
    """Submitting the session's Stock Reconciliation completes the session and unfreezes the warehouse."""
    from wmserp_picking.stocktaking import service

    session = doc.get("custom_stocktaking_session")
    if not session or not frappe.db.exists(SESSION, session):
        return
    if frappe.db.get_value(SESSION, session, "stock_reconciliation") != doc.name:
        return
    service.mark_completed(frappe.get_doc(SESSION, session), by=frappe.session.user)


def on_reconciliation_cancel(doc, method=None):
    """Cancelling it reopens the session at Final Approval so a new reconciliation can be created."""
    session = doc.get("custom_stocktaking_session")
    if not session or not frappe.db.exists(SESSION, session):
        return
    if frappe.db.get_value(SESSION, session, "stock_reconciliation") != doc.name:
        return
    session_doc = frappe.get_doc(SESSION, session)
    session_doc.db_set({"status": rules.SESSION_FINAL_APPROVAL, "stock_reconciliation": None, "reconciled_at": None, "completed_at": None, "completed_by": None})
    frappe.db.sql(
        "update `tabStocktaking Item` set status=%s where session=%s and status=%s",
        (rules.ITEM_APPROVED, session, rules.ITEM_FINALIZED),
    )
    session_doc.add_comment("Info", _("Stock Reconciliation {0} was cancelled; the session is back at Final Approval.").format(doc.name))
    invalidate()
