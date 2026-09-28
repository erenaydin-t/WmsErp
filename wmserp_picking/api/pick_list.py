"""Whitelisted API used by the WMS ERP Android app for the physical picking workflow.

Endpoints are called as `/api/method/wmserp_picking.api.pick_list.<function>` (POST with a JSON
body; the read-only ones also accept GET). They only write the WMS custom fields on Pick List /
Pick List Item and create *draft* standard documents through the regular Document API, so stock
ledger and GL entries are always produced by ERPNext itself when those drafts are submitted.

Lifecycle:  Ready to Pick --start_picking--> Picking --save_progress*--> --complete_picking--> Picked
            --generate_document--> Delivery Note / Stock Entry (draft, generated at most once)
"""

import frappe
from frappe import _
from frappe.utils import cint, flt, getdate, now_datetime, nowdate

from wmserp_picking.picking import rules
from wmserp_picking.picking.pick_list_events import add_assignment, remove_assignment

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
    "_assign",
    "custom_picker",
    "custom_picking_status",
    "custom_target_warehouse",
    "custom_picking_started_at",
    "custom_picking_started_by",
    "custom_picking_completed_at",
    "custom_picking_completed_by",
    "custom_generated_doctype",
    "custom_generated_docname",
]
ALWAYS_PRESENT = {"name", "docstatus", "modified", "creation", "owner", "_assign"}


# --------------------------------------------------------------------------------------------
# Reads
# --------------------------------------------------------------------------------------------


@frappe.whitelist()
def get_my_pick_lists(status=None, include_generated=0, limit=50):
    """Pick Lists assigned to the current user (custom_picker or standard Assign To) that still
    need action: Ready to Pick, Picking, or Picked without a generated document."""
    user = frappe.session.user
    statuses = [status] if status else list(rules.PICKING_STATUSES)
    if status and status not in rules.PICKING_STATUSES:
        frappe.throw(_("Unknown picking status {0}").format(status))

    rows = frappe.get_list(
        PICK_LIST,
        filters=[
            [PICK_LIST, "docstatus", "=", 1],
            [PICK_LIST, "custom_picking_status", "in", statuses],
        ],
        or_filters=[
            [PICK_LIST, "custom_picker", "=", user],
            [PICK_LIST, "_assign", "like", f"%{user}%"],
        ],
        fields=header_fields(),
        order_by="modified desc",
        limit_page_length=cint(limit) or 50,
    )
    if not cint(include_generated):
        rows = [
            row
            for row in rows
            if not (row.get("custom_picking_status") == rules.STATUS_PICKED and row.get("custom_generated_docname"))
        ]
    totals = item_totals([row.name for row in rows])
    return [serialize_header(row, totals.get(row.name)) for row in rows]


@frappe.whitelist()
def get_pick_list(name):
    doc = frappe.get_doc(PICK_LIST, name)
    doc.check_permission("read")
    return serialize_pick_list(doc)


@frappe.whitelist()
def resolve_scan(name, code):
    """Maps a scanned barcode / item code / batch number to a row of the pick list."""
    doc = frappe.get_doc(PICK_LIST, name)
    doc.check_permission("read")
    code = (code or "").strip()
    if not code:
        frappe.throw(_("Scanned code is empty."))
    rows = doc.get("locations") or []

    row = pick_row(rows, lambda r: (r.item_code or "").lower() == code.lower())
    if row:
        return scan_result(row, "item_code", row.get("batch_no"))

    item_code = frappe.db.get_value("Item Barcode", {"barcode": code}, "parent")
    if item_code:
        row = pick_row(rows, lambda r: r.item_code == item_code)
        if row:
            return scan_result(row, "barcode", row.get("batch_no"))
        return {"row_name": None, "item_code": item_code, "batch_no": None, "match": "not_on_list", "expiry_date": None}

    batch = frappe.db.get_value("Batch", code, ["name", "item", "expiry_date", "disabled"], as_dict=True)
    if batch:
        if cint(batch.disabled):
            frappe.throw(_("Batch {0} is disabled.").format(batch.name))
        if batch.expiry_date and getdate(batch.expiry_date) < getdate(nowdate()):
            frappe.throw(_("Batch {0} expired on {1}.").format(batch.name, frappe.format(batch.expiry_date, "Date")))
        row = pick_row(rows, lambda r: r.item_code == batch.item and (not r.get("batch_no") or r.batch_no == batch.name))
        if row:
            return scan_result(row, "batch", batch.name, batch.expiry_date)
        return {"row_name": None, "item_code": batch.item, "batch_no": batch.name, "match": "not_on_list", "expiry_date": str_or_none(batch.expiry_date)}

    return {"row_name": None, "item_code": None, "batch_no": None, "match": "none", "expiry_date": None}


# --------------------------------------------------------------------------------------------
# Transitions
# --------------------------------------------------------------------------------------------


@frappe.whitelist()
def assign_picker(name, user):
    """Sets the picker (supervisor action) and mirrors it into the standard assignment."""
    doc = frappe.get_doc(PICK_LIST, name)
    doc.check_permission("write")
    if not frappe.db.exists("User", user):
        frappe.throw(_("User {0} does not exist.").format(user))
    previous = doc.get("custom_picker")
    doc.db_set("custom_picker", user, notify=True)
    if previous and previous != user:
        remove_assignment(doc, previous)
    add_assignment(doc, user)
    return serialize_pick_list(reload(doc))


@frappe.whitelist()
def start_picking(name):
    """Ready to Pick -> Picking. Validates the assignment and stamps the start time."""
    doc = load_for_action(name)
    status = current_status(doc)
    if status == rules.STATUS_PICKED:
        frappe.throw(_("Pick List {0} has already been picked.").format(name))
    if status == rules.STATUS_PICKING:
        return serialize_pick_list(doc)  # resuming is idempotent
    ensure_open(doc)

    user = frappe.session.user
    values = {
        "custom_picking_status": rules.STATUS_PICKING,
        "custom_picking_started_at": now_datetime(),
        "custom_picking_started_by": user,
    }
    if not doc.get("custom_picker"):
        values["custom_picker"] = user  # claimed through the standard Assign To only
    doc.db_set(values, notify=True)
    add_assignment(doc, doc.custom_picker)
    doc.add_comment("Info", _("Picking started by {0}").format(frappe.utils.get_fullname(user)))
    return serialize_pick_list(reload(doc))


@frappe.whitelist()
def save_progress(name, items):
    """Stores partial picked quantities so picking can be paused and resumed."""
    doc = load_for_action(name)
    require_status(doc, rules.STATUS_PICKING, _("Start picking before saving progress."))
    apply_progress(doc, items)
    return serialize_pick_list(reload(doc))


@frappe.whitelist()
def complete_picking(name, items=None):
    """Picking -> Picked. Every mandatory row must be picked in full."""
    doc = load_for_action(name)
    require_status(doc, rules.STATUS_PICKING, _("Start picking before completing it."))
    if items:
        apply_progress(doc, items)
        doc = reload(doc)

    incomplete = rules.incomplete_rows([row_summary(row) for row in doc.get("locations") or []])
    if incomplete:
        details = ", ".join(
            "{0} ({1:g}/{2:g})".format(row["item_code"], row["picked_qty"], row["required_qty"]) for row in incomplete
        )
        frappe.throw(_("Picking is incomplete: {0}").format(details))

    user = frappe.session.user
    for row in doc.get("locations") or []:
        # Keep the standard field in step so ERPNext's own "Create ..." buttons agree with the app.
        row.db_set({"picked_qty": flt(row.get("custom_wms_picked_qty"))}, update_modified=False)
    doc.db_set(
        {
            "custom_picking_status": rules.STATUS_PICKED,
            "custom_picking_completed_at": now_datetime(),
            "custom_picking_completed_by": user,
        },
        notify=True,
    )
    doc.add_comment("Info", _("Picking completed by {0}").format(frappe.utils.get_fullname(user)))
    return serialize_pick_list(reload(doc))


@frappe.whitelist()
def generate_document(name):
    """Creates the draft Delivery Note / Stock Entry for a picked list, exactly once."""
    doc = load_for_action(name)
    require_status(doc, rules.STATUS_PICKED, _("Complete picking before creating a document."))

    # Row lock: two devices pressing the button at the same time serialize here, and the second
    # one sees the document created by the first.
    frappe.db.get_value(PICK_LIST, name, "name", for_update=True)
    doc = reload(doc)

    existing = find_generated_document(doc)
    if existing:
        existing["already_generated"] = True
        existing["documents"] = [{"doctype": existing["doctype"], "name": existing["name"]}]
        return existing

    doctype, stock_entry_purpose = target_for(doc)
    if doctype == rules.DELIVERY_NOTE:
        created = make_delivery_notes(doc)
    else:
        created = [make_stock_entry(doc, stock_entry_purpose)]

    primary = created[0]
    doc.db_set({"custom_generated_doctype": primary.doctype, "custom_generated_docname": primary.name}, notify=True)
    doc.add_comment("Info", _("{0} {1} created from WMS picking").format(_(primary.doctype), primary.name))
    return {
        "doctype": primary.doctype,
        "name": primary.name,
        "docstatus": primary.docstatus,
        "already_generated": False,
        "documents": [{"doctype": d.doctype, "name": d.name} for d in created],
    }


# --------------------------------------------------------------------------------------------
# Validation helpers
# --------------------------------------------------------------------------------------------


def load_for_action(name, allow_supervisor=True):
    doc = frappe.get_doc(PICK_LIST, name)
    doc.check_permission("read")
    if doc.docstatus != 1:
        frappe.throw(_("Pick List {0} must be submitted before it can be picked.").format(name))
    user = frappe.session.user
    if not rules.can_act(user, doc.get("custom_picker"), rules.assigned_users(doc.get("_assign")), frappe.get_roles(user), allow_supervisor):
        frappe.throw(_("Pick List {0} is not assigned to you.").format(name), frappe.PermissionError)
    return doc


def ensure_open(doc):
    if doc.get("status") in ("Completed", "Cancelled"):
        frappe.throw(_("Pick List {0} is {1} and can no longer be picked.").format(doc.name, _(doc.status)))


def current_status(doc):
    return doc.get("custom_picking_status") or rules.STATUS_READY


def require_status(doc, expected, message):
    if current_status(doc) != expected:
        frappe.throw(message)


def reload(doc):
    return frappe.get_doc(PICK_LIST, doc.name)


def required_qty(row):
    return flt(row.get("stock_qty")) or flt(row.get("qty")) * (flt(row.get("conversion_factor")) or 1)


def row_summary(row):
    return {
        "name": row.name,
        "item_code": row.item_code,
        "picked_qty": flt(row.get("custom_wms_picked_qty")),
        "required_qty": required_qty(row),
        "optional": cint(row.get("custom_optional")),
    }


def apply_progress(doc, items):
    try:
        payload_rows = rules.normalize_progress_rows(items)
    except rules.PickingRuleError as exc:
        frappe.throw(str(exc))

    rows_by_name = {row.name: row for row in doc.get("locations") or []}
    today = getdate(nowdate())
    for payload in payload_rows:
        row = rows_by_name.get(payload["name"])
        if row is None:
            frappe.throw(_("Row {0} does not belong to Pick List {1}.").format(payload["name"], doc.name))
        updates = validate_row_progress(doc, row, payload, today)
        row.db_set(updates, update_modified=False)
    frappe.db.set_value(PICK_LIST, doc.name, "modified", now_datetime(), update_modified=False)


def validate_row_progress(doc, row, payload, today):
    """Checks item, warehouse, batch, expiry and quantity for one row and returns the DB updates."""
    required = required_qty(row)
    try:
        picked = rules.validate_picked_qty(payload["picked_qty"], required, row.item_code)
    except rules.PickingRuleError as exc:
        frappe.throw(str(exc))

    if payload.get("item_code") and payload["item_code"].lower() != (row.item_code or "").lower():
        frappe.throw(_("Row {0} expects item {1}, not {2}.").format(row.idx, row.item_code, payload["item_code"]))

    updates = {"custom_wms_picked_qty": picked}

    warehouse = payload.get("warehouse")
    if warehouse:
        if row.get("warehouse") and warehouse != row.warehouse:
            frappe.throw(_("Row {0} must be picked from warehouse {1}, not {2}.").format(row.idx, row.warehouse, warehouse))
        info = frappe.db.get_value("Warehouse", warehouse, ["is_group", "company"], as_dict=True)
        if not info:
            frappe.throw(_("Warehouse {0} does not exist.").format(warehouse))
        if cint(info.is_group):
            frappe.throw(_("Warehouse {0} is a group and cannot hold stock.").format(warehouse))
        if info.company and doc.company and info.company != doc.company:
            frappe.throw(_("Warehouse {0} belongs to another company.").format(warehouse))
        if not row.get("warehouse"):
            updates["warehouse"] = warehouse

    batch_no = payload.get("batch_no")
    if batch_no:
        if row.get("batch_no") and batch_no != row.batch_no:
            frappe.throw(_("Row {0} expects batch {1}, not {2}.").format(row.idx, row.batch_no, batch_no))
        validate_batch(batch_no, row, picked, today)
        if not row.get("batch_no"):
            updates["batch_no"] = batch_no
    elif row.get("batch_no") and picked > 0:
        validate_batch(row.batch_no, row, picked, today)

    serial_no = payload.get("serial_no")
    if serial_no:
        for serial in [s.strip() for s in serial_no.replace(",", "\n").split("\n") if s.strip()]:
            item = frappe.db.get_value("Serial No", serial, "item_code")
            if not item:
                frappe.throw(_("Serial No {0} does not exist.").format(serial))
            if item != row.item_code:
                frappe.throw(_("Serial No {0} belongs to item {1}, not {2}.").format(serial, item, row.item_code))
        if not row.get("serial_no"):
            updates["serial_no"] = serial_no
    return updates


def validate_batch(batch_no, row, picked, today):
    batch = frappe.db.get_value("Batch", batch_no, ["item", "expiry_date", "disabled"], as_dict=True)
    if not batch:
        frappe.throw(_("Batch {0} does not exist.").format(batch_no))
    if batch.item != row.item_code:
        frappe.throw(_("Batch {0} belongs to item {1}, not {2}.").format(batch_no, batch.item, row.item_code))
    if cint(batch.disabled):
        frappe.throw(_("Batch {0} is disabled.").format(batch_no))
    if batch.expiry_date and getdate(batch.expiry_date) < today:
        frappe.throw(_("Batch {0} expired on {1}.").format(batch_no, frappe.format(batch.expiry_date, "Date")))
    available = batch_available_qty(batch_no, row)
    if available is not None and picked > available + rules.QTY_TOLERANCE:
        frappe.throw(
            _("Only {0} of batch {1} is available in {2} (trying to pick {3}).").format(
                flt(available), batch_no, row.get("warehouse"), flt(picked)
            )
        )


def batch_available_qty(batch_no, row):
    if not row.get("warehouse"):
        return None
    try:
        from erpnext.stock.doctype.batch.batch import get_batch_qty

        qty = get_batch_qty(batch_no=batch_no, warehouse=row.warehouse, item_code=row.item_code)
    except Exception:
        return None
    return flt(qty) if isinstance(qty, (int, float)) else None


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

    # Documents created from the desk carry the same links, so they count as well.
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


def make_delivery_notes(pick_list):
    """One draft Delivery Note per customer (mirrors erpnext's create_delivery_note, but built from
    the physically picked quantities and skipping rows that were not picked)."""
    from frappe.model.mapper import map_child_doc
    from erpnext.selling.doctype.sales_order.sales_order import make_delivery_note as make_dn_from_so

    rows = pickable_rows(pick_list)
    item_mapper = {
        "doctype": "Delivery Note Item",
        "field_map": {"rate": "rate", "name": "so_detail", "parent": "against_sales_order"},
    }
    created = []

    so_customers, by_customer = {}, {}
    for row in rows:
        sales_order = row.get("sales_order")
        if sales_order and sales_order not in so_customers:
            so_customers[sales_order] = frappe.db.get_value("Sales Order", sales_order, "customer")
            by_customer.setdefault(so_customers[sales_order], []).append(sales_order)

    for _customer, sales_orders in by_customer.items():
        delivery_note = make_dn_from_so(sales_orders[0], skip_item_mapping=True)
        for row in rows:
            if row.get("sales_order") not in sales_orders:
                continue
            if row.get("sales_order_item"):
                so_item = frappe.get_doc("Sales Order Item", row.sales_order_item)
                dn_item = map_child_doc(so_item, delivery_note, item_mapper)
            else:
                dn_item = delivery_note.append("items", {"item_code": row.item_code, "against_sales_order": row.sales_order})
            fill_delivery_note_item(dn_item, row)
        finalize_delivery_note(delivery_note, pick_list)
        created.append(delivery_note)

    unlinked = [row for row in rows if not row.get("sales_order")]
    if unlinked:
        if not pick_list.get("customer"):
            frappe.throw(_("Pick List {0} has no customer, so a Delivery Note cannot be created.").format(pick_list.name))
        delivery_note = frappe.new_doc("Delivery Note")
        delivery_note.customer = pick_list.customer
        for row in unlinked:
            fill_delivery_note_item(delivery_note.append("items", {"item_code": row.item_code}), row)
        finalize_delivery_note(delivery_note, pick_list)
        created.append(delivery_note)
    return created


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


def finalize_delivery_note(delivery_note, pick_list):
    set_if_field(delivery_note, "pick_list", pick_list.name)
    delivery_note.company = pick_list.company
    delivery_note.run_method("set_missing_values")
    delivery_note.run_method("set_po_nos")
    delivery_note.run_method("calculate_taxes_and_totals")
    delivery_note.flags.ignore_mandatory = True  # same as erpnext's create_delivery_note
    delivery_note.insert()


def make_stock_entry(pick_list, purpose):
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
    stock_entry.insert()
    return stock_entry


# --------------------------------------------------------------------------------------------
# Serialization
# --------------------------------------------------------------------------------------------


def header_fields():
    meta = frappe.get_meta(PICK_LIST)
    return [field for field in HEADER_FIELDS if field in ALWAYS_PRESENT or meta.has_field(field)]


def item_totals(names):
    if not names:
        return {}
    data = frappe.get_all(
        PICK_LIST_ITEM,
        filters={"parent": ["in", names], "parenttype": PICK_LIST},
        fields=[
            "parent",
            "count(name) as item_count",
            "sum(stock_qty) as required_qty",
            "sum(custom_wms_picked_qty) as picked_qty",
        ],
        group_by="parent",
    )
    return {row.parent: row for row in data}


def customer_name_for(customer, cache={}):
    if not customer:
        return None
    if customer not in cache:
        cache[customer] = frappe.db.get_value("Customer", customer, "customer_name") or customer
    return cache[customer]


def str_or_none(value):
    return None if value in (None, "") else str(value)


def serialize_header(source, totals=None):
    totals = totals or {}
    return {
        "name": source.get("name"),
        "purpose": source.get("purpose"),
        "company": source.get("company"),
        "customer": source.get("customer"),
        "customer_name": source.get("customer_name") or customer_name_for(source.get("customer")),
        "parent_warehouse": source.get("parent_warehouse"),
        "target_warehouse": source.get("custom_target_warehouse"),
        "status": source.get("status"),
        "picking_status": source.get("custom_picking_status") or rules.STATUS_READY,
        "picker": source.get("custom_picker"),
        "assigned_to": rules.assigned_users(source.get("_assign")),
        "picking_started_at": str_or_none(source.get("custom_picking_started_at")),
        "picking_started_by": source.get("custom_picking_started_by"),
        "picking_completed_at": str_or_none(source.get("custom_picking_completed_at")),
        "picking_completed_by": source.get("custom_picking_completed_by"),
        "generated_doctype": source.get("custom_generated_doctype"),
        "generated_docname": source.get("custom_generated_docname"),
        "work_order": source.get("work_order"),
        "material_request": source.get("material_request"),
        "modified": str_or_none(source.get("modified")),
        "creation": str_or_none(source.get("creation")),
        "item_count": cint(totals.get("item_count")),
        "required_qty": flt(totals.get("required_qty")),
        "picked_qty": flt(totals.get("picked_qty")),
    }


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


def serialize_pick_list(doc):
    rows = doc.get("locations") or []
    item_codes = list({row.item_code for row in rows if row.item_code})
    batch_nos = list({row.batch_no for row in rows if row.get("batch_no")})

    item_info = {}
    barcodes = {}
    if item_codes:
        for item in frappe.get_all("Item", filters={"name": ["in", item_codes]}, fields=["name", "item_name", "has_batch_no", "has_serial_no"]):
            item_info[item.name] = item
        for barcode in frappe.get_all("Item Barcode", filters={"parent": ["in", item_codes], "parenttype": "Item"}, fields=["parent", "barcode"]):
            barcodes.setdefault(barcode.parent, []).append(barcode.barcode)
    expiries = {}
    if batch_nos:
        for batch in frappe.get_all("Batch", filters={"name": ["in", batch_nos]}, fields=["name", "expiry_date"]):
            expiries[batch.name] = batch.expiry_date
    targets = row_target_warehouses(doc)

    items = []
    for row in rows:
        required = required_qty(row)
        picked = flt(row.get("custom_wms_picked_qty"))
        info = item_info.get(row.item_code) or {}
        items.append(
            {
                "name": row.name,
                "idx": row.idx,
                "item_code": row.item_code,
                "item_name": row.get("item_name") or info.get("item_name") or row.item_code,
                "description": row.get("description"),
                "warehouse": row.get("warehouse"),
                "target_warehouse": targets.get(row.name),
                "batch_no": row.get("batch_no"),
                "expiry_date": str_or_none(expiries.get(row.get("batch_no"))),
                "serial_no": row.get("serial_no"),
                "required_qty": required,
                "picked_qty": picked,
                "uom": row.get("stock_uom") or row.get("uom"),
                "order_qty": flt(row.get("qty")),
                "order_uom": row.get("uom"),
                "conversion_factor": flt(row.get("conversion_factor")) or 1,
                "has_batch_no": cint(info.get("has_batch_no")),
                "has_serial_no": cint(info.get("has_serial_no")),
                "optional": cint(row.get("custom_optional")),
                "barcodes": barcodes.get(row.item_code, []),
                "sales_order": row.get("sales_order"),
                "material_request": row.get("material_request"),
                "row_status": rules.row_status(picked, required),
            }
        )

    header = serialize_header(doc)
    header["item_count"] = len(items)
    header["required_qty"] = sum(item["required_qty"] for item in items)
    header["picked_qty"] = sum(item["picked_qty"] for item in items)
    header["items"] = items
    return header


def pick_row(rows, predicate):
    matches = [row for row in rows if predicate(row)]
    if not matches:
        return None
    for row in matches:  # prefer a row that still needs picking
        if not rules.is_row_complete(row.get("custom_wms_picked_qty"), required_qty(row), cint(row.get("custom_optional"))):
            return row
    return matches[0]


def scan_result(row, match, batch_no=None, expiry_date=None):
    return {
        "row_name": row.name,
        "item_code": row.item_code,
        "batch_no": batch_no,
        "match": match,
        "expiry_date": str_or_none(expiry_date),
    }
