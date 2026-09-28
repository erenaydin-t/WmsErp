"""Pure business rules of the picking workflow.

This module deliberately has no Frappe imports so it can be unit-tested without a bench:

    python -m unittest discover -s erpnext/wmserp_picking -p "test_*.py"
"""

from __future__ import annotations

import datetime as dt
import json

# Header (card) status: Pick List.custom_picking_status
STATUS_READY = "Ready to Pick"
STATUS_PICKING = "Picking"
STATUS_PICKED = "Picked"
PICKING_STATUSES = (STATUS_READY, STATUS_PICKING, STATUS_PICKED)

# Row status: Pick List Item.custom_row_status
ROW_NOT_PICKED = "Not Picked"
ROW_PICKING = "Picking"
ROW_PICKED = "Picked"
ROW_STATUSES = (ROW_NOT_PICKED, ROW_PICKING, ROW_PICKED)

PURPOSE_DELIVERY = "Delivery"
PURPOSE_MATERIAL_TRANSFER = "Material Transfer"
PURPOSE_MATERIAL_ISSUE = "Material Issue"
PURPOSE_MATERIAL_TRANSFER_FOR_MANUFACTURE = "Material Transfer for Manufacture"

DELIVERY_NOTE = "Delivery Note"
STOCK_ENTRY = "Stock Entry"

DEFAULT_QR_ITEM_KEY = "item_code"
DEFAULT_QR_BATCH_KEY = "batch_no"

QTY_TOLERANCE = 1e-6
SUPERVISOR_ROLES = ("Stock Manager", "System Manager")

SCAN_MATCH = "match"
SCAN_WRONG_ITEM = "wrong_item"
SCAN_WRONG_BATCH = "wrong_batch"
SCAN_MISSING_BATCH = "missing_batch"


class PickingRuleError(ValueError):
    """A validation failure that the API layer turns into `frappe.throw`."""


def _num(value) -> float:
    if value in (None, ""):
        return 0.0
    try:
        return float(value)
    except (TypeError, ValueError) as exc:
        raise PickingRuleError(f"'{value}' is not a valid quantity") from exc


# ---- quantities & statuses ---------------------------------------------------------------


def is_row_complete(picked_qty, required_qty) -> bool:
    """A row is complete when the picked quantity equals the required quantity."""
    picked, required = _num(picked_qty), _num(required_qty)
    if required <= QTY_TOLERANCE:
        return True
    return abs(picked - required) <= QTY_TOLERANCE


def validate_picked_qty(picked_qty, required_qty, item_code="") -> float:
    """0 <= picked <= required; over-picking is never allowed."""
    picked, required = _num(picked_qty), _num(required_qty)
    label = f"{item_code}: " if item_code else ""
    if picked < 0:
        raise PickingRuleError(f"{label}picked quantity cannot be negative")
    if picked > required + QTY_TOLERANCE:
        raise PickingRuleError(f"{label}cannot pick {picked:g} (required {required:g})")
    return picked


def next_row_status(picked_qty, required_qty, current_status=None) -> str:
    if is_row_complete(picked_qty, required_qty):
        return ROW_PICKED
    if _num(picked_qty) > QTY_TOLERANCE or current_status == ROW_PICKING:
        return ROW_PICKING
    return ROW_NOT_PICKED


def card_complete(row_statuses) -> bool:
    """The card (Pick List) is picked when every row is picked."""
    statuses = list(row_statuses)
    return bool(statuses) and all(status == ROW_PICKED for status in statuses)


def duration_seconds(started_at, completed_at, elapsed_hint=None) -> float:
    """Seconds between two datetimes; falls back to the client's elapsed time when no start is known."""
    if started_at is not None and completed_at is not None:
        if isinstance(started_at, dt.datetime) and isinstance(completed_at, dt.datetime):
            return max(0.0, (completed_at - started_at).total_seconds())
    if elapsed_hint not in (None, ""):
        return max(0.0, _num(elapsed_hint))
    return 0.0


# ---- QR labels ---------------------------------------------------------------------------


def parse_qr(payload, item_key=DEFAULT_QR_ITEM_KEY, batch_key=DEFAULT_QR_BATCH_KEY) -> dict:
    """Strictly parses a scanned QR code: it must be a JSON object holding the configured keys.

    Returns {"item_code": str, "batch_no": str | None}. Anything else raises PickingRuleError.
    """
    if isinstance(payload, (bytes, bytearray)):
        payload = payload.decode("utf-8", errors="replace")
    if isinstance(payload, str):
        text = payload.strip().strip("\x1d")
        if not text:
            raise PickingRuleError("empty scan")
        try:
            data = json.loads(text)
        except ValueError as exc:
            raise PickingRuleError("scan is not a JSON QR label") from exc
    else:
        data = payload
    if not isinstance(data, dict):
        raise PickingRuleError("QR label must be a JSON object")
    item_code = data.get(item_key)
    if item_code is None or str(item_code).strip() == "":
        raise PickingRuleError(f"QR label has no '{item_key}'")
    batch = data.get(batch_key)
    batch_no = None if batch in (None, "") else str(batch).strip()
    return {"item_code": str(item_code).strip(), "batch_no": batch_no}


def check_scan(scanned_item, scanned_batch, row_item, expected_batch) -> str:
    """Compares a parsed QR label with a row: SCAN_MATCH, SCAN_WRONG_ITEM, SCAN_WRONG_BATCH or
    SCAN_MISSING_BATCH (the row expects a batch but the label carries none)."""
    if (scanned_item or "").strip().lower() != (row_item or "").strip().lower():
        return SCAN_WRONG_ITEM
    expected = (expected_batch or "").strip()
    scanned = (scanned_batch or "").strip()
    if expected:
        if not scanned:
            return SCAN_MISSING_BATCH
        if scanned.lower() != expected.lower():
            return SCAN_WRONG_BATCH
    return SCAN_MATCH


# ---- documents ----------------------------------------------------------------------------


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


# ---- permissions ---------------------------------------------------------------------------


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


def is_supervisor(roles) -> bool:
    return any(role in (roles or []) for role in SUPERVISOR_ROLES)


def can_act_on_row(user, row_picker, roles, allow_supervisor=True) -> bool:
    """A user may work on a row when it is assigned to them (or they supervise)."""
    if not user or user == "Guest":
        return False
    if row_picker and row_picker == user:
        return True
    return allow_supervisor and is_supervisor(roles)


def can_act_on_card(user, row_pickers, roles, allow_supervisor=True) -> bool:
    """A user may act on the card (e.g. generate its document) when any row is theirs."""
    if not user or user == "Guest":
        return False
    if user in set(row_pickers or []):
        return True
    return allow_supervisor and is_supervisor(roles)


# ---- KPIs -----------------------------------------------------------------------------------


def kpi_summary(rows) -> dict:
    """Aggregates picked rows (dicts with parent, picked_qty, duration_seconds) into picker KPIs."""
    rows = list(rows)
    durations = [_num(row.get("duration_seconds")) for row in rows if row.get("duration_seconds") not in (None, "")]
    total_seconds = sum(durations)
    summary = {
        "rows_picked": len(rows),
        "qty_picked": sum(_num(row.get("picked_qty")) for row in rows),
        "pick_lists_touched": len({row.get("parent") for row in rows if row.get("parent")}),
        "total_seconds": total_seconds,
        "avg_seconds_per_row": (total_seconds / len(durations)) if durations else None,
        "fastest_seconds": min(durations) if durations else None,
        "slowest_seconds": max(durations) if durations else None,
        "rows_per_hour": (len(durations) / (total_seconds / 3600.0)) if total_seconds > 0 else None,
    }
    return summary
