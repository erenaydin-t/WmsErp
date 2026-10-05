"""Pure business rules of receiving against draft Purchase Receipts (no Frappe imports).

The intended flow: purchasing creates a Purchase Order, then a draft Purchase Receipt that goes
through the site's approval workflow; once it reaches the warehouse stage the warehouse counts the
goods against it in the app and confirms, which submits the receipt and puts the stock in.

    python -m unittest discover -s erpnext/wmserp_picking -p "test_*.py"
"""

from __future__ import annotations

QTY_TOLERANCE = 1e-6

# Workflow actions that submit a receipt, in order of preference when several are allowed.
PREFERRED_SUBMIT_ACTIONS = ("receive", "submit", "approve", "confirm", "complete", "accept")


class ReceivingRuleError(ValueError):
    """A validation failure that the API layer turns into `frappe.throw`."""


def _num(value) -> float:
    if value in (None, ""):
        return 0.0
    try:
        return float(value)
    except (TypeError, ValueError) as exc:
        raise ReceivingRuleError(f"'{value}' is not a valid quantity") from exc


def parse_states(text) -> list[str]:
    """WMS Settings > Receipt Workflow States: one state per line (or comma separated)."""
    if not text:
        return []
    out = []
    for line in str(text).replace(",", "\n").splitlines():
        state = line.strip()
        if state and state not in out:
            out.append(state)
    return out


def editable_states(states, roles) -> list[str]:
    """Draft states of a workflow in which one of `roles` may edit: the receipt 'is at that stage'.

    `states` are dicts/rows with `state`, `doc_status`, `allow_edit`."""
    roles = set(roles or [])
    out = []
    for row in states or []:
        state = _get(row, "state")
        if not state or str(_get(row, "doc_status") or "0") != "0":
            continue
        if _get(row, "allow_edit") in roles and state not in out:
            out.append(state)
    return out


def choose_submit_action(transitions, state_docstatus) -> str | None:
    """Among the workflow transitions allowed to the user, the action that submits the document
    (next state with docstatus 1); prefers actions named like Receive / Submit / Approve."""
    candidates = []
    for transition in transitions or []:
        next_state = _get(transition, "next_state")
        action = _get(transition, "action")
        if action and str(state_docstatus.get(next_state, "0")) == "1":
            candidates.append(action)
    if not candidates:
        return None
    lowered = [c.lower() for c in candidates]
    for preferred in PREFERRED_SUBMIT_ACTIONS:
        for index, action in enumerate(lowered):
            if preferred in action:
                return candidates[index]
    return candidates[0]


def plan_receipt(rows, counted, remove_unreceived=True) -> dict:
    """Decides what happens to each receipt row given what the warehouse counted.

    `rows`: the receipt rows ({name, item_code, qty, warehouse, batch_no, needs_batch}).
    `counted`: what the app sends ({row, qty, warehouse?, batch_no?}); quantities in the row UOM.

    Returns {"updates": {row: {qty, warehouse, batch_no}}, "removed": [row...],
             "differences": [{row, item_code, expected, counted}], "total_qty": float}.
    Rows the warehouse did not receive (missing or 0) are removed from the receipt when
    `remove_unreceived` (the Purchase Order stays open for them). Without it (saving the
    progress of a count) they are left untouched: the warehouse comes back to them later.
    """
    by_name = {_get(r, "name"): r for r in rows}
    counts = {}
    for entry in counted or []:
        row = _get(entry, "row")
        if row not in by_name:
            raise ReceivingRuleError(f"Row {row} does not belong to this receipt")
        qty = _num(_get(entry, "qty"))
        if qty < 0:
            raise ReceivingRuleError(f"{_get(by_name[row], 'item_code')}: quantity cannot be negative")
        if row in counts:
            raise ReceivingRuleError(f"Row {row} was sent twice")
        counts[row] = entry

    updates, removed, differences, total = {}, [], [], 0.0
    for row in rows:
        name = _get(row, "name")
        entry = counts.get(name)
        qty = _num(_get(entry, "qty")) if entry is not None else 0.0
        expected = _num(_get(row, "qty"))
        if qty <= QTY_TOLERANCE:
            if not remove_unreceived:
                continue
            removed.append(name)
            differences.append({"row": name, "item_code": _get(row, "item_code"), "expected": expected, "counted": 0.0})
            continue
        warehouse = (_get(entry, "warehouse") or "").strip() or _get(row, "warehouse")
        batch_no = (_get(entry, "batch_no") or "").strip() or _get(row, "batch_no")
        if _get(row, "needs_batch") and not batch_no:
            raise ReceivingRuleError(f"{_get(row, 'item_code')}: this item is batch tracked, scan or enter its batch")
        updates[name] = {"qty": qty, "warehouse": warehouse, "batch_no": batch_no or None}
        total += qty
        if abs(qty - expected) > QTY_TOLERANCE:
            differences.append({"row": name, "item_code": _get(row, "item_code"), "expected": expected, "counted": qty})
    if not updates:
        raise ReceivingRuleError("Nothing was received: count at least one row")
    return {"updates": updates, "removed": removed, "differences": differences, "total_qty": total}


def _get(obj, key):
    if isinstance(obj, dict):
        return obj.get(key)
    return getattr(obj, key, None)
