"""Whitelisted API used by the WMS ERP Android app for row-level physical picking.

Endpoints are called as `/api/method/wmserp_picking.api.pick_list.<function>` (POST with a JSON
body; the read-only ones also accept GET). They only write the WMS custom fields on Pick List /
Pick List Item and create *draft* standard documents through the regular Document API, so stock
ledger and GL entries are always produced by ERPNext itself when those drafts are submitted.

Model
-----
* Assignment is per row: `Pick List Item.custom_picker`. Several pickers share one card.
* Target: a row's required quantity is its *Picked Qty (in Stock UOM)* (`picked_qty`), which the
  Senior Warehouse User sets on the Pick List (ERPNext fills it with the stock quantity on submit
  when left empty). The app never writes `picked_qty`; what was picked lives in
  `custom_wms_picked_qty`.
* Row lifecycle: Not Picked --start_row--> Picking --save_row_progress*--> --complete_row--> Picked
  (a row completes when picked qty == required qty; timestamps and duration are stamped).
* Several batches: a batch tracked row the Pick List left without a batch is picked from any
  number of batches (`batches=[{batch_no, qty}]`, kept in `custom_wms_picked_batches`). Its final
  confirmation records them in the row's Serial and Batch Bundle, which ERPNext's stock reservation
  reads, and the Delivery Note / Stock Entry gets one row per batch.
* Stock reservation: when the last row of a *Delivery* card is confirmed and *Stock Settings >
  Enable Stock Reservation* is on, the picked quantities are reserved for the Sales Orders through
  ERPNext's own Pick List reservation (per batch where the row has a bundle). A reset cancels the
  row's reservation.
  `save_row_progress(hold=1)` keeps a row *Picking* even at the required quantity, so the app's
  draft -> picking in progress -> final confirmation flow leaves quantities and batches editable
  until `complete_row`; `reset_row` clears a row (and a batch the app assigned) before a document
  exists.
* Stock checks: a scanned batch must belong to the row's item, be live and hold the picked
  quantity in the row's warehouse (`InsufficientStockError`); `generate_document` re-checks every
  picked row before creating the Delivery Note / Stock Entry, so a draft that cannot be submitted
  is never created.
* Card lifecycle (header `custom_picking_status`): Ready to Pick -> Picking (first row started)
  -> Picked (every row picked; checked after each row completion under a row lock, so exactly one
  request observes the transition and is told `is_last_picker: true`).
* `generate_document` creates the draft Delivery Note / Stock Entry once the card is Picked.
"""

import datetime as dt
import json
from contextlib import contextmanager

import frappe
from frappe import _
from frappe.utils import cint, flt, get_datetime, getdate, now_datetime, nowdate

from wmserp_picking import __version__, documents
from wmserp_picking.picking import rules
from wmserp_picking.picking.pick_list_events import add_assignment, remove_assignment
from wmserp_picking.wms_erp_picking.doctype.wms_settings.wms_settings import get_qr_keys

PICK_LIST = "Pick List"
PICK_LIST_ITEM = "Pick List Item"

HEADER_FIELDS = [
    "name",
    "purpose",
    "company",
    "customer",
    "customer_name",
    "parent_warehouse",
    "status",
    "work_order",
    "material_request",
    "docstatus",
    "modified",
    "creation",
    "owner",
    "custom_picking_status",
    "custom_target_warehouse",
    "custom_card_started_at",
    "custom_card_completed_at",
    "custom_generated_doctype",
    "custom_generated_docname",
]
ALWAYS_PRESENT = {"name", "docstatus", "modified", "creation", "owner"}

ROW_FIELDS = [
    "name",
    "idx",
    "parent",
    "item_code",
    "item_name",
    "description",
    "warehouse",
    "batch_no",
    "serial_no",
    "qty",
    "stock_qty",
    "uom",
    "stock_uom",
    "conversion_factor",
    "picked_qty",
    "sales_order",
    "sales_order_item",
    "material_request",
    "material_request_item",
    "product_bundle_item",
    "custom_picker",
    "custom_row_status",
    "custom_wms_picked_qty",
    "custom_wms_picked_batches",
    "serial_and_batch_bundle",
    "stock_reserved_qty",
    "custom_row_started_at",
    "custom_row_completed_at",
    "custom_picking_duration_seconds",
    "custom_batch_from_wms",
]


class InsufficientStockError(frappe.ValidationError):
    """The picked quantity is not available in the row's warehouse (batch or item)."""


# --------------------------------------------------------------------------------------------
# Reads
# --------------------------------------------------------------------------------------------


@frappe.whitelist()
def get_settings():
    """JSON keys of the QR labels (from WMS Settings) plus the app version."""
    keys = get_qr_keys()
    return {
        "qr_item_key": keys["qr_item_key"],
        "qr_batch_key": keys["qr_batch_key"],
        "app_version": __version__,
        # What this backend offers beyond picking, so the app can tell an old backend from a broken one.
        "features": [
            "stocktaking",
            "purchase_receipt_receiving",
            "label_sheets",
            "required_field_values",
            "batch_receiving",
            "followup_receipts",
            "warehouse_stamps",
            "material_request_pick_lists",
            "picked_qty_target",
            "multi_batch_picking",
            "pick_list_stock_reservation",
            "stock_transfer_api",
            "warehouse_qr_labels",
        ],
    }


@frappe.whitelist()
def get_my_pick_lists(limit=50):
    """Submitted, Open Pick Lists with at least one row assigned to the current user."""
    user = frappe.session.user
    parents = frappe.get_all(
        PICK_LIST_ITEM,
        filters={"parenttype": PICK_LIST, "docstatus": 1, "custom_picker": user},
        pluck="parent",
        distinct=True,
    )
    if not parents:
        return []
    filters = {"name": ["in", list(set(parents))], "docstatus": 1, "status": "Open"}
    headers = frappe.get_list(
        PICK_LIST,
        filters=filters,
        fields=header_fields(),
        order_by="modified desc",
        limit_page_length=cint(limit) or 50,
    )
    rows_by_parent = child_rows([header.name for header in headers])
    return [serialize_header(header, rows_by_parent.get(header.name, []), user) for header in headers]


@frappe.whitelist()
def get_pick_list(name):
    doc = frappe.get_doc(PICK_LIST, name)
    doc.check_permission("read")
    return serialize_pick_list(doc, frappe.session.user)


@frappe.whitelist()
def get_picker_kpis(date=None, user=None):
    """Today's (or `date`'s) picking statistics for the current user (supervisors may pass `user`)."""
    session_user = frappe.session.user
    user = user or session_user
    if user != session_user and not rules.is_supervisor(frappe.get_roles(session_user)):
        frappe.throw(_("You can only see your own picking statistics."), frappe.PermissionError)
    day = getdate(date) if date else getdate(nowdate())
    start, end = f"{day} 00:00:00", f"{day} 23:59:59.999999"

    picked_rows = frappe.get_all(
        PICK_LIST_ITEM,
        filters={
            "parenttype": PICK_LIST,
            "docstatus": 1,
            "custom_picker": user,
            "custom_row_status": rules.ROW_PICKED,
            "custom_row_completed_at": ["between", [start, end]],
        },
        fields=["parent", "custom_wms_picked_qty as picked_qty", "custom_picking_duration_seconds as duration_seconds"],
    )
    summary = rules.kpi_summary(picked_rows)

    touched = {row.parent for row in picked_rows}
    completed_cards = 0
    if touched:
        completed_cards = frappe.db.count(
            PICK_LIST,
            {"name": ["in", list(touched)], "custom_picking_status": rules.STATUS_PICKED, "custom_card_completed_at": ["between", [start, end]]},
        )

    open_rows = frappe.get_all(
        PICK_LIST_ITEM,
        filters={"parenttype": PICK_LIST, "docstatus": 1, "custom_picker": user, "custom_row_status": ["!=", rules.ROW_PICKED]},
        fields=["parent"],
    )
    open_parents = {row.parent for row in open_rows}
    if open_parents:
        open_parents = set(frappe.get_all(PICK_LIST, filters={"name": ["in", list(open_parents)], "status": "Open"}, pluck="name"))
        open_rows = [row for row in open_rows if row.parent in open_parents]

    summary.update(
        {
            "date": str(day),
            "user": user,
            "pick_lists_completed": completed_cards,
            "open_rows": len(open_rows),
            "open_pick_lists": len(open_parents),
        }
    )
    return summary


# --------------------------------------------------------------------------------------------
# Row operations
# --------------------------------------------------------------------------------------------


@frappe.whitelist()
def start_row(name, row):
    """Not Picked -> Picking for one row; stamps the row start and, if empty, the card start."""
    doc, child = load_row(name, row)
    if child.get("custom_row_status") == rules.ROW_PICKED:
        frappe.throw(_("Row {0} ({1}) is already picked.").format(child.idx, child.item_code))
    now = now_datetime()
    updates = {}
    if child.get("custom_row_status") != rules.ROW_PICKING:
        updates["custom_row_status"] = rules.ROW_PICKING
    if not child.get("custom_row_started_at"):
        updates["custom_row_started_at"] = now
    if updates:
        child.db_set(updates, update_modified=False)
    stamp_card_started(doc, now)
    return row_result(doc.name, child.name, row_completed=False, card_completed=False)


@frappe.whitelist()
def save_row_progress(name, row, picked_qty=None, item_code=None, batch_no=None, elapsed_seconds=None, hold=0, batches=None):
    """Stores a partial (or full) picked quantity for one row. Completes the row when it reaches
    the required quantity, then runs the card completion check - unless `hold` is set, in which
    case the row stays *Picking* (editable) until `complete_row`.

    `batches` (`[{"batch_no": ..., "qty": ...}]`, the row's complete list) is how a row without an
    allocated batch is picked from several batches; the picked quantity is their sum."""
    return update_row(name, row, picked_qty, item_code, batch_no, elapsed_seconds, require_complete=False, hold=bool(cint(hold)), batches=batches)


@frappe.whitelist()
def reset_row(name, row):
    """Clears what was picked on one row (quantity, timestamps and a batch the app assigned) so
    the picker can scan it again. Refused once the card's document exists; a *Picked* card
    returns to *Picking*."""
    doc, child = load_row(name, row)
    existing = find_generated_document(doc)
    if existing:
        frappe.throw(_("Row {0} ({1}) can no longer be changed: {2} {3} was already created from Pick List {4}.").format(child.idx, child.item_code, _(existing["doctype"]), existing["name"], doc.name))
    # `picked_qty` is the Senior Warehouse User's target and stays untouched.
    updates = {
        "custom_wms_picked_qty": 0,
        "custom_row_status": rules.ROW_NOT_PICKED,
        "custom_row_completed_at": None,
        "custom_picking_duration_seconds": 0,
    }
    cancel_row_reservation(doc, child)
    if cint(child.get("custom_batch_from_wms")):
        updates.update({"batch_no": None, "custom_batch_from_wms": 0, "custom_wms_picked_batches": None})
        if child.get("serial_and_batch_bundle"):
            updates["serial_and_batch_bundle"] = None
    bundle = child.get("serial_and_batch_bundle") if cint(child.get("custom_batch_from_wms")) else None
    child.db_set(updates, update_modified=False)
    if bundle:
        discard_row_bundle(bundle)
    if current_status(doc) == rules.STATUS_PICKED:
        doc.db_set({"custom_picking_status": rules.STATUS_PICKING, "custom_card_completed_at": None}, notify=True)
    else:
        frappe.db.set_value(PICK_LIST, doc.name, "modified", now_datetime(), update_modified=False)
    doc.add_comment("Info", _("Row {0} ({1}) was reset by {2} from the WMS app.").format(child.idx, child.item_code, frappe.utils.get_fullname(frappe.session.user)))
    return row_result(doc.name, child.name, row_completed=False, card_completed=False)


@frappe.whitelist()
def complete_row(name, row, picked_qty=None, item_code=None, batch_no=None, elapsed_seconds=None, batches=None):
    """Marks one row as Picked (picked qty must equal the required qty) and runs the card
    completion check; the response says whether this call completed the whole card. A row picked
    from several batches gets its Serial and Batch Bundle here; the call that completes a
    *Delivery* card also reserves the stock (`reservation` in the response)."""
    return update_row(name, row, picked_qty, item_code, batch_no, elapsed_seconds, require_complete=True, batches=batches)


@frappe.whitelist()
def assign_rows(name, user, rows=None):
    """Supervisor action: assigns rows (all rows when `rows` is empty) to a picker."""
    doc = frappe.get_doc(PICK_LIST, name)
    doc.check_permission("write")
    if not frappe.db.exists("User", user):
        frappe.throw(_("User {0} does not exist.").format(user))
    wanted = set(frappe.parse_json(rows) or []) if rows else None
    previous = {r.get("custom_picker") for r in doc.get("locations") or [] if r.get("custom_picker")}
    for child in doc.get("locations") or []:
        if wanted is not None and child.name not in wanted:
            continue
        if child.get("custom_row_status") == rules.ROW_PICKED:
            continue
        child.db_set({"custom_picker": user}, update_modified=False)
    doc = reload(doc)
    current = {r.get("custom_picker") for r in doc.get("locations") or [] if r.get("custom_picker")}
    for dropped in previous - current:
        remove_assignment(doc, dropped)
    add_assignment(doc, user)
    return serialize_pick_list(doc, frappe.session.user)


@frappe.whitelist()
def generate_document(name, values=None):
    """Creates the draft Delivery Note / Stock Entry for a picked card, exactly once.

    `values` answers required fields the site added (`{"Delivery Note Item.department": "..."}`).
    When required fields are still empty the answer is `{"missing_fields": [...], "created": false}`
    (HTTP 200) and nothing is created; the app asks the user and calls again with `values`.
    """
    doc = load_for_card(name)
    if current_status(doc) != rules.STATUS_PICKED:
        frappe.throw(_("All rows of Pick List {0} must be picked before creating a document.").format(name))
    values = frappe.parse_json(values) or {}

    # Row lock: two devices pressing the button at the same time serialize here, and the second
    # one sees the document created by the first.
    frappe.db.get_value(PICK_LIST, name, "name", for_update=True)
    doc = reload(doc)

    existing = find_generated_document(doc)
    if existing:
        existing["already_generated"] = True
        existing["created"] = True
        existing["documents"] = [{"doctype": existing["doctype"], "name": existing["name"]}]
        return existing

    ensure_stock_for(doc)
    doctype, stock_entry_purpose = target_for(doc)
    try:
        if doctype == rules.DELIVERY_NOTE:
            created = make_delivery_notes(doc, values)
        else:
            created = [make_stock_entry(doc, stock_entry_purpose, values)]
    except documents.MissingRequiredFields as exc:
        frappe.db.rollback()
        frappe.clear_messages()
        return exc.response()
    except frappe.MandatoryError as exc:
        answer = documents.missing_fields_answer(doctype, exc)
        if answer:
            return answer
        raise

    primary = created[0]
    doc.db_set({"custom_generated_doctype": primary.doctype, "custom_generated_docname": primary.name}, notify=True)
    doc.add_comment("Info", _("{0} {1} created from WMS picking").format(_(primary.doctype), primary.name))
    return {
        "doctype": primary.doctype,
        "name": primary.name,
        "docstatus": primary.docstatus,
        "already_generated": False,
        "created": True,
        "documents": [{"doctype": d.doctype, "name": d.name} for d in created],
    }


# --------------------------------------------------------------------------------------------
# Row update core
# --------------------------------------------------------------------------------------------


def update_row(name, row, picked_qty, item_code, batch_no, elapsed_seconds, require_complete, hold=False, batches=None):
    doc, child = load_row(name, row)
    required = required_qty(child)

    if item_code and item_code.strip().lower() != (child.item_code or "").lower():
        frappe.throw(_("Wrong item. Expected: {0}, scanned: {1}").format(child.item_code, item_code))

    picked_batches = None
    if takes_several_batches(child):
        picked_batches = requested_batches(child, picked_qty, batch_no, batches)
        picked = rules.batches_total(picked_batches)
    else:
        picked = flt(picked_qty) if picked_qty not in (None, "") else flt(child.get("custom_wms_picked_qty"))
    try:
        picked = rules.validate_picked_qty(picked, required, child.item_code)
    except rules.PickingRuleError as exc:
        frappe.throw(str(exc))

    updates = {"custom_wms_picked_qty": picked}
    if picked_batches is not None:
        for entry in picked_batches:
            check_stock(child, entry["batch_no"], entry["qty"])
        updates.update(
            {
                "custom_wms_picked_batches": json.dumps(picked_batches) if picked_batches else None,
                "custom_batch_from_wms": 1 if picked_batches else 0,
                # The batches live in the list; a single batch remembered by an earlier version goes.
                "batch_no": None,
            }
        )
    else:
        effective_batch = child.get("batch_no")
        if batch_no:
            batch_updates = validate_scanned_batch(child, batch_no.strip())
            updates.update(batch_updates)
            effective_batch = batch_updates.get("batch_no") or effective_batch
        check_stock(child, effective_batch, picked)

    complete = rules.is_row_complete(picked, required)
    if require_complete and not complete:
        frappe.throw(_("Row {0} ({1}) is not complete: {2} of {3} picked.").format(child.idx, child.item_code, flt(picked), flt(required)))
    if child.get("custom_row_status") == rules.ROW_PICKED:
        if not complete:
            frappe.throw(_("Row {0} ({1}) is already picked and cannot be reduced. Reset the row first.").format(child.idx, child.item_code))
        if picked_batches is not None and not same_batches(picked_batches, row_batches(child)):
            frappe.throw(_("Row {0} ({1}) is already picked; reset the row to pick other batches.").format(child.idx, child.item_code))

    now = now_datetime()
    started = child.get("custom_row_started_at")
    if started:
        started = get_datetime(started)
    else:
        started = started_from_elapsed(now, elapsed_seconds)
        updates["custom_row_started_at"] = started

    row_completed = False
    if complete and not hold and child.get("custom_row_status") != rules.ROW_PICKED:
        updates.update(
            {
                "custom_row_status": rules.ROW_PICKED,
                "custom_row_completed_at": now,
                "custom_picking_duration_seconds": rules.duration_seconds(started, now, elapsed_seconds),
            }
        )
        if picked_batches:
            # The batches go into the row's Serial and Batch Bundle: ERPNext's stock reservation
            # (and its own Create > Delivery Note) read them from there.
            updates["serial_and_batch_bundle"] = make_row_bundle(doc, child, picked_batches)
        row_completed = True
    elif child.get("custom_row_status") != rules.ROW_PICKED:
        updates["custom_row_status"] = rules.next_row_status(picked, required, child.get("custom_row_status"), hold=hold)

    child.db_set(updates, update_modified=False)
    stamp_card_started(doc, started)

    card_completed = False
    reservation = None
    if row_completed:
        card_completed = run_card_completion_check(doc.name)
        if card_completed:
            doc.add_comment("Info", _("All rows picked. Last row completed by {0}.").format(frappe.utils.get_fullname(frappe.session.user)))
            reservation = reserve_stock(doc.name)
    else:
        frappe.db.set_value(PICK_LIST, doc.name, "modified", now, update_modified=False)
    return row_result(doc.name, child.name, row_completed=row_completed, card_completed=card_completed, reservation=reservation)


def stamp_card_started(doc, when):
    header = {}
    if not doc.get("custom_card_started_at"):
        header["custom_card_started_at"] = when
    if current_status(doc) == rules.STATUS_READY:
        header["custom_picking_status"] = rules.STATUS_PICKING
    if header:
        doc.db_set(header, notify=True)


def run_card_completion_check(name) -> bool:
    """Picked when every row is picked. The row lock makes the transition happen exactly once,
    so the request that flips it knows it was the last picker."""
    frappe.db.get_value(PICK_LIST, name, "name", for_update=True)
    statuses = frappe.get_all(PICK_LIST_ITEM, filters={"parent": name, "parenttype": PICK_LIST}, pluck="custom_row_status")
    if not rules.card_complete(statuses):
        return False
    if frappe.db.get_value(PICK_LIST, name, "custom_picking_status") == rules.STATUS_PICKED:
        return False
    frappe.db.set_value(
        PICK_LIST,
        name,
        {"custom_picking_status": rules.STATUS_PICKED, "custom_card_completed_at": now_datetime()},
    )
    return True


def started_from_elapsed(now, elapsed_seconds):
    elapsed = flt(elapsed_seconds) if elapsed_seconds not in (None, "") else 0.0
    return now - dt.timedelta(seconds=max(0.0, elapsed)) if elapsed > 0 else now


def validate_scanned_batch(child, batch_no) -> dict:
    """The scanned batch must be the one ERPNext allocated to the row. Batch tracked rows without
    an allocated batch never get here (they take several batches, see `requested_batches`); a row
    of an item without batches accepts any valid, unexpired batch of the item and remembers it."""
    expected = (child.get("batch_no") or "").strip()
    if expected and batch_no.lower() == expected.lower():
        return {}
    replaceable = cint(child.get("custom_batch_from_wms")) and flt(child.get("custom_wms_picked_qty")) <= rules.QTY_TOLERANCE
    if expected and not replaceable:
        frappe.throw(_("Wrong batch. Expected: {0}, scanned: {1}").format(expected, batch_no))
    batch = frappe.db.get_value("Batch", batch_no, ["item", "expiry_date", "disabled"], as_dict=True)
    if not batch:
        frappe.throw(_("Batch {0} does not exist.").format(batch_no))
    if batch.item != child.item_code:
        frappe.throw(_("Batch {0} belongs to item {1}, not {2}.").format(batch_no, batch.item, child.item_code))
    if cint(batch.disabled):
        frappe.throw(_("Batch {0} is disabled.").format(batch_no))
    if batch.expiry_date and getdate(batch.expiry_date) < getdate(nowdate()):
        frappe.throw(_("Batch {0} expired on {1}.").format(batch_no, frappe.format(batch.expiry_date, "Date")))
    return {"batch_no": batch_no, "custom_batch_from_wms": 1}


# --------------------------------------------------------------------------------------------
# Several batches per row
# --------------------------------------------------------------------------------------------


def item_has_batch(item_code) -> bool:
    return bool(item_code) and bool(cint(frappe.get_cached_value("Item", item_code, "has_batch_no")))


def has_foreign_bundle(row) -> bool:
    """A Serial and Batch Bundle on the row that the app did not make (filled in on the desk)."""
    return bool(row.get("serial_and_batch_bundle")) and not cint(row.get("custom_batch_from_wms"))


def takes_several_batches(row, has_batch_no=None) -> bool:
    if has_batch_no is None:
        has_batch_no = item_has_batch(row.item_code)
    return rules.accepts_several_batches(has_batch_no, row.get("batch_no"), row.get("custom_batch_from_wms"), has_foreign_bundle(row))


def row_batches(row) -> list[dict]:
    """The batches picked so far on a row without an allocated batch. A row picked by a version
    before multi-batch picking kept its one scanned batch in `batch_no`."""
    try:
        batches = rules.parse_batches(row.get("custom_wms_picked_batches"))
    except rules.PickingRuleError:
        batches = []
    picked = flt(row.get("custom_wms_picked_qty"))
    if not batches and cint(row.get("custom_batch_from_wms")) and row.get("batch_no") and picked > rules.QTY_TOLERANCE:
        batches = [{"batch_no": row.batch_no, "qty": picked}]
    return batches


def same_batches(left, right) -> bool:
    def key(batches):
        return sorted((entry["batch_no"].lower(), round(flt(entry["qty"]), 6)) for entry in batches)

    return key(left) == key(right)


def requested_batches(child, picked_qty, batch_no, batches) -> list[dict]:
    """The row's batch list after this call, every batch checked (item, disabled, expiry) and
    named as ERPNext names it. `batches` is the full list; without it the call names at most one
    batch, as the app did before multi-batch picking."""
    try:
        if batches not in (None, ""):
            wanted = rules.parse_batches(batches)
        elif picked_qty in (None, "") and not batch_no:
            wanted = row_batches(child)
        else:
            wanted = rules.single_batch_update(row_batches(child), batch_no, picked_qty)
    except rules.PickingRuleError as exc:
        frappe.throw(_("Row {0} ({1}): {2}").format(child.idx, child.item_code, str(exc)))
    checked = {}
    for entry in wanted:
        name = check_batch(child, entry["batch_no"])
        if name.lower() in checked:
            checked[name.lower()]["qty"] += flt(entry["qty"])
        else:
            checked[name.lower()] = {"batch_no": name, "qty": flt(entry["qty"])}
    return list(checked.values())


def check_batch(child, batch_no) -> str:
    """A batch picked on a row without an allocated batch: it must exist, belong to the row's
    item, be enabled and not expired. Returns the Batch name."""
    batch = frappe.db.get_value("Batch", batch_no, ["name", "item", "expiry_date", "disabled"], as_dict=True)
    if not batch:
        frappe.throw(_("Batch {0} does not exist.").format(batch_no))
    if batch.item != child.item_code:
        frappe.throw(_("Batch {0} belongs to item {1}, not {2}.").format(batch_no, batch.item, child.item_code))
    if cint(batch.disabled):
        frappe.throw(_("Batch {0} is disabled.").format(batch_no))
    if batch.expiry_date and getdate(batch.expiry_date) < getdate(nowdate()):
        frappe.throw(_("Batch {0} expired on {1}.").format(batch_no, frappe.format(batch.expiry_date, "Date")))
    return batch.name


@contextmanager
def as_administrator():
    """Runs ERPNext's own stock steps (Serial and Batch Bundle, Stock Reservation Entries) on behalf
    of the picking process: the warehouse role may lack their permissions.

    `frappe.set_user` rewrites the request's session in place (sid, session data) and clears
    `form_dict`; Frappe saves that session at the end of the request, so both are put back exactly
    as they were, not just the user."""
    user = frappe.session.user
    if user == "Administrator":
        yield
        return
    session = frappe.local.session
    saved_session = dict(session)
    saved_form_dict = frappe.local.form_dict
    frappe.set_user("Administrator")
    try:
        yield
    finally:
        frappe.set_user(user)
        session.update(saved_session)
        frappe.local.session = session
        frappe.local.form_dict = saved_form_dict


def make_row_bundle(doc, child, batches) -> str:
    """An outward Serial and Batch Bundle for the Pick List row holding the picked batches, as
    ERPNext makes one on submit for a row with a batch (`make_bundle_using_old_serial_batch_fields`)."""
    from erpnext.stock.serial_batch_bundle import SerialBatchCreation

    previous = child.get("serial_and_batch_bundle") if cint(child.get("custom_batch_from_wms")) else None
    with as_administrator():
        bundle = SerialBatchCreation(
            {
                "item_code": child.item_code,
                "warehouse": child.get("warehouse"),
                "voucher_type": PICK_LIST,
                "voucher_no": doc.name,
                "voucher_detail_no": child.name,
                "qty": rules.batches_total(batches),
                "type_of_transaction": "Outward",
                "company": doc.company,
                "batches": frappe._dict({entry["batch_no"]: flt(entry["qty"]) for entry in batches}),
            }
        ).make_serial_and_batch_bundle()
    if previous:
        discard_row_bundle(previous)
    return bundle.name


def discard_row_bundle(name):
    """Cancels a bundle the app made for a row (the way ERPNext does when a Pick List is cancelled)."""
    with as_administrator():
        if frappe.db.get_value("Serial and Batch Bundle", name, "docstatus") == 1:
            frappe.db.set_value("Serial and Batch Bundle", name, {"is_cancelled": 1, "voucher_no": ""})
            frappe.get_doc("Serial and Batch Bundle", name).cancel()


# --------------------------------------------------------------------------------------------
# Stock reservation
# --------------------------------------------------------------------------------------------


def reservation_enabled() -> bool:
    return bool(cint(frappe.db.get_single_value("Stock Settings", "enable_stock_reservation")))


def reserved_entries(name) -> int:
    return frappe.db.count("Stock Reservation Entry", {"from_voucher_type": PICK_LIST, "from_voucher_no": name, "docstatus": 1})


def reserve_stock(name):
    """Reserves the picked quantities of a *Delivery* card for its Sales Orders through ERPNext's
    Pick List reservation (each row's Picked Qty, per batch where the row has a bundle). Sales Order
    rows without *Reserve Stock* are skipped by ERPNext. A failure never undoes the picking: it is
    logged, noted on the Pick List and returned, and the desk's *Reserve* button can retry."""
    if not reservation_enabled():
        return None
    doc = frappe.get_doc(PICK_LIST, name)
    if doc.get("purpose") != rules.PURPOSE_DELIVERY or not doc.has_unreserved_stock():
        return None
    before = reserved_entries(name)
    frappe.db.savepoint("wms_reserve_stock")
    try:
        with as_administrator():
            doc.create_stock_reservation_entries(notify=False)
    except Exception as exc:
        frappe.db.rollback(save_point="wms_reserve_stock")
        frappe.clear_messages()
        frappe.log_error(title=f"WMS picking: stock reservation failed for {name}", message=frappe.get_traceback())
        message = frappe.utils.strip_html(str(exc)) or exc.__class__.__name__
        doc.add_comment("Info", _("Stock could not be reserved after WMS picking: {0}").format(message))
        return {"reserved": False, "entries": 0, "error": message}
    created = reserved_entries(name) - before
    if created > 0:
        doc.add_comment("Info", _("Stock reserved for the picked quantities from the WMS app ({0} reservation entries).").format(created))
    return {"reserved": created > 0, "entries": created, "error": None}


def cancel_row_reservation(doc, child):
    """A reset row gives its reservation back; the card's next completion reserves again."""
    if flt(child.get("stock_reserved_qty")) <= rules.QTY_TOLERANCE:
        return
    from erpnext.stock.doctype.stock_reservation_entry.stock_reservation_entry import cancel_stock_reservation_entries

    with as_administrator():
        cancel_stock_reservation_entries(from_voucher_type=PICK_LIST, from_voucher_no=doc.name, from_voucher_detail_no=child.name, notify=False)


def available_qty(item_code, warehouse, batch_no=None):
    """What the warehouse holds right now: the batch balance (ERPNext's `get_batch_qty`, bundles
    and ledger) or, for items without batches, the Bin's actual quantity. None without a warehouse."""
    if not warehouse or not item_code:
        return None
    if batch_no:
        from erpnext.stock.doctype.batch.batch import get_batch_qty

        return flt(get_batch_qty(batch_no=batch_no, warehouse=warehouse, item_code=item_code))
    return flt(frappe.db.get_value("Bin", {"item_code": item_code, "warehouse": warehouse}, "actual_qty"))


def check_stock(child, batch_no, picked):
    """Refuses a picked quantity the row's warehouse does not hold (for the batch when there is one)."""
    picked = flt(picked)
    if picked <= rules.QTY_TOLERANCE:
        return
    available = available_qty(child.item_code, child.get("warehouse"), batch_no)
    short = rules.stock_shortfalls([{"picked": picked, "available": available}])
    if not short:
        return
    if batch_no:
        message = _("Batch {0} of {1} has only {2} in {3}, but {4} would be picked. Choose a batch with stock in this warehouse.").format(
            batch_no, child.item_code, flt(available), child.get("warehouse"), picked
        )
    else:
        message = _("{0} has only {1} in {2}, but {3} would be picked.").format(child.item_code, flt(available), child.get("warehouse"), picked)
    frappe.throw(message, InsufficientStockError)


def ensure_stock_for(doc):
    """Final gate before a document is created: every picked row must still be covered by the
    warehouse's stock, so the draft can be submitted later."""
    rows = []
    for row in doc.get("locations") or []:
        if flt(row.get("custom_wms_picked_qty")) <= rules.QTY_TOLERANCE or row.get("product_bundle_item"):
            continue
        for batch_no, picked in picked_parts(row):
            rows.append(
                {
                    "idx": row.idx,
                    "item_code": row.item_code,
                    "batch_no": batch_no,
                    "warehouse": row.get("warehouse"),
                    "picked": picked,
                    "available": available_qty(row.item_code, row.get("warehouse"), batch_no),
                }
            )
    short = rules.stock_shortfalls(rows)
    if not short:
        return
    lines = [
        _("Row {0}: {1}{2} has {3} in {4}, {5} picked").format(
            r["idx"], r["item_code"], f" ({r['batch_no']})" if r.get("batch_no") else "", flt(r["available"]), r["warehouse"], flt(r["picked"])
        )
        for r in short
    ]
    frappe.throw(_("The picked quantities are not available in the warehouse, so no document was created. Reset these rows and pick from stock:") + "<br>" + "<br>".join(lines), InsufficientStockError)


# --------------------------------------------------------------------------------------------
# Loading / permissions
# --------------------------------------------------------------------------------------------


def load_row(name, row):
    doc = frappe.get_doc(PICK_LIST, name)
    doc.check_permission("read")
    if doc.docstatus != 1:
        frappe.throw(_("Pick List {0} must be submitted before it can be picked.").format(name))
    ensure_open(doc)
    child = next((r for r in doc.get("locations") or [] if r.name == row), None)
    if child is None:
        frappe.throw(_("Row {0} does not belong to Pick List {1}.").format(row, name))
    user = frappe.session.user
    if not rules.can_act_on_row(user, child.get("custom_picker"), frappe.get_roles(user)):
        frappe.throw(_("Row {0} of Pick List {1} is not assigned to you.").format(child.idx, name), frappe.PermissionError)
    return doc, child


def load_for_card(name):
    doc = frappe.get_doc(PICK_LIST, name)
    doc.check_permission("read")
    if doc.docstatus != 1:
        frappe.throw(_("Pick List {0} must be submitted before it can be picked.").format(name))
    user = frappe.session.user
    pickers = [r.get("custom_picker") for r in doc.get("locations") or []]
    if not rules.can_act_on_card(user, pickers, frappe.get_roles(user)):
        frappe.throw(_("Pick List {0} has no rows assigned to you.").format(name), frappe.PermissionError)
    return doc


def ensure_open(doc):
    if doc.get("status") in ("Completed", "Cancelled"):
        frappe.throw(_("Pick List {0} is {1} and can no longer be picked.").format(doc.name, _(doc.status)))


def current_status(doc):
    return doc.get("custom_picking_status") or rules.STATUS_READY


def reload(doc):
    return frappe.get_doc(PICK_LIST, doc.name)


def required_qty(row):
    """The row's target: Picked Qty (in Stock UOM) as the Senior Warehouse User set it."""
    return rules.target_qty(row.get("picked_qty"), row.get("stock_qty"), row.get("qty"), row.get("conversion_factor"))


# --------------------------------------------------------------------------------------------
# Document generation
# --------------------------------------------------------------------------------------------


def target_for(doc):
    try:
        return rules.target_document(doc.get("purpose"))
    except rules.PickingRuleError as exc:
        frappe.throw(str(exc))


def find_generated_document(doc):
    """Returns {doctype, name, docstatus} when a live (non-cancelled) document already exists."""
    doctype, _purpose = target_for(doc)

    if doc.get("custom_generated_doctype") and doc.get("custom_generated_docname"):
        docstatus = frappe.db.get_value(doc.custom_generated_doctype, doc.custom_generated_docname, "docstatus")
        if docstatus is not None and cint(docstatus) != 2:
            return {"doctype": doc.custom_generated_doctype, "name": doc.custom_generated_docname, "docstatus": cint(docstatus)}

    # A Stock Entry links the pick list in its header; a Delivery Note only in its rows (pick_list_item).
    if frappe.get_meta(doctype).has_field("pick_list"):
        found = frappe.get_all(
            doctype,
            filters={"pick_list": doc.name, "docstatus": ["!=", 2]},
            fields=["name", "docstatus"],
            order_by="creation asc",
            limit_page_length=1,
        )
        if found:
            return {"doctype": doctype, "name": found[0].name, "docstatus": cint(found[0].docstatus)}

    child_doctype = doctype + " Item" if doctype == rules.DELIVERY_NOTE else "Stock Entry Detail"
    row_names = [row.name for row in doc.get("locations") or []]
    if row_names:
        found = frappe.get_all(
            child_doctype,
            filters={"pick_list_item": ["in", row_names], "docstatus": ["!=", 2]},
            fields=["parent"],
            order_by="creation asc",
            limit_page_length=1,
        )
        if found:
            return {"doctype": doctype, "name": found[0].parent, "docstatus": cint(frappe.db.get_value(doctype, found[0].parent, "docstatus"))}
    return None


def pickable_rows(doc):
    rows = [
        row
        for row in doc.get("locations") or []
        if flt(row.get("custom_wms_picked_qty")) > rules.QTY_TOLERANCE and not row.get("product_bundle_item")
    ]
    if not rows:
        frappe.throw(_("Nothing was picked on Pick List {0}.").format(doc.name))
    return rows


def picked_parts(row) -> list[tuple]:
    """(batch_no, qty in stock UOM) per document row: one per batch for a row picked from several
    batches, else the row's own batch (or none) with the whole picked quantity."""
    picked = flt(row.get("custom_wms_picked_qty"))
    if takes_several_batches(row):
        batches = row_batches(row)
        if batches:
            return [(entry["batch_no"], flt(entry["qty"])) for entry in batches]
    return [(row.get("batch_no") or None, picked)]


def set_serial_batch(item, row, batch_no=None):
    """The picked batch / serial numbers go into the row fields; ERPNext builds the Serial and
    Batch Bundle from them on submit (`use_serial_batch_fields`)."""
    if batch_no:
        item.batch_no = batch_no
    if row.get("serial_no"):
        item.serial_no = row.serial_no
    if batch_no or row.get("serial_no"):
        item.use_serial_batch_fields = 1


def delivery_note_skeleton(sales_order):
    """A Delivery Note mapped from the Sales Order header only (customer, addresses, taxes), without
    item rows."""
    from erpnext.selling.doctype.sales_order.sales_order import make_delivery_note as make_dn_from_so

    return make_dn_from_so(sales_order, kwargs={"skip_item_mapping": True})


def make_delivery_notes(pick_list, values=None):
    """One draft Delivery Note per customer (mirrors erpnext's create_delivery_note, but built from
    the physically picked quantities and skipping rows that were not picked). Every note is
    prepared first; nothing is inserted while a required field is still unanswered."""
    from frappe.model.mapper import map_child_doc

    rows = pickable_rows(pick_list)
    item_mapper = {
        "doctype": "Delivery Note Item",
        "field_map": {"rate": "rate", "name": "so_detail", "parent": "against_sales_order"},
    }
    prepared = []

    so_customers, by_customer = {}, {}
    for row in rows:
        sales_order = row.get("sales_order")
        if sales_order and sales_order not in so_customers:
            so_customers[sales_order] = frappe.db.get_value("Sales Order", sales_order, "customer")
            by_customer.setdefault(so_customers[sales_order], []).append(sales_order)

    for _customer, sales_orders in by_customer.items():
        delivery_note = delivery_note_skeleton(sales_orders[0])
        for row in rows:
            if row.get("sales_order") not in sales_orders:
                continue
            so_item = frappe.get_doc("Sales Order Item", row.sales_order_item) if row.get("sales_order_item") else None
            # One Delivery Note row per picked batch, all against the same Sales Order row.
            for batch_no, qty in picked_parts(row):
                if so_item is not None:
                    dn_item = map_child_doc(so_item, delivery_note, item_mapper)
                else:
                    dn_item = delivery_note.append("items", {"item_code": row.item_code, "against_sales_order": row.sales_order})
                fill_delivery_note_item(dn_item, row, batch_no, qty)
        prepare_delivery_note(delivery_note, pick_list)
        # Header fields only: the Sales Orders are a source of required-field values, not re-read documents.
        prepared.append((delivery_note, [documents.source_values("Sales Order", so) for so in sales_orders]))

    unlinked = [row for row in rows if not row.get("sales_order")]
    if unlinked:
        if not pick_list.get("customer"):
            frappe.throw(_("Pick List {0} has no customer, so a Delivery Note cannot be created.").format(pick_list.name))
        delivery_note = frappe.new_doc("Delivery Note")
        delivery_note.customer = pick_list.customer
        for row in unlinked:
            for batch_no, qty in picked_parts(row):
                fill_delivery_note_item(delivery_note.append("items", {"item_code": row.item_code}), row, batch_no, qty)
        prepare_delivery_note(delivery_note, pick_list)
        prepared.append((delivery_note, []))

    require_fields(rules.DELIVERY_NOTE, [(note, sources + [pick_list]) for note, sources in prepared], values)
    created = []
    for delivery_note, _sources in prepared:
        delivery_note.flags.ignore_mandatory = True  # same as erpnext's create_delivery_note
        delivery_note.insert()
        created.append(delivery_note)
    return created


def require_fields(doctype, docs_with_sources, values, extra_missing=None):
    """Fills the required fields of every prepared document; raises MissingRequiredFields with the
    union of what is still empty (plus `extra_missing`, fields the user must choose every time),
    before anything is inserted."""
    missing, seen = list(extra_missing or []), {(f["doctype"], f["fieldname"]) for f in extra_missing or []}
    for document, sources in docs_with_sources:
        for field in documents.fill_required(document, values, sources=sources, company=document.get("company")):
            key = (field["doctype"], field["fieldname"])
            if key not in seen:
                seen.add(key)
                missing.append(field)
    if missing:
        raise documents.MissingRequiredFields(doctype, missing)


def fill_delivery_note_item(dn_item, row, batch_no, picked):
    conversion_factor = flt(row.get("conversion_factor")) or 1
    picked = flt(picked)
    dn_item.item_code = row.item_code
    if not dn_item.get("item_name"):
        dn_item.item_name = row.get("item_name")
    if not dn_item.get("description"):
        dn_item.description = row.get("description") or row.get("item_name") or row.item_code
    dn_item.warehouse = row.get("warehouse")
    dn_item.qty = picked / conversion_factor
    dn_item.uom = row.get("uom") or dn_item.get("uom")
    dn_item.stock_uom = row.get("stock_uom") or dn_item.get("stock_uom")
    dn_item.conversion_factor = conversion_factor
    dn_item.stock_qty = picked
    set_serial_batch(dn_item, row, batch_no)
    dn_item.pick_list_item = row.name


def prepare_delivery_note(delivery_note, pick_list):
    # A Delivery Note has no pick_list header field: the link is per row (pick_list_item).
    delivery_note.company = pick_list.company
    delivery_note.run_method("set_missing_values")
    delivery_note.run_method("set_po_nos")
    delivery_note.run_method("calculate_taxes_and_totals")
    stamp_warehouse_values(delivery_note)


def stamp_warehouse_values(doc):
    """What the warehouse owns on a document it creates: its department on every row (*WMS
    Settings > Warehouse Department*, so the site's submit rules pass without asking) and the
    employee processing it in the site's officer field (whose signature the print carries)."""
    documents.apply_department(doc)
    documents.stamp_officer(doc)


def row_material_request(row):
    """The Material Request of a Pick List row: ERPNext's mapper links the row only through
    `material_request_item`, so the request is read from that line when `material_request` is empty."""
    if row.get("material_request"):
        return row.material_request
    if row.get("material_request_item"):
        return frappe.db.get_value("Material Request Item", row.material_request_item, "parent")
    return None


def make_stock_entry(pick_list, purpose, values=None):
    rows = pickable_rows(pick_list)
    stock_entry = frappe.new_doc("Stock Entry")
    stock_entry.company = pick_list.company
    stock_entry.purpose = purpose
    stock_entry.set_stock_entry_type()
    stock_entry.pick_list = pick_list.name
    if purpose == rules.PURPOSE_MATERIAL_TRANSFER_FOR_MANUFACTURE and pick_list.get("work_order"):
        stock_entry.work_order = pick_list.work_order

    targets = row_target_warehouses(pick_list)
    needs_target = purpose != rules.PURPOSE_MATERIAL_ISSUE
    for row in rows:
        target = targets.get(row.name) if needs_target else None
        if needs_target and not target:
            frappe.throw(
                _("Set a Target Warehouse on Pick List {0} (row {1}) before creating a {2}.").format(
                    pick_list.name, row.idx, _(purpose)
                )
            )
        conversion_factor = flt(row.get("conversion_factor")) or 1
        # One Stock Entry row per picked batch.
        for batch_no, picked in picked_parts(row):
            item = stock_entry.append(
                "items",
                {
                    "item_code": row.item_code,
                    "item_name": row.get("item_name"),
                    "description": row.get("description") or row.get("item_name") or row.item_code,
                    "s_warehouse": row.get("warehouse"),
                    "t_warehouse": target,
                    "qty": picked / conversion_factor,
                    "uom": row.get("uom") or row.get("stock_uom"),
                    "stock_uom": row.get("stock_uom"),
                    "conversion_factor": conversion_factor,
                    "transfer_qty": picked,
                },
            )
            set_serial_batch(item, row, batch_no)
            item.pick_list_item = row.name
            material_request = row_material_request(row)
            if material_request:
                item.material_request = material_request
                item.material_request_item = row.get("material_request_item")

    stock_entry.run_method("set_missing_values")
    stamp_warehouse_values(stock_entry)
    header_request = pick_list.get("material_request") or next((row_material_request(row) for row in rows if row_material_request(row)), None)
    extra_missing = []
    if purpose == rules.PURPOSE_MATERIAL_ISSUE:
        # The requesting department and employee, not the warehouse's (department replaced).
        documents.apply_material_request_values(stock_entry, header_request)
    else:
        # A transfer names who receives it: chosen by the user in the app for every document.
        extra_missing = documents.ask_received_by(stock_entry, values)
    sources = [pick_list]
    if header_request:
        sources.insert(0, documents.source_values("Material Request", header_request))
    require_fields(rules.STOCK_ENTRY, [(stock_entry, sources)], values, extra_missing)
    stock_entry.insert()
    return stock_entry


# --------------------------------------------------------------------------------------------
# Pick Lists from Material Requests
# --------------------------------------------------------------------------------------------


@frappe.whitelist()
def create_pick_list_from_material_request(source_name, target_doc=None):
    """A Pick List for a submitted Material Request, for *Material Issue* requests too (ERPNext's
    own *Create > Pick List* only offers it on *Material Transfer* requests; the app's Property
    Setter adds *Material Issue* to the Pick List purposes). Mirrors
    `erpnext...material_request.create_pick_list`: rows for what is not picked yet, purpose from
    the request type, locations allocated unless the request picks manually. On a Material Issue
    request the row's *For Warehouse* is the warehouse to issue from, so it becomes the pick
    location; a transfer request's *Source Warehouse* does the same there. Used by the desk button
    the app adds on Material Request."""
    from frappe.model.mapper import get_mapped_doc

    request_type = frappe.db.get_value("Material Request", source_name, "material_request_type")
    source_warehouse = "warehouse" if request_type == rules.PURPOSE_MATERIAL_ISSUE else "from_warehouse"

    def update_item(obj, target, source_parent):
        qty = flt((flt(obj.stock_qty) - flt(obj.picked_qty)) / (flt(target.conversion_factor) or 1), obj.precision("qty"))
        target.qty = qty
        target.stock_qty = qty * (flt(obj.conversion_factor) or 1)
        target.conversion_factor = obj.conversion_factor

    doc = get_mapped_doc(
        "Material Request",
        source_name,
        {
            "Material Request": {
                "doctype": PICK_LIST,
                "field_map": {"material_request_type": "purpose"},
                "validation": {"docstatus": ["=", 1]},
            },
            "Material Request Item": {
                "doctype": PICK_LIST_ITEM,
                "field_map": {"name": "material_request_item", "stock_qty": "stock_qty", source_warehouse: "warehouse"},
                "postprocess": update_item,
                "condition": lambda row: flt(row.picked_qty, row.precision("picked_qty")) < flt(row.stock_qty, row.precision("stock_qty")),
            },
        },
        target_doc,
    )
    if doc.purpose not in rules.PICK_LIST_PURPOSES:
        frappe.throw(_("A Pick List cannot be created for a {0} Material Request.").format(_(request_type)))
    if not doc.get("pick_manually"):
        doc.set_item_locations()
    return doc


# --------------------------------------------------------------------------------------------
# Serialization
# --------------------------------------------------------------------------------------------


def header_fields():
    meta = frappe.get_meta(PICK_LIST)
    return [field for field in HEADER_FIELDS if field in ALWAYS_PRESENT or meta.has_field(field)]


def child_rows(parents):
    """Rows of several pick lists in one query, grouped by parent."""
    if not parents:
        return {}
    meta = frappe.get_meta(PICK_LIST_ITEM)
    fields = [f for f in ROW_FIELDS if f in ("name", "idx", "parent") or meta.has_field(f)]
    grouped = {}
    for row in frappe.get_all(PICK_LIST_ITEM, filters={"parent": ["in", parents], "parenttype": PICK_LIST}, fields=fields, order_by="parent asc, idx asc"):
        grouped.setdefault(row.parent, []).append(row)
    return grouped


def customer_name_for(customer, cache={}):
    if not customer:
        return None
    if customer not in cache:
        cache[customer] = frappe.db.get_value("Customer", customer, "customer_name") or customer
    return cache[customer]


def str_or_none(value):
    return None if value in (None, "") else str(value)


def row_totals(rows, user):
    picked_rows = [r for r in rows if r.get("custom_row_status") == rules.ROW_PICKED]
    mine = [r for r in rows if r.get("custom_picker") == user]
    return {
        "item_count": len(rows),
        "picked_rows": len(picked_rows),
        "required_qty": sum(required_qty(r) for r in rows),
        "picked_qty": sum(flt(r.get("custom_wms_picked_qty")) for r in rows),
        "my_row_count": len(mine),
        "my_picked_rows": len([r for r in mine if r.get("custom_row_status") == rules.ROW_PICKED]),
        "my_open_rows": len([r for r in mine if r.get("custom_row_status") != rules.ROW_PICKED]),
        "all_rows_picked": rules.card_complete([r.get("custom_row_status") for r in rows]),
    }


def serialize_header(source, rows, user):
    header = {
        "name": source.get("name"),
        "purpose": source.get("purpose"),
        "company": source.get("company"),
        "customer": source.get("customer"),
        "customer_name": source.get("customer_name") or customer_name_for(source.get("customer")),
        "parent_warehouse": source.get("parent_warehouse"),
        "target_warehouse": source.get("custom_target_warehouse"),
        "status": source.get("status"),
        "picking_status": source.get("custom_picking_status") or rules.STATUS_READY,
        "card_started_at": str_or_none(source.get("custom_card_started_at")),
        "card_completed_at": str_or_none(source.get("custom_card_completed_at")),
        "generated_doctype": source.get("custom_generated_doctype"),
        "generated_docname": source.get("custom_generated_docname"),
        "work_order": source.get("work_order"),
        "material_request": source.get("material_request"),
        "modified": str_or_none(source.get("modified")),
        "creation": str_or_none(source.get("creation")),
    }
    header.update(row_totals(rows, user))
    return header


def row_target_warehouses(doc):
    """Target warehouse per row: Material Request row warehouse, else Work Order WIP warehouse,
    else the Pick List's custom Target Warehouse."""
    rows = doc.get("locations") or []
    targets = {}
    mr_rows = [row.material_request_item for row in rows if row.get("material_request_item")]
    mr_warehouses = {}
    if mr_rows:
        for mr_item in frappe.get_all("Material Request Item", filters={"name": ["in", mr_rows]}, fields=["name", "warehouse"]):
            mr_warehouses[mr_item.name] = mr_item.warehouse
    default_target = doc.get("custom_target_warehouse")
    if not default_target and doc.get("work_order"):
        default_target = frappe.db.get_value("Work Order", doc.work_order, "wip_warehouse")
    for row in rows:
        targets[row.name] = mr_warehouses.get(row.get("material_request_item")) or default_target
    return targets


def serialize_row(row, user, info=None, expiry_date=None, target_warehouse=None, expiries=None):
    info = info or {}
    required = required_qty(row)
    picked = flt(row.get("custom_wms_picked_qty"))
    several = takes_several_batches(row, has_batch_no=cint(info.get("has_batch_no")))
    batches = row_batches(row) if several else []
    expiries = expiries or {}
    return {
        "name": row.name,
        "idx": row.idx,
        "item_code": row.item_code,
        "item_name": row.get("item_name") or info.get("item_name") or row.item_code,
        "description": row.get("description"),
        "warehouse": row.get("warehouse"),
        "target_warehouse": target_warehouse,
        # A row picked from several batches has no single batch: `batches` lists them.
        "batch_no": None if several else row.get("batch_no"),
        "batch_from_wms": cint(row.get("custom_batch_from_wms")),
        "multi_batch": 1 if several else 0,
        "batches": [
            {"batch_no": entry["batch_no"], "qty": flt(entry["qty"]), "expiry_date": str_or_none(expiries.get(entry["batch_no"]))}
            for entry in batches
        ],
        "expiry_date": None if several else str_or_none(expiry_date),
        "serial_no": row.get("serial_no"),
        "required_qty": required,
        "picked_qty": picked,
        "stock_qty": flt(row.get("stock_qty")),
        "reserved_qty": flt(row.get("stock_reserved_qty")),
        "uom": row.get("stock_uom") or row.get("uom"),
        "order_qty": flt(row.get("qty")),
        "order_uom": row.get("uom"),
        "conversion_factor": flt(row.get("conversion_factor")) or 1,
        "has_batch_no": cint(info.get("has_batch_no")),
        "sales_order": row.get("sales_order"),
        "material_request": row.get("material_request"),
        "picker": row.get("custom_picker"),
        "is_mine": bool(user) and row.get("custom_picker") == user,
        "row_status": row.get("custom_row_status") or rules.ROW_NOT_PICKED,
        "row_started_at": str_or_none(row.get("custom_row_started_at")),
        "row_completed_at": str_or_none(row.get("custom_row_completed_at")),
        "duration_seconds": flt(row.get("custom_picking_duration_seconds")) if row.get("custom_picking_duration_seconds") not in (None, "") else None,
    }


def serialize_pick_list(doc, user):
    rows = doc.get("locations") or []
    item_codes = list({row.item_code for row in rows if row.item_code})
    batch_nos = {row.batch_no for row in rows if row.get("batch_no")}
    for row in rows:
        batch_nos.update(entry["batch_no"] for entry in row_batches(row))
    batch_nos = list(batch_nos)

    item_info = {}
    if item_codes:
        for item in frappe.get_all("Item", filters={"name": ["in", item_codes]}, fields=["name", "item_name", "has_batch_no"]):
            item_info[item.name] = item
    expiries = {}
    if batch_nos:
        for batch in frappe.get_all("Batch", filters={"name": ["in", batch_nos]}, fields=["name", "expiry_date"]):
            expiries[batch.name] = batch.expiry_date
    targets = row_target_warehouses(doc)

    header = serialize_header(doc, rows, user)
    header["items"] = [
        serialize_row(row, user, item_info.get(row.item_code), expiries.get(row.get("batch_no")), targets.get(row.name), expiries) for row in rows
    ]
    return header


def row_result(name, row_name, row_completed, card_completed, reservation=None):
    doc = frappe.get_doc(PICK_LIST, name)
    serialized = serialize_pick_list(doc, frappe.session.user)
    row = next((r for r in serialized["items"] if r["name"] == row_name), None)
    return {
        "pick_list": serialized,
        "row": row,
        "row_completed": bool(row_completed),
        "card_completed": bool(card_completed),
        # Only the request that flipped the card to Picked is the last picker.
        "is_last_picker": bool(card_completed),
        # Stock reservation run by that request (None: not a Delivery card, or reservation is off).
        "reservation": reservation,
    }
