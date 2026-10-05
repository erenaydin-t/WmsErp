"""Whitelisted API used by the WMS ERP Android app for row-level physical picking.

Endpoints are called as `/api/method/wmserp_picking.api.pick_list.<function>` (POST with a JSON
body; the read-only ones also accept GET). They only write the WMS custom fields on Pick List /
Pick List Item and create *draft* standard documents through the regular Document API, so stock
ledger and GL entries are always produced by ERPNext itself when those drafts are submitted.

Model
-----
* Assignment is per row: `Pick List Item.custom_picker`. Several pickers share one card.
* Row lifecycle: Not Picked --start_row--> Picking --save_row_progress*--> --complete_row--> Picked
  (a row completes when picked qty == required qty; timestamps and duration are stamped).
* Card lifecycle (header `custom_picking_status`): Ready to Pick -> Picking (first row started)
  -> Picked (every row picked; checked after each row completion under a row lock, so exactly one
  request observes the transition and is told `is_last_picker: true`).
* `generate_document` creates the draft Delivery Note / Stock Entry once the card is Picked.
"""

import datetime as dt

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
    "custom_row_started_at",
    "custom_row_completed_at",
    "custom_picking_duration_seconds",
]


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
        "features": ["stocktaking", "purchase_receipt_receiving", "label_sheets", "required_field_values"],
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
    filters = {"name": ["in", list(set(parents))], "docstatus": 1}
    if frappe.get_meta(PICK_LIST).has_field("status"):
        filters["status"] = "Open"
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
    if open_parents and frappe.get_meta(PICK_LIST).has_field("status"):
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
def save_row_progress(name, row, picked_qty, item_code=None, batch_no=None, elapsed_seconds=None):
    """Stores a partial (or full) picked quantity for one row. Completes the row when it reaches
    the required quantity, then runs the card completion check."""
    return update_row(name, row, picked_qty, item_code, batch_no, elapsed_seconds, require_complete=False)


@frappe.whitelist()
def complete_row(name, row, picked_qty=None, item_code=None, batch_no=None, elapsed_seconds=None):
    """Marks one row as Picked (picked qty must equal the required qty) and runs the card
    completion check; the response says whether this call completed the whole card."""
    return update_row(name, row, picked_qty, item_code, batch_no, elapsed_seconds, require_complete=True)


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


def update_row(name, row, picked_qty, item_code, batch_no, elapsed_seconds, require_complete):
    doc, child = load_row(name, row)
    required = required_qty(child)
    picked = flt(picked_qty) if picked_qty not in (None, "") else flt(child.get("custom_wms_picked_qty"))
    try:
        picked = rules.validate_picked_qty(picked, required, child.item_code)
    except rules.PickingRuleError as exc:
        frappe.throw(str(exc))

    if item_code and item_code.strip().lower() != (child.item_code or "").lower():
        frappe.throw(_("Wrong item. Expected: {0}, scanned: {1}").format(child.item_code, item_code))

    updates = {"custom_wms_picked_qty": picked}
    if batch_no:
        updates.update(validate_scanned_batch(child, batch_no.strip()))

    complete = rules.is_row_complete(picked, required)
    if require_complete and not complete:
        frappe.throw(_("Row {0} ({1}) is not complete: {2} of {3} picked.").format(child.idx, child.item_code, flt(picked), flt(required)))
    if child.get("custom_row_status") == rules.ROW_PICKED and not complete:
        frappe.throw(_("Row {0} ({1}) is already picked and cannot be reduced.").format(child.idx, child.item_code))

    now = now_datetime()
    started = child.get("custom_row_started_at")
    if started:
        started = get_datetime(started)
    else:
        started = started_from_elapsed(now, elapsed_seconds)
        updates["custom_row_started_at"] = started

    row_completed = False
    if complete and child.get("custom_row_status") != rules.ROW_PICKED:
        updates.update(
            {
                "custom_row_status": rules.ROW_PICKED,
                "custom_row_completed_at": now,
                "custom_picking_duration_seconds": rules.duration_seconds(started, now, elapsed_seconds),
                # Keep the standard field in step so ERPNext's own "Create ..." buttons agree with the app.
                "picked_qty": picked,
            }
        )
        row_completed = True
    elif not complete:
        updates["custom_row_status"] = rules.next_row_status(picked, required, child.get("custom_row_status"))

    child.db_set(updates, update_modified=False)
    stamp_card_started(doc, started)

    card_completed = False
    if row_completed:
        card_completed = run_card_completion_check(doc.name)
        if card_completed:
            doc.add_comment("Info", _("All rows picked. Last row completed by {0}.").format(frappe.utils.get_fullname(frappe.session.user)))
    else:
        frappe.db.set_value(PICK_LIST, doc.name, "modified", now, update_modified=False)
    return row_result(doc.name, child.name, row_completed=row_completed, card_completed=card_completed)


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
    """The scanned batch must be the one ERPNext allocated to the row; rows without an allocated
    batch accept any valid, unexpired batch of the same item (and remember it)."""
    expected = (child.get("batch_no") or "").strip()
    if expected:
        if batch_no.lower() != expected.lower():
            frappe.throw(_("Wrong batch. Expected: {0}, scanned: {1}").format(expected, batch_no))
        return {}
    batch = frappe.db.get_value("Batch", batch_no, ["item", "expiry_date", "disabled"], as_dict=True)
    if not batch:
        frappe.throw(_("Batch {0} does not exist.").format(batch_no))
    if batch.item != child.item_code:
        frappe.throw(_("Batch {0} belongs to item {1}, not {2}.").format(batch_no, batch.item, child.item_code))
    if cint(batch.disabled):
        frappe.throw(_("Batch {0} is disabled.").format(batch_no))
    if batch.expiry_date and getdate(batch.expiry_date) < getdate(nowdate()):
        frappe.throw(_("Batch {0} expired on {1}.").format(batch_no, frappe.format(batch.expiry_date, "Date")))
    return {"batch_no": batch_no}


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
    return flt(row.get("stock_qty")) or flt(row.get("qty")) * (flt(row.get("conversion_factor")) or 1)


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
    if row_names and frappe.get_meta(child_doctype).has_field("pick_list_item"):
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


def set_if_field(document, fieldname, value):
    if document.meta.has_field(fieldname):
        document.set(fieldname, value)


def set_serial_batch(item, row):
    if row.get("batch_no"):
        item.batch_no = row.batch_no
    if row.get("serial_no"):
        item.serial_no = row.serial_no
    if row.get("batch_no") or row.get("serial_no"):
        set_if_field(item, "use_serial_batch_fields", 1)  # ERPNext v15 serial/batch bundles


def delivery_note_skeleton(sales_order):
    """A Delivery Note mapped from the Sales Order header only (customer, addresses, taxes), without
    item rows. The mapper's signature changed in ERPNext v15:
    v14: make_delivery_note(source_name, target_doc=None, skip_item_mapping=False)
    v15/v16: make_delivery_note(source_name, target_doc=None, kwargs=None)"""
    import inspect

    from erpnext.selling.doctype.sales_order.sales_order import make_delivery_note as make_dn_from_so

    if "kwargs" in inspect.signature(make_dn_from_so).parameters:
        return make_dn_from_so(sales_order, kwargs={"skip_item_mapping": True})
    return make_dn_from_so(sales_order, skip_item_mapping=True)


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
            if row.get("sales_order_item"):
                so_item = frappe.get_doc("Sales Order Item", row.sales_order_item)
                dn_item = map_child_doc(so_item, delivery_note, item_mapper)
            else:
                dn_item = delivery_note.append("items", {"item_code": row.item_code, "against_sales_order": row.sales_order})
            fill_delivery_note_item(dn_item, row)
        prepare_delivery_note(delivery_note, pick_list)
        prepared.append((delivery_note, [frappe.get_doc("Sales Order", so) for so in sales_orders]))

    unlinked = [row for row in rows if not row.get("sales_order")]
    if unlinked:
        if not pick_list.get("customer"):
            frappe.throw(_("Pick List {0} has no customer, so a Delivery Note cannot be created.").format(pick_list.name))
        delivery_note = frappe.new_doc("Delivery Note")
        delivery_note.customer = pick_list.customer
        for row in unlinked:
            fill_delivery_note_item(delivery_note.append("items", {"item_code": row.item_code}), row)
        prepare_delivery_note(delivery_note, pick_list)
        prepared.append((delivery_note, []))

    require_fields(rules.DELIVERY_NOTE, [(note, sources + [pick_list]) for note, sources in prepared], values)
    created = []
    for delivery_note, _sources in prepared:
        delivery_note.flags.ignore_mandatory = True  # same as erpnext's create_delivery_note
        delivery_note.insert()
        created.append(delivery_note)
    return created


def require_fields(doctype, docs_with_sources, values):
    """Fills the required fields of every prepared document; raises MissingRequiredFields with the
    union of what is still empty, before anything is inserted."""
    missing, seen = [], set()
    for document, sources in docs_with_sources:
        for field in documents.fill_required(document, values, sources=sources, company=document.get("company")):
            key = (field["doctype"], field["fieldname"])
            if key not in seen:
                seen.add(key)
                missing.append(field)
    if missing:
        raise documents.MissingRequiredFields(doctype, missing)


def fill_delivery_note_item(dn_item, row):
    conversion_factor = flt(row.get("conversion_factor")) or 1
    picked = flt(row.get("custom_wms_picked_qty"))
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
    set_serial_batch(dn_item, row)
    set_if_field(dn_item, "pick_list_item", row.name)


def prepare_delivery_note(delivery_note, pick_list):
    set_if_field(delivery_note, "pick_list", pick_list.name)
    delivery_note.company = pick_list.company
    delivery_note.run_method("set_missing_values")
    delivery_note.run_method("set_po_nos")
    delivery_note.run_method("calculate_taxes_and_totals")


def make_stock_entry(pick_list, purpose, values=None):
    rows = pickable_rows(pick_list)
    stock_entry = frappe.new_doc("Stock Entry")
    stock_entry.company = pick_list.company
    stock_entry.purpose = purpose
    if hasattr(stock_entry, "set_stock_entry_type"):
        stock_entry.set_stock_entry_type()
    else:
        stock_entry.stock_entry_type = purpose
    set_if_field(stock_entry, "pick_list", pick_list.name)
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
        picked = flt(row.get("custom_wms_picked_qty"))
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
        set_serial_batch(item, row)
        set_if_field(item, "pick_list_item", row.name)
        if row.get("material_request"):
            set_if_field(item, "material_request", row.material_request)
            set_if_field(item, "material_request_item", row.get("material_request_item"))

    stock_entry.run_method("set_missing_values")
    sources = [pick_list]
    if pick_list.get("material_request"):
        sources.insert(0, frappe.get_doc("Material Request", pick_list.material_request))
    require_fields(rules.STOCK_ENTRY, [(stock_entry, sources)], values)
    stock_entry.insert()
    return stock_entry


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


def serialize_row(row, user, info=None, expiry_date=None, target_warehouse=None):
    info = info or {}
    required = required_qty(row)
    picked = flt(row.get("custom_wms_picked_qty"))
    return {
        "name": row.name,
        "idx": row.idx,
        "item_code": row.item_code,
        "item_name": row.get("item_name") or info.get("item_name") or row.item_code,
        "description": row.get("description"),
        "warehouse": row.get("warehouse"),
        "target_warehouse": target_warehouse,
        "batch_no": row.get("batch_no"),
        "expiry_date": str_or_none(expiry_date),
        "serial_no": row.get("serial_no"),
        "required_qty": required,
        "picked_qty": picked,
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
    batch_nos = list({row.batch_no for row in rows if row.get("batch_no")})

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
        serialize_row(row, user, item_info.get(row.item_code), expiries.get(row.get("batch_no")), targets.get(row.name)) for row in rows
    ]
    return header


def row_result(name, row_name, row_completed, card_completed):
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
    }
