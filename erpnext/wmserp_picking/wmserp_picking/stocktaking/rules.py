"""Pure business rules of the stocktaking (physical inventory count) workflow.

No Frappe imports, so the module is unit-tested without a bench:

    python -m unittest discover -s erpnext/wmserp_picking -p "test_*.py"

Model
-----
* A **Stocktaking Session** freezes one warehouse (or a warehouse group) and snapshots the ERP
  quantities of every item / batch in it into **Stocktaking Item** rows (item + warehouse + batch).
* Counters count rows: the first count is accepted when it matches the ERP quantity, otherwise the
  counter must count a second time; a second count that still differs goes to the manager. The
  manager can request a recount as often as needed. Every count is written to an immutable
  **Stocktaking Count** log; nothing is overwritten.
* When every row is counted and every difference reviewed, the manager approves the session and
  a *Stock Reconciliation* is created from the final quantities; submitting it completes the
  session and unfreezes the warehouse.
"""

from __future__ import annotations

import json

from wmserp_picking.picking.rules import DEFAULT_QR_BATCH_KEY, DEFAULT_QR_ITEM_KEY

# ---- session --------------------------------------------------------------------------------

SESSION_DRAFT = "Draft"
SESSION_COUNTING = "Counting"
SESSION_MANAGER_REVIEW = "Manager Review"
SESSION_RECOUNT = "Recount"
SESSION_FINAL_APPROVAL = "Final Approval"
SESSION_RECONCILED = "Reconciled"
SESSION_COMPLETED = "Completed"
SESSION_CANCELLED = "Cancelled"
SESSION_STATUSES = (
    SESSION_DRAFT,
    SESSION_COUNTING,
    SESSION_MANAGER_REVIEW,
    SESSION_RECOUNT,
    SESSION_FINAL_APPROVAL,
    SESSION_RECONCILED,
    SESSION_COMPLETED,
    SESSION_CANCELLED,
)
# Counts are accepted from the app in these states.
COUNTING_SESSION_STATUSES = (SESSION_COUNTING, SESSION_RECOUNT)
# The warehouse stays frozen in these states.
FROZEN_SESSION_STATUSES = (SESSION_COUNTING, SESSION_MANAGER_REVIEW, SESSION_RECOUNT, SESSION_FINAL_APPROVAL, SESSION_RECONCILED)
CLOSED_SESSION_STATUSES = (SESSION_COMPLETED, SESSION_CANCELLED)

MODE_ASSIGNED = "Assigned"
MODE_OPEN = "Open"
COUNTING_MODES = (MODE_ASSIGNED, MODE_OPEN)

POLICY_LOCK = "Lock after count"
POLICY_ALLOW = "Allow additional counts"
DUPLICATE_POLICIES = (POLICY_LOCK, POLICY_ALLOW)

# ---- rows -----------------------------------------------------------------------------------

ITEM_NOT_COUNTED = "Not Counted"
ITEM_ASSIGNED = "Assigned"
ITEM_COUNTING = "Counting"
ITEM_COUNTED = "Counted"
ITEM_RECOUNT_REQUIRED = "Recount Required"
ITEM_RECOUNTED = "Recounted"
ITEM_MANAGER_REVIEW = "Manager Review"
ITEM_APPROVED = "Approved"
ITEM_FINALIZED = "Finalized"
ITEM_STATUSES = (
    ITEM_NOT_COUNTED,
    ITEM_ASSIGNED,
    ITEM_COUNTING,
    ITEM_COUNTED,
    ITEM_RECOUNT_REQUIRED,
    ITEM_RECOUNTED,
    ITEM_MANAGER_REVIEW,
    ITEM_APPROVED,
    ITEM_FINALIZED,
)
UNCOUNTED_STATUSES = (ITEM_NOT_COUNTED, ITEM_ASSIGNED, ITEM_COUNTING)
PENDING_REVIEW_STATUSES = (ITEM_MANAGER_REVIEW, ITEM_RECOUNTED)
# Rows that carry a final quantity.
COUNTED_STATUSES = (ITEM_COUNTED, ITEM_MANAGER_REVIEW, ITEM_RECOUNTED, ITEM_APPROVED, ITEM_FINALIZED)
# Rows the manager may send back for another count.
RECOUNTABLE_STATUSES = (ITEM_NOT_COUNTED, ITEM_ASSIGNED, ITEM_COUNTING, ITEM_COUNTED, ITEM_MANAGER_REVIEW, ITEM_RECOUNTED, ITEM_APPROVED)

COUNT_1 = "Count 1"
COUNT_2 = "Count 2"
RECOUNT = "Recount"
COUNT_TYPES = (COUNT_1, COUNT_2, RECOUNT)

OUTCOME_ACCEPTED = "accepted"
OUTCOME_SECOND_COUNT_REQUIRED = "second_count_required"
OUTCOME_MANAGER_REVIEW = "manager_review"
OUTCOME_RECOUNT_RECORDED = "recount_recorded"
OUTCOME_ADDITIONAL_COUNT = "additional_count"
OUTCOME_DUPLICATE = "duplicate"

QTY_TOLERANCE = 1e-6
SUPERVISOR_ROLES = ("Stock Manager", "System Manager")


class StocktakingRuleError(ValueError):
    """A validation failure that the API layer turns into `frappe.throw`."""


class AlreadyCountedError(StocktakingRuleError):
    def __init__(self, message, counted_by=None):
        super().__init__(message)
        self.counted_by = counted_by


class NotAssignedError(StocktakingRuleError):
    def __init__(self, message, counter=None):
        super().__init__(message)
        self.counter = counter


def _num(value) -> float:
    if value in (None, ""):
        return 0.0
    try:
        return float(value)
    except (TypeError, ValueError) as exc:
        raise StocktakingRuleError(f"'{value}' is not a valid quantity") from exc


def is_supervisor(roles) -> bool:
    return any(role in (roles or []) for role in SUPERVISOR_ROLES)


# ---- counting -------------------------------------------------------------------------------


def validate_count_qty(qty) -> float:
    """A physical count is a non-negative number (zero means "none found")."""
    value = _num(qty)
    if value < 0:
        raise StocktakingRuleError("a physical count cannot be negative")
    return value


def is_matched(qty, erp_qty, tolerance=0.0) -> bool:
    """The count agrees with ERPNext within the session tolerance (absolute, in stock UOM)."""
    return abs(_num(qty) - _num(erp_qty)) <= max(_num(tolerance), 0.0) + QTY_TOLERANCE


def is_locked(item_status, policy=POLICY_LOCK) -> bool:
    """A counted row cannot be counted again unless the session allows additional counts."""
    return item_status in COUNTED_STATUSES and policy != POLICY_ALLOW


def can_count(session_status, mode, item_status, item_counter, user, roles, counters=(), policy=POLICY_LOCK, counted_by=None) -> None:
    """Raises when `user` may not count this row right now.

    * counts are only accepted while the session is Counting or Recount;
    * a row that is already counted is locked (unless the policy allows additional counts) and
      the counter is told who counted it, before any assignment check;
    * in Assigned mode a row belongs to its counter (supervisors may count anything);
    * in Open mode any registered counter (or supervisor) may count any row;
    * finalized rows are immutable.
    """
    if not user or user == "Guest":
        raise StocktakingRuleError("sign in to count")
    if session_status not in COUNTING_SESSION_STATUSES:
        raise StocktakingRuleError(f"counting is closed (session is {session_status})")
    if item_status == ITEM_FINALIZED:
        raise StocktakingRuleError("this row is finalized and cannot be counted again")
    if is_locked(item_status, policy):
        raise AlreadyCountedError("this item has already been counted", counted_by=counted_by)
    supervisor = is_supervisor(roles)
    if mode == MODE_ASSIGNED:
        if item_counter and item_counter != user and not supervisor:
            raise NotAssignedError(f"this item is assigned to {item_counter}", counter=item_counter)
        if not item_counter and not supervisor and user not in set(counters or ()):
            raise NotAssignedError("this item is not assigned to you")
    elif not supervisor and counters and user not in set(counters):
        raise NotAssignedError("you are not a counter of this session")


def evaluate_count(status, next_count_type, qty, erp_qty, tolerance=0.0, require_second_count=True, policy=POLICY_LOCK, counted_by=None) -> dict:
    """Applies one physical count to a row and returns what changes.

    Returns a dict with `count_type` (what this count is), `status` and `next_count_type` (the row
    afterwards), `final_qty` (None while a second count is pending), `matched` and `outcome`.
    """
    qty = validate_count_qty(qty)
    matched = is_matched(qty, erp_qty, tolerance)
    status = status or ITEM_NOT_COUNTED
    if status == ITEM_FINALIZED:
        raise AlreadyCountedError("this row is finalized", counted_by=counted_by)

    if status in UNCOUNTED_STATUSES:
        if matched or not require_second_count:
            return _result(COUNT_1, ITEM_COUNTED if matched else ITEM_MANAGER_REVIEW, None, qty, matched, OUTCOME_ACCEPTED if matched else OUTCOME_MANAGER_REVIEW)
        return _result(COUNT_1, ITEM_RECOUNT_REQUIRED, COUNT_2, None, matched, OUTCOME_SECOND_COUNT_REQUIRED)

    if status == ITEM_RECOUNT_REQUIRED:
        if (next_count_type or COUNT_2) == COUNT_2:
            if matched:
                return _result(COUNT_2, ITEM_COUNTED, None, qty, matched, OUTCOME_ACCEPTED)
            return _result(COUNT_2, ITEM_MANAGER_REVIEW, None, qty, matched, OUTCOME_MANAGER_REVIEW)
        # A recount requested by the manager: a matching recount closes the row, a differing one
        # goes back to the manager with the whole history.
        if matched:
            return _result(RECOUNT, ITEM_COUNTED, None, qty, matched, OUTCOME_ACCEPTED)
        return _result(RECOUNT, ITEM_RECOUNTED, None, qty, matched, OUTCOME_RECOUNT_RECORDED)

    # Already counted (Counted / Manager Review / Recounted / Approved).
    if policy != POLICY_ALLOW:
        raise AlreadyCountedError("this item has already been counted", counted_by=counted_by)
    return _result(RECOUNT, ITEM_COUNTED if matched else ITEM_MANAGER_REVIEW, None, qty, matched, OUTCOME_ADDITIONAL_COUNT)


def _result(count_type, status, next_count_type, final_qty, matched, outcome) -> dict:
    return {
        "count_type": count_type,
        "status": status,
        "next_count_type": next_count_type,
        "final_qty": final_qty,
        "matched": bool(matched),
        "outcome": outcome,
    }


def differences(final_qty, erp_qty, valuation_rate) -> tuple[float, float]:
    """(quantity difference, value difference) of a counted row; zeros while no final quantity."""
    if final_qty in (None, ""):
        return 0.0, 0.0
    diff = _num(final_qty) - _num(erp_qty)
    return diff, diff * _num(valuation_rate)


# ---- manager actions ---------------------------------------------------------------------------


def can_request_recount(item_status) -> bool:
    return item_status in RECOUNTABLE_STATUSES


def can_accept(item_status) -> bool:
    """The manager accepts the latest count of a row that has one and is not finalized."""
    return item_status in (ITEM_COUNTED, ITEM_MANAGER_REVIEW, ITEM_RECOUNTED, ITEM_APPROVED)


def session_status_after_count(session_status, recount_required_remaining) -> str:
    """A Recount session returns to Manager Review once the last requested recount is in."""
    if session_status == SESSION_RECOUNT and int(recount_required_remaining or 0) == 0:
        return SESSION_MANAGER_REVIEW
    return session_status


def session_status_after_recount_request(session_status) -> str:
    if session_status in (SESSION_MANAGER_REVIEW, SESSION_FINAL_APPROVAL):
        return SESSION_RECOUNT
    return session_status


def complete_counting_status(totals) -> str:
    """Counting can be closed only when nothing is uncounted; then the manager reviews the
    differences (Manager Review) or, when there are none left, approves (Final Approval)."""
    uncounted = int(totals.get("uncounted_items") or 0)
    if uncounted > 0:
        raise StocktakingRuleError(f"{uncounted} item(s) are still not counted")
    if int(totals.get("recount_required") or 0) > 0:
        raise StocktakingRuleError(f"{totals.get('recount_required')} item(s) still need a recount")
    if int(totals.get("pending_review") or 0) > 0:
        return SESSION_MANAGER_REVIEW
    return SESSION_FINAL_APPROVAL


def ready_for_approval(session_status, totals) -> None:
    """Raises unless every row is counted, recounted where requested and reviewed."""
    if session_status not in (SESSION_MANAGER_REVIEW, SESSION_RECOUNT, SESSION_FINAL_APPROVAL, SESSION_COUNTING):
        raise StocktakingRuleError(f"a session that is {session_status} cannot be approved")
    problems = []
    if int(totals.get("uncounted_items") or 0):
        problems.append(f"{totals['uncounted_items']} not counted")
    if int(totals.get("recount_required") or 0):
        problems.append(f"{totals['recount_required']} waiting for a recount")
    if int(totals.get("pending_review") or 0):
        problems.append(f"{totals['pending_review']} waiting for manager review")
    if problems:
        raise StocktakingRuleError("cannot approve: " + ", ".join(problems))


def can_reconcile(session_status) -> bool:
    return session_status == SESSION_FINAL_APPROVAL


def can_cancel(session_status, reconciliation_docstatus=None) -> None:
    if session_status in CLOSED_SESSION_STATUSES:
        raise StocktakingRuleError(f"a {session_status} session cannot be cancelled")
    if reconciliation_docstatus == 1:
        raise StocktakingRuleError("cancel the submitted Stock Reconciliation first")


# ---- aggregation ----------------------------------------------------------------------------------


def progress(rows) -> dict:
    """Totals of a session from its rows (dicts with status, qty_difference, value_difference)."""
    rows = list(rows)
    statuses = [row.get("status") or ITEM_NOT_COUNTED for row in rows]
    counted = [row for row in rows if (row.get("status") or ITEM_NOT_COUNTED) in COUNTED_STATUSES]
    variance = [row for row in counted if abs(_num(row.get("qty_difference"))) > QTY_TOLERANCE]
    return {
        "total_items": len(rows),
        "counted_items": len(counted),
        "uncounted_items": sum(1 for s in statuses if s in UNCOUNTED_STATUSES),
        "matched_items": len(counted) - len(variance),
        "variance_items": len(variance),
        "recount_required": sum(1 for s in statuses if s == ITEM_RECOUNT_REQUIRED),
        "pending_review": sum(1 for s in statuses if s in PENDING_REVIEW_STATUSES),
        "approved_items": sum(1 for s in statuses if s in (ITEM_APPROVED, ITEM_FINALIZED)),
        "qty_variance": sum(_num(row.get("qty_difference")) for row in variance),
        "value_variance": sum(_num(row.get("value_difference")) for row in variance),
    }


def reconciliation_rows(rows, tolerance=0.0) -> list:
    """Rows whose final quantity differs from ERPNext: what the Stock Reconciliation must carry."""
    out = []
    for row in rows:
        status = row.get("status") or ITEM_NOT_COUNTED
        if status not in (ITEM_COUNTED, ITEM_APPROVED):
            if status in (ITEM_MANAGER_REVIEW, ITEM_RECOUNTED):
                raise StocktakingRuleError(f"{row.get('item_code')} is still waiting for manager review")
            if status in UNCOUNTED_STATUSES or status == ITEM_RECOUNT_REQUIRED:
                raise StocktakingRuleError(f"{row.get('item_code')} is not counted")
            continue
        final = row.get("final_qty")
        if final in (None, ""):
            continue
        if is_matched(final, row.get("erp_qty"), tolerance):
            continue
        out.append(row)
    return out


# ---- scans ---------------------------------------------------------------------------------------


def resolve_scan(code, item_key=DEFAULT_QR_ITEM_KEY, batch_key=DEFAULT_QR_BATCH_KEY) -> dict:
    """A JSON QR label gives item + batch; anything else is a plain barcode / code to look up."""
    if isinstance(code, (bytes, bytearray)):
        code = code.decode("utf-8", errors="replace")
    text = (code or "").strip().strip("\x1d")
    if not text:
        raise StocktakingRuleError("empty scan")
    if text.startswith("{"):
        try:
            data = json.loads(text)
        except ValueError:
            data = None
        if isinstance(data, dict):
            item_code = data.get(item_key)
            if item_code not in (None, "") and str(item_code).strip():
                batch = data.get(batch_key)
                return {
                    "kind": "qr",
                    "item_code": str(item_code).strip(),
                    "batch_no": None if batch in (None, "") else str(batch).strip(),
                    "raw": text,
                }
    return {"kind": "barcode", "item_code": None, "batch_no": None, "raw": text}


def match_rows(rows, item_code, batch_no=None, warehouse=None) -> list:
    """Session rows for an item (and batch / warehouse when given), case-insensitively."""
    wanted_item = (item_code or "").strip().lower()
    wanted_batch = (batch_no or "").strip().lower()
    wanted_wh = (warehouse or "").strip().lower()
    out = []
    for row in rows:
        if (row.get("item_code") or "").strip().lower() != wanted_item:
            continue
        if wanted_batch and (row.get("batch_no") or "").strip().lower() != wanted_batch:
            continue
        if wanted_wh and (row.get("warehouse") or "").strip().lower() != wanted_wh:
            continue
        out.append(row)
    return out


def snapshot_key(item_code, warehouse, batch_no=None) -> tuple:
    return ((item_code or "").strip(), (warehouse or "").strip(), (batch_no or "").strip())
