"""Pure business rules of receiving against draft Purchase Receipts (no Frappe imports).

The intended flow: purchasing creates a Purchase Order, then a draft Purchase Receipt that goes
through the site's approval workflow; once it reaches the warehouse stage the warehouse counts the
goods against it in the app and confirms, which submits the receipt and puts the stock in.

    python -m unittest discover -s erpnext/wmserp_picking -p "test_*.py"
"""

from __future__ import annotations

import json
import re

QTY_TOLERANCE = 1e-6

# Placeholders of *WMS Settings > Batch ID Pattern* (see `render_batch_id`).
DEFAULT_BATCH_ID_PATTERN = "{counter}-{item}-{batch_no}"
PLAIN_BATCH_ID_PATTERN = "{batch_no}"
_COUNTER_PREFIX = re.compile(r"^(\d+)-")

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


def parse_field_values(text) -> dict:
    """WMS Settings > Receipt Field Values: a JSON object `{"<Doctype>.<fieldname>" or
    "<fieldname>": value}`; blanks are dropped, anything else is refused."""
    if text in (None, "") or not str(text).strip():
        return {}
    try:
        data = json.loads(text) if isinstance(text, str) else text
    except ValueError as exc:
        raise ReceivingRuleError("WMS Settings > Receipt Field Values is not valid JSON") from exc
    if not isinstance(data, dict):
        raise ReceivingRuleError("WMS Settings > Receipt Field Values must be a JSON object")
    out = {}
    for key, value in data.items():
        key = str(key).strip()
        if key and value not in (None, "") and not isinstance(value, (dict, list)):
            out[key] = value
    return out


def split_field_key(key) -> tuple[str | None, str]:
    """`"Purchase Receipt Item.department"` -> ("Purchase Receipt Item", "department");
    `"department"` -> (None, "department")."""
    doctype, dot, fieldname = str(key).rpartition(".")
    return (doctype.strip() or None, fieldname.strip()) if dot else (None, str(key).strip())


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


def bundle_batch(entries) -> str | None:
    """The one batch a Serial and Batch Bundle holds (its `Serial and Batch Entry` rows), or None
    when it holds none or several: the app shows and scans a single batch per receipt row."""
    batches = []
    for entry in entries or []:
        batch = _get(entry, "batch_no")
        if batch and batch not in batches:
            batches.append(batch)
    return batches[0] if len(batches) == 1 else None


def plan_receipt(rows, counted, remove_unreceived=True) -> dict:
    """Decides what happens to each receipt row given what the warehouse counted.

    `rows`: the receipt rows ({name, item_code, qty, warehouse, batch_no, needs_batch, has_batch_no}).
    `counted`: what the app sends ({row, qty, warehouse?, batch_no?, batches?}); quantities in the
    row UOM. `batches` is `[{batch_no, qty}, ...]` for a batch tracked row received in several
    batches (one shipment may bring the same item under two supplier batch numbers): the row's
    quantity is then the sum of its batches and, when the receipt is confirmed, the row is split
    into one Purchase Receipt Item per batch (see `split_batches`).

    Returns {"updates": {row: {qty, warehouse, batch_no, batches}}, "removed": [row...],
             "cleared": [row...], "differences": [{row, item_code, expected, counted}],
             "total_qty": float}.
    Rows the warehouse did not receive (missing or 0) are removed from the receipt when
    `remove_unreceived` (the Purchase Order stays open for them). Without it (saving the
    progress of a count) they are left untouched - the warehouse comes back to them later -
    except that a row sent explicitly with 0 is `cleared` (its saved count goes back to 0).
    """
    by_name = {_get(r, "name"): r for r in rows}
    counts = {}
    for entry in counted or []:
        row = _get(entry, "row")
        if row not in by_name:
            raise ReceivingRuleError(f"Row {row} does not belong to this receipt")
        if row in counts:
            raise ReceivingRuleError(f"Row {row} was sent twice")
        counts[row] = entry

    updates, removed, cleared, differences, total = {}, [], [], [], 0.0
    for row in rows:
        name = _get(row, "name")
        item_code = _get(row, "item_code")
        entry = counts.get(name)
        batches = _counted_batches(entry, item_code) if entry is not None else []
        if batches and not _get(row, "has_batch_no"):
            raise ReceivingRuleError(f"{item_code}: this item is not batch tracked, send its quantity without batches")
        if batches:
            qty = sum(b["qty"] for b in batches)
        else:
            qty = _num(_get(entry, "qty")) if entry is not None else 0.0
        if qty < 0:
            raise ReceivingRuleError(f"{item_code}: quantity cannot be negative")
        expected = _num(_get(row, "qty"))
        if qty <= QTY_TOLERANCE:
            if not remove_unreceived:
                if entry is not None:
                    cleared.append(name)
                continue
            removed.append(name)
            differences.append({"row": name, "item_code": item_code, "expected": expected, "counted": 0.0})
            continue
        warehouse = (_get(entry, "warehouse") or "").strip() or _get(row, "warehouse")
        if not batches:
            batch_no = (_get(entry, "batch_no") or "").strip() or _get(row, "batch_no")
            if batch_no:
                batches = [{"batch_no": batch_no, "qty": qty}]
        if _get(row, "needs_batch") and not batches:
            raise ReceivingRuleError(f"{item_code}: this item is batch tracked, scan or enter its batch")
        updates[name] = {
            "qty": qty,
            "warehouse": warehouse,
            "batch_no": batches[0]["batch_no"] if batches else None,
            "batches": batches,
        }
        total += qty
        if abs(qty - expected) > QTY_TOLERANCE:
            differences.append({"row": name, "item_code": item_code, "expected": expected, "counted": qty})
    if not updates and (remove_unreceived or not cleared):
        # Saving a count in which every row was reset to 0 is a legitimate progress save.
        raise ReceivingRuleError("Nothing was received: count at least one row")
    return {"updates": updates, "removed": removed, "cleared": cleared, "differences": differences, "total_qty": total}


def _counted_batches(entry, item_code) -> list[dict]:
    """The `batches` of one counted entry, cleaned: `[{batch_no, qty}]` with blank batches and
    zero quantities dropped, a batch sent twice for the same row refused."""
    out, seen = [], set()
    for batch in _get(entry, "batches") or []:
        batch_no = (_get(batch, "batch_no") or "").strip()
        qty = _num(_get(batch, "qty"))
        if qty < 0:
            raise ReceivingRuleError(f"{item_code}: quantity cannot be negative")
        if not batch_no:
            if qty > QTY_TOLERANCE:
                raise ReceivingRuleError(f"{item_code}: a counted batch has no batch number")
            continue
        if batch_no in seen:
            raise ReceivingRuleError(f"{item_code}: batch {batch_no} was sent twice for the same row")
        seen.add(batch_no)
        if qty <= QTY_TOLERANCE:
            continue
        out.append({"batch_no": batch_no, "qty": qty})
    return out


def split_batches(update) -> tuple[dict, list[dict]]:
    """How a confirmed row is written: the first batch stays on the row itself, every further
    batch becomes a copy of the row (same Purchase Order row, rate, UOM, warehouse...), which is
    how ERPNext records one Serial and Batch Bundle and one stock ledger entry per batch.
    Returns (first, extras) as `{batch_no, qty}` dicts; a row without batches gives
    (`{batch_no: None, qty}`, [])."""
    batches = list(update.get("batches") or [])
    if not batches:
        return {"batch_no": update.get("batch_no"), "qty": update["qty"]}, []
    return dict(batches[0]), [dict(b) for b in batches[1:]]


# --------------------------------------------------------------------------------------------
# Batches
# --------------------------------------------------------------------------------------------


def batch_key(value) -> str:
    """How two batch numbers are compared: spaces ignored, case ignored (a label printed
    `SPH 405000439-001` and a desk entry `sph405000439-001` are the same batch)."""
    return re.sub(r"\s+", "", str(value or "")).casefold()


def default_batch_id_pattern(has_batch_no_field) -> str:
    """Sites that keep the supplier's batch number in its own Batch field (e.g. `custom_batch_no`)
    name batches `<counter>-<item>-<batch no>`; everywhere else the batch number is the Batch ID."""
    return DEFAULT_BATCH_ID_PATTERN if has_batch_no_field else PLAIN_BATCH_ID_PATTERN


def render_batch_id(pattern, item, batch_no, counter=None) -> str:
    """Fills a pattern with `{item}`, `{batch_no}` and `{counter}`; unknown placeholders are refused."""
    pattern = (pattern or "").strip() or PLAIN_BATCH_ID_PATTERN
    values = {"item": item or "", "batch_no": batch_no or "", "counter": "" if counter is None else str(counter)}
    try:
        return pattern.format(**values).strip()
    except (KeyError, IndexError, ValueError) as exc:
        raise ReceivingRuleError(f"WMS Settings > Batch ID Pattern: unknown placeholder in '{pattern}' (use {{counter}}, {{item}}, {{batch_no}})") from exc


def needs_counter(pattern) -> bool:
    return "{counter}" in (pattern or "")


def next_counter(last_batch_id) -> int:
    """The counter of the next batch: the numeric prefix of the last batch created plus one (the
    convention of sites that number their batches), 1 when there is none."""
    match = _COUNTER_PREFIX.match(str(last_batch_id or ""))
    return int(match.group(1)) + 1 if match else 1


def parse_counted_batches(text) -> list[dict]:
    """The per-batch counts saved on a row while receiving is in progress (`custom_wms_counted_batches`)."""
    if text in (None, ""):
        return []
    try:
        data = json.loads(text) if isinstance(text, str) else text
    except ValueError:
        return []
    if not isinstance(data, list):
        return []
    out = []
    for entry in data:
        if not isinstance(entry, dict):
            continue
        batch_no = str(entry.get("batch_no") or "").strip()
        qty = _num(entry.get("qty"))
        if batch_no and qty > QTY_TOLERANCE:
            out.append({"batch_no": batch_no, "qty": qty})
    return out


def serialize_counted_batches(batches) -> str | None:
    """What is stored in `custom_wms_counted_batches`; None when there is nothing to keep."""
    cleaned = [{"batch_no": b["batch_no"], "qty": b["qty"]} for b in (batches or []) if b.get("batch_no")]
    return json.dumps(cleaned, ensure_ascii=False) if cleaned else None


def _get(obj, key):
    if isinstance(obj, dict):
        return obj.get(key)
    return getattr(obj, key, None)
