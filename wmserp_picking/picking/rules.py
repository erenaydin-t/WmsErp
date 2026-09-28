"""Pure business rules of the picking workflow.

This module deliberately has no Frappe imports so it can be unit-tested without a bench:

    python -m unittest discover -s erpnext/wmserp_picking -p "test_*.py"
"""

from __future__ import annotations

import json

STATUS_READY = "Ready to Pick"
STATUS_PICKING = "Picking"
STATUS_PICKED = "Picked"
PICKING_STATUSES = (STATUS_READY, STATUS_PICKING, STATUS_PICKED)

PURPOSE_DELIVERY = "Delivery"
PURPOSE_MATERIAL_TRANSFER = "Material Transfer"
PURPOSE_MATERIAL_ISSUE = "Material Issue"
PURPOSE_MATERIAL_TRANSFER_FOR_MANUFACTURE = "Material Transfer for Manufacture"

DELIVERY_NOTE = "Delivery Note"
STOCK_ENTRY = "Stock Entry"

ROW_NOT_PICKED = "Not Picked"
ROW_PARTIAL = "Partial"
ROW_PICKED = "Picked"

QTY_TOLERANCE = 1e-6
SUPERVISOR_ROLES = ("Stock Manager", "System Manager")


class PickingRuleError(ValueError):
    """A validation failure that the API layer turns into `frappe.throw`."""


def _num(value) -> float:
    if value in (None, ""):
        return 0.0
    try:
        return float(value)
    except (TypeError, ValueError) as exc:
        raise PickingRuleError(f"'{value}' is not a valid quantity") from exc


def row_status(picked_qty, required_qty) -> str:
    picked, required = _num(picked_qty), _num(required_qty)
    if picked <= QTY_TOLERANCE:
        return ROW_NOT_PICKED
    if picked + QTY_TOLERANCE >= required:
        return ROW_PICKED
    return ROW_PARTIAL


def is_row_complete(picked_qty, required_qty, optional=False) -> bool:
    """Mandatory rows must match the required quantity exactly; optional rows may be short."""
    picked, required = _num(picked_qty), _num(required_qty)
    if required <= QTY_TOLERANCE:
        return True
    if optional:
        return picked <= required + QTY_TOLERANCE
    return abs(picked - required) <= QTY_TOLERANCE


def incomplete_rows(rows) -> list[dict]:
    """`rows` are dicts with name, item_code, picked_qty, required_qty and optional."""
    result = []
    for row in rows:
        if not is_row_complete(row.get("picked_qty"), row.get("required_qty"), bool(row.get("optional"))):
            result.append(row)
    return result


def validate_picked_qty(picked_qty, required_qty, item_code="") -> float:
    picked, required = _num(picked_qty), _num(required_qty)
    label = f"{item_code}: " if item_code else ""
    if picked < 0:
        raise PickingRuleError(f"{label}picked quantity cannot be negative")
    if picked > required + QTY_TOLERANCE:
        raise PickingRuleError(f"{label}cannot pick {picked:g} (required {required:g})")
    return picked


def target_document(purpose) -> tuple[str, str | None]:
    """Maps a Pick List purpose to (DocType, Stock Entry purpose or None)."""
    mapping = {
        PURPOSE_DELIVERY: (DELIVERY_NOTE, None),
        PURPOSE_MATERIAL_TRANSFER: (STOCK_ENTRY, PURPOSE_MATERIAL_TRANSFER),
        PURPOSE_MATERIAL_ISSUE: (STOCK_ENTRY, PURPOSE_MATERIAL_ISSUE),
        PURPOSE_MATERIAL_TRANSFER_FOR_MANUFACTURE: (STOCK_ENTRY, PURPOSE_MATERIAL_TRANSFER_FOR_MANUFACTURE),
    }
    try:
        return mapping[(purpose or "").strip()]
    except KeyError as exc:
        raise PickingRuleError(f"No target document is defined for purpose '{purpose}'") from exc


def normalize_progress_rows(rows) -> list[dict]:
    """Validates the `items` payload of save_progress / complete_picking.

    Accepts a list (or JSON string) of {"name", "picked_qty", "batch_no"?, "serial_no"?,
    "warehouse"?, "item_code"?} and returns cleaned dicts. Duplicate row names are rejected.
    """
    if isinstance(rows, str):
        try:
            rows = json.loads(rows)
        except ValueError as exc:
            raise PickingRuleError("items must be a JSON list") from exc
    if not isinstance(rows, list):
        raise PickingRuleError("items must be a list of rows")
    cleaned, seen = [], set()
    for index, raw in enumerate(rows):
        if not isinstance(raw, dict):
            raise PickingRuleError(f"items[{index}] must be an object")
        name = str(raw.get("name") or "").strip()
        if not name:
            raise PickingRuleError(f"items[{index}] is missing the row name")
        if name in seen:
            raise PickingRuleError(f"row {name} appears more than once")
        seen.add(name)
        picked = _num(raw.get("picked_qty"))
        if picked < 0:
            raise PickingRuleError(f"row {name}: picked quantity cannot be negative")
        row = {"name": name, "picked_qty": picked}
        for key in ("batch_no", "serial_no", "warehouse", "item_code"):
            value = raw.get(key)
            if value not in (None, ""):
                row[key] = str(value).strip()
        cleaned.append(row)
    return cleaned


def assigned_users(assign_json) -> list[str]:
    """Parses the standard `_assign` column (JSON list of users)."""
    if not assign_json:
        return []
    if isinstance(assign_json, (list, tuple)):
        return [str(user) for user in assign_json if user]
    try:
        users = json.loads(assign_json)
    except (TypeError, ValueError):
        return []
    return [str(user) for user in users if user] if isinstance(users, list) else []


def can_act(user, picker, assigned, roles, allow_supervisor=True) -> bool:
    """A user may act on a pick list when they are its picker, assigned to it, or a supervisor."""
    if not user or user == "Guest":
        return False
    if picker and picker == user:
        return True
    if user in (assigned or []):
        return True
    if allow_supervisor and any(role in (roles or []) for role in SUPERVISOR_ROLES):
        return True
    return False
