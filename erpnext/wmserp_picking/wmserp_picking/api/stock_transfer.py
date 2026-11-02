"""Stock transfers started from the app's scan menu (item or batch label -> another warehouse).

Called as `/api/method/wmserp_picking.api.stock_transfer.<function>`. Until 0.8.0 the app inserted
and submitted the Stock Entry itself through the REST API, so nothing filled the fields a site makes
mandatory (a *Department*...), and a failed submit left a draft behind. Now:

* `get_transfer_form` tells the app which choices the site needs: the *Department* (header / rows)
  and the *Received By (Employee)* field, both chosen by the user in the transfer sheet.
* `create_transfer` creates one **draft** *Material Transfer* Stock Entry: the department and the
  receiving employee the user chose, the officer processing it, the scanned batch or, for a batch
  tracked item scanned without a batch, the batches ERPNext would pick (FEFO / FIFO as in *Stock
  Settings*, reserved stock left out), split over as many rows as needed. Other mandatory fields are
  filled like every document the app creates, or reported as `missing_fields` so the app asks.
"""

import frappe
from frappe import _
from frappe.utils import cint, flt, getdate, nowdate

from wmserp_picking import documents
from wmserp_picking.picking import rules

STOCK_ENTRY = "Stock Entry"
STOCK_ENTRY_DETAIL = "Stock Entry Detail"


class InsufficientStockError(frappe.ValidationError):
    """The source warehouse does not hold the quantity to move (for the batch, or in batches)."""


# --------------------------------------------------------------------------------------------
# Form
# --------------------------------------------------------------------------------------------


def department_field():
    """The department field of Stock Entry (header, else rows) as `{fieldname, label, options}`."""
    for doctype in (STOCK_ENTRY, STOCK_ENTRY_DETAIL):
        df = frappe.get_meta(doctype).get_field("department")
        if df:
            return {"fieldname": "department", "label": _(df.label or "Department"), "options": df.options or "Department", "reqd": cint(df.reqd)}
    return None


def received_by_field():
    """The site's *Received By (Employee)* field of Stock Entry, found by its label."""
    df = documents.field_by_labels(STOCK_ENTRY, documents.RECEIVED_BY_LABELS)
    if df is None:
        return None
    options = df.options if df.fieldtype in ("Link", "Dynamic Link") else None
    return {"fieldname": df.fieldname, "label": _(df.label), "options": options or "Employee", "fieldtype": df.fieldtype}


@frappe.whitelist()
def get_transfer_form(item_code=None):
    """What the transfer sheet asks besides warehouses and quantity: the department and the
    receiving employee when the site's Stock Entry has those fields (null otherwise), and whether
    the item is batch tracked (batches are then taken automatically unless a batch was scanned)."""
    has_batch_no = cint(frappe.db.get_value("Item", item_code, "has_batch_no")) if item_code else 0
    return {"department": department_field(), "received_by": received_by_field(), "has_batch_no": bool(has_batch_no)}


# --------------------------------------------------------------------------------------------
# Creation
# --------------------------------------------------------------------------------------------


@frappe.whitelist()
def create_transfer(item_code, qty, s_warehouse, t_warehouse, batch_no=None, department=None, received_by=None, values=None, remarks=None):
    """Creates the draft Material Transfer. Returns `{doctype, name, docstatus, items, created}`,
    or `{missing_fields, created: false}` (HTTP 200) when the site needs values the app must ask
    for; nothing is inserted then."""
    values = frappe.parse_json(values) or {}
    qty = flt(qty)
    item = validate_request(item_code, qty, s_warehouse, t_warehouse)
    form = {"department": department_field(), "received_by": received_by_field()}
    if form["department"] and not (department or "").strip():
        frappe.throw(_("Select the department of this transfer."))
    if form["received_by"] and not (received_by or "").strip():
        frappe.throw(_("Select who receives this transfer ({0}).").format(form["received_by"]["label"]))

    parts = transfer_parts(item, qty, s_warehouse, (batch_no or "").strip() or None)
    company = frappe.db.get_value("Warehouse", s_warehouse, "company")
    doc = build_stock_entry(item, parts, s_warehouse, t_warehouse, company, remarks)
    if form["department"]:
        set_department(doc, department.strip())
    if form["received_by"]:
        doc.set(form["received_by"]["fieldname"], received_by.strip())
    documents.stamp_officer(doc)

    missing = documents.fill_required(doc, values, sources=[], company=company)
    if missing:
        return documents.MissingRequiredFields(STOCK_ENTRY, missing).response()
    try:
        doc.insert()
    except frappe.MandatoryError as exc:
        answer = documents.missing_fields_answer(STOCK_ENTRY, exc)
        if answer:
            return answer
        raise
    return {
        "doctype": doc.doctype,
        "name": doc.name,
        "docstatus": doc.docstatus,
        "created": True,
        "items": [{"item_code": row.item_code, "batch_no": row.get("batch_no"), "qty": flt(row.qty)} for row in doc.items],
    }


def validate_request(item_code, qty, s_warehouse, t_warehouse):
    if qty <= rules.QTY_TOLERANCE:
        frappe.throw(_("The quantity must be greater than zero."))
    if not s_warehouse or not t_warehouse:
        frappe.throw(_("Choose the source and the target warehouse."))
    if s_warehouse == t_warehouse:
        frappe.throw(_("The source and the target warehouse must differ."))
    item = frappe.db.get_value("Item", item_code, ["name", "item_name", "stock_uom", "has_batch_no", "is_stock_item", "disabled"], as_dict=True)
    if not item:
        frappe.throw(_("Item {0} does not exist.").format(item_code))
    if not cint(item.is_stock_item):
        frappe.throw(_("{0} is not a stock item.").format(item_code))
    if cint(item.disabled):
        frappe.throw(_("Item {0} is disabled.").format(item_code))
    return item


def transfer_parts(item, qty, s_warehouse, batch_no=None) -> list[dict]:
    """`[{batch_no, qty}]`: the scanned batch, the batches ERPNext would pick for a batch tracked
    item, or one part without a batch. The source warehouse must hold the quantity."""
    if batch_no:
        check_batch(item, batch_no)
        available = batch_qty(item.name, s_warehouse, batch_no)
        if available + rules.QTY_TOLERANCE < qty:
            frappe.throw(
                _("Batch {0} has only {1} in {2}, but {3} would be moved.").format(batch_no, flt(available), s_warehouse, flt(qty)),
                InsufficientStockError,
            )
        return [{"batch_no": batch_no, "qty": qty}]
    if cint(item.has_batch_no):
        try:
            return rules.allocate_batches(auto_batches(item.name, s_warehouse, qty), qty)
        except rules.PickingRuleError as exc:
            frappe.throw(_("{0} in {1}: {2}.").format(item.name, s_warehouse, str(exc)), InsufficientStockError)
    available = flt(frappe.db.get_value("Bin", {"item_code": item.name, "warehouse": s_warehouse}, "actual_qty"))
    if available + rules.QTY_TOLERANCE < qty:
        frappe.throw(_("{0} has only {1} in {2}, but {3} would be moved.").format(item.name, available, s_warehouse, flt(qty)), InsufficientStockError)
    return [{"batch_no": None, "qty": qty}]


def check_batch(item, batch_no):
    batch = frappe.db.get_value("Batch", batch_no, ["item", "disabled", "expiry_date"], as_dict=True)
    if not batch:
        frappe.throw(_("Batch {0} does not exist.").format(batch_no))
    if batch.item != item.name:
        frappe.throw(_("Batch {0} belongs to item {1}, not {2}.").format(batch_no, batch.item, item.name))
    if cint(batch.disabled):
        frappe.throw(_("Batch {0} is disabled.").format(batch_no))
    if batch.expiry_date and getdate(batch.expiry_date) < getdate(nowdate()):
        frappe.throw(_("Batch {0} expired on {1}.").format(batch_no, frappe.format(batch.expiry_date, "Date")))


def batch_qty(item_code, warehouse, batch_no) -> float:
    from erpnext.stock.doctype.batch.batch import get_batch_qty

    return flt(get_batch_qty(batch_no=batch_no, warehouse=warehouse, item_code=item_code))


def auto_batches(item_code, warehouse, qty) -> list[dict]:
    """Batches of the item in the warehouse in the order ERPNext picks them (*Stock Settings >
    Pick Serial / Batch Based On*), stock reserved for other documents left out; expired and
    disabled batches skipped."""
    from erpnext.stock.doctype.serial_and_batch_bundle.serial_and_batch_bundle import get_auto_batch_nos

    based_on = frappe.db.get_single_value("Stock Settings", "pick_serial_and_batch_based_on")
    rows = get_auto_batch_nos(frappe._dict(item_code=item_code, warehouse=warehouse, qty=qty, based_on=based_on))
    names = [r.get("batch_no") for r in rows if r.get("batch_no")]
    usable = set()
    if names:
        today = getdate(nowdate())
        for batch in frappe.get_all("Batch", filters={"name": ["in", names]}, fields=["name", "disabled", "expiry_date"]):
            if not cint(batch.disabled) and not (batch.expiry_date and getdate(batch.expiry_date) < today):
                usable.add(batch.name)
    return [{"batch_no": r.get("batch_no"), "qty": flt(r.get("qty"))} for r in rows if r.get("batch_no") in usable and flt(r.get("qty")) > 0]


def build_stock_entry(item, parts, s_warehouse, t_warehouse, company, remarks=None):
    doc = frappe.new_doc(STOCK_ENTRY)
    doc.company = company
    doc.purpose = rules.PURPOSE_MATERIAL_TRANSFER
    doc.set_stock_entry_type()
    doc.from_warehouse = s_warehouse
    doc.to_warehouse = t_warehouse
    doc.remarks = remarks or _("Created from the WMS ERP mobile scan menu")
    for part in parts:
        row = doc.append(
            "items",
            {
                "item_code": item.name,
                "item_name": item.item_name,
                "s_warehouse": s_warehouse,
                "t_warehouse": t_warehouse,
                "qty": part["qty"],
                "transfer_qty": part["qty"],
                "uom": item.stock_uom,
                "stock_uom": item.stock_uom,
                "conversion_factor": 1,
            },
        )
        if part.get("batch_no"):
            row.batch_no = part["batch_no"]
            row.use_serial_batch_fields = 1
    doc.run_method("set_missing_values")
    return doc


def set_department(doc, department):
    """The department the user chose, on the header and on every row that has the field."""
    if doc.meta.has_field("department"):
        doc.set("department", department)
    if frappe.get_meta(STOCK_ENTRY_DETAIL).has_field("department"):
        for row in doc.get("items") or []:
            row.set("department", department)
