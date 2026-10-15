"""Receiving against draft Purchase Receipts (the WMS app's *Orders > Receive*).

Intended flow
-------------
1. Purchasing creates a Purchase Order and, from it, a *draft* Purchase Receipt.
2. The receipt goes through the site's approval workflow until it reaches the warehouse stage.
3. The warehouse user sees it in the app (`get_receivable`), counts the goods against its rows
   (`get_receipt`), and confirms (`receive`): the counted quantities are written into the rows,
   rows that were not received are dropped (the Purchase Order stays open for them) and the receipt
   is submitted - through the workflow action that submits it when a workflow is active, else
   directly. Only then does the stock enter the warehouse.

Saving the progress of a count (`receive` with `submit=0`, `remove_unreceived=0`) stores what was
counted so far in the row's *WMS Counted Qty* (`custom_wms_counted_qty`) and leaves `qty` - what
purchasing expects - untouched, so the differences reported when the receipt is finally confirmed
and the expected column of the app stay right.

Which drafts are "at the warehouse stage": the states listed in *WMS Settings > Receipt Workflow
States*, or, when that is empty, the draft states of the Purchase Receipt workflow in which one of
the user's roles may edit. Without an active workflow every draft the user may read is listed (the
configured states only apply to a workflow).

Mandatory fields a site added (a *Department*...) that the receipt lacks are filled from the values
the app sends, the user's defaults and the company's default accounting dimensions; what is still
empty comes back as `missing_fields` (HTTP 200) so the app asks once and calls again. Values a site
requires on every confirmed receipt (*WMS Settings > Receipt Field Values*, e.g. the warehouse's
department on each row) are written before the save, overwriting what the draft carries.
"""

import frappe
from frappe import _
from frappe.utils import cint, flt

from wmserp_picking import documents
from wmserp_picking.receiving import rules

PURCHASE_RECEIPT = "Purchase Receipt"
PURCHASE_RECEIPT_ITEM = "Purchase Receipt Item"
WORKFLOW_STATE = "workflow_state"
COUNTED_QTY = "custom_wms_counted_qty"
BUNDLE = "serial_and_batch_bundle"

HEADER_FIELDS = [
    "name",
    "supplier",
    "supplier_name",
    "posting_date",
    "company",
    "set_warehouse",
    "status",
    "docstatus",
    "owner",
    "modified",
    "supplier_delivery_note",
    "total_qty",
    "is_return",
    WORKFLOW_STATE,
]
ALWAYS_PRESENT = {"name", "docstatus", "owner", "modified"}


# --------------------------------------------------------------------------------------------
# Reads
# --------------------------------------------------------------------------------------------


@frappe.whitelist()
def get_receivable(query=None, limit=50):
    """Draft Purchase Receipts waiting for the warehouse (see the module docstring)."""
    filters = {"docstatus": 0, "is_return": 0}
    states = receivable_states()
    if states is not None:
        if not states:
            return []
        filters[WORKFLOW_STATE] = ["in", states]
    or_filters = None
    if query:
        like = f"%{query.strip()}%"
        or_filters = {"name": ["like", like], "supplier": ["like", like], "supplier_name": ["like", like]}
    headers = frappe.get_list(
        PURCHASE_RECEIPT,
        filters=filters,
        or_filters=or_filters,
        fields=header_fields(),
        order_by="posting_date desc, modified desc",
        limit_page_length=cint(limit) or 50,
    )
    if not headers:
        return []
    rows = frappe.get_all(
        PURCHASE_RECEIPT_ITEM,
        filters={"parent": ["in", [h.name for h in headers]], "parenttype": PURCHASE_RECEIPT},
        fields=["parent", "qty", "item_code"],
    )
    by_parent = {}
    for row in rows:
        by_parent.setdefault(row.parent, []).append(row)
    return [serialize_header(header, by_parent.get(header.name, [])) for header in headers]


@frappe.whitelist()
def get_receipt(name):
    """One draft receipt with its rows (expected quantities, counts so far, warehouses, batches,
    item barcodes)."""
    doc = frappe.get_doc(PURCHASE_RECEIPT, name)
    doc.check_permission("read")
    return serialize_receipt(doc)


# --------------------------------------------------------------------------------------------
# Receive
# --------------------------------------------------------------------------------------------


@frappe.whitelist()
def receive(name, rows, values=None, submit=1, remove_unreceived=1):
    """Writes the counted quantities into the draft and submits it.

    `rows`: `[{"row": <Purchase Receipt Item name>, "qty": <counted, row UOM>, "warehouse"?, "batch_no"?}]`.
    Rows missing from `rows` (or with 0) are removed when `remove_unreceived`; the app sends
    `remove_unreceived=0` with `submit=0` to save the progress of a count: the counts then go to
    *WMS Counted Qty* only and the expected `qty` of every row is kept.
    `values`: answers to required fields (`{"Purchase Receipt Item.department": "..."}`).
    """
    doc = frappe.get_doc(PURCHASE_RECEIPT, name)
    doc.check_permission("write")
    if doc.docstatus != 0:
        frappe.throw(_("Purchase Receipt {0} is already {1}.").format(name, _("submitted") if doc.docstatus == 1 else _("cancelled")))
    stage = workflow_stage()
    if not can_receive(doc, stage):
        frappe.throw(
            _("Purchase Receipt {0} is not at the warehouse stage ({1}).").format(name, doc.get(WORKFLOW_STATE) or _("Draft")),
            frappe.PermissionError,
        )

    progress = not cint(submit) and not cint(remove_unreceived)
    if progress and not frappe.get_meta(PURCHASE_RECEIPT_ITEM).has_field(COUNTED_QTY):
        frappe.throw(_("Purchase Receipt Item has no {0} field yet: run bench migrate for wmserp_picking before saving the progress of a count.").format("WMS Counted Qty"))
    facts = row_facts(doc)
    counted = frappe.parse_json(rows) or []
    try:
        plan = rules.plan_receipt(facts, counted, remove_unreceived=cint(remove_unreceived) != 0)
    except rules.ReceivingRuleError as exc:
        frappe.throw(_(str(exc)))

    facts_by_row = {fact["name"]: fact for fact in facts}
    stale_bundles = []
    for child in list(doc.get("items") or []):
        if child.name in plan["removed"]:
            doc.remove(child)
            continue
        update = plan["updates"].get(child.name)
        if update:
            stale_bundles.extend(apply_count(child, update, facts_by_row[child.name], progress=progress))
        elif child.name in plan["cleared"]:
            child.set(COUNTED_QTY, 0)  # counted earlier, now sent as 0 while saving progress

    if not progress:
        apply_field_values(doc, configured_field_values())
    missing = documents.fill_required(doc, frappe.parse_json(values) or {}, company=doc.company)
    if missing:
        return documents.MissingRequiredFields(PURCHASE_RECEIPT, missing).response()

    try:
        doc.save()
        discard_bundles(stale_bundles)  # while the receipt is still a draft: a submitted voucher locks its bundles
        submitted = False
        if cint(submit):
            doc = submit_receipt(doc, stage)
            submitted = doc.docstatus == 1
    except frappe.MandatoryError as exc:
        answer = documents.missing_fields_answer(PURCHASE_RECEIPT, exc)
        if answer:
            return answer
        raise
    if progress:
        comment = _("Count saved through the WMS app ({0} row(s), {1} unit(s) counted so far)")
    else:
        comment = _("Received through the WMS app ({0} row(s), {1} unit(s))")
    doc.add_comment("Info", comment.format(len(plan["updates"]), flt(plan["total_qty"])))
    result = serialize_receipt(doc, facts=row_facts(doc), stage=stage)
    result.update({"created": True, "submitted": submitted, "differences": plan["differences"], "removed_rows": plan["removed"]})
    return result


def apply_count(child, update, fact, progress=False) -> list:
    """Writes one counted row.

    While a count is in progress only *WMS Counted Qty*, the warehouse and the batch change: `qty`
    keeps what purchasing expects. Confirming writes the counted quantity into the row (`qty`,
    `received_qty`, `stock_qty`).

    ERPNext keeps the batch of a draft row in a *Serial and Batch Bundle* when purchasing chose it
    through the bundle dialog. A bundle whose batch or quantity no longer matches the row is
    unlinked and the batch written into `batch_no` with `use_serial_batch_fields`, from which
    ERPNext builds a fresh bundle on submit. Returns the unlinked bundles (deleted once the receipt
    is saved, before it is submitted)."""
    qty = flt(update["qty"])
    stale = []
    child.set(COUNTED_QTY, qty)
    if update.get("warehouse"):
        child.warehouse = update["warehouse"]

    current_batch = child.get("batch_no") or fact.get("batch_no")
    batch_no = update.get("batch_no") or current_batch
    batch_changed = bool(batch_no) and batch_no != current_batch
    qty_changed = abs(qty - flt(child.get("qty"))) > rules.QTY_TOLERANCE
    bundle = child.get(BUNDLE)
    if bundle and (batch_changed or (qty_changed and not progress)):
        stale.append(bundle)
        child.set(BUNDLE, None)
        if child.get("rejected_serial_and_batch_bundle"):
            stale.append(child.get("rejected_serial_and_batch_bundle"))
            child.set("rejected_serial_and_batch_bundle", None)
        if not batch_no:
            frappe.throw(
                _("Row {0} ({1}): its Serial and Batch Bundle holds several batches, so the quantity cannot be changed from the app. Adjust the bundle in ERPNext or scan the batch received.").format(
                    child.idx, child.item_code
                )
            )
    keeps_bundle = bool(bundle) and bundle not in stale
    if batch_no and not keeps_bundle and (batch_changed or not child.get("batch_no")):
        child.batch_no = batch_no
        child.use_serial_batch_fields = 1  # ERPNext builds the Serial and Batch Bundle from this on submit

    if progress:
        return stale
    conversion_factor = flt(child.get("conversion_factor")) or 1
    child.received_qty = qty
    child.qty = qty
    child.rejected_qty = 0
    child.stock_qty = qty * conversion_factor
    child.received_stock_qty = qty * conversion_factor
    return stale


def configured_field_values() -> dict:
    """*WMS Settings > Receipt Field Values*, parsed."""
    try:
        text = frappe.get_cached_doc("WMS Settings", "WMS Settings").get("receipt_field_values")
    except Exception:
        return {}
    try:
        return rules.parse_field_values(text)
    except rules.ReceivingRuleError as exc:
        frappe.throw(_(str(exc)))


def apply_field_values(doc, values):
    """Writes the configured values onto the receipt before it is confirmed: a key
    `Purchase Receipt.<field>` sets the header, `Purchase Receipt Item.<field>` every row, a bare
    fieldname whichever of the two has it (header first). Unknown fields are ignored. Sites whose
    submit rules require the warehouse's own values (a *Department*...) configure them once here
    instead of editing every draft by hand."""
    header_meta, row_meta = frappe.get_meta(PURCHASE_RECEIPT), frappe.get_meta(PURCHASE_RECEIPT_ITEM)
    for key, value in (values or {}).items():
        doctype, fieldname = rules.split_field_key(key)
        on_header = doctype in (None, PURCHASE_RECEIPT) and header_meta.has_field(fieldname)
        on_rows = doctype == PURCHASE_RECEIPT_ITEM or (doctype is None and not on_header)
        if on_header:
            doc.set(fieldname, value)
        if on_rows and row_meta.has_field(fieldname):
            for row in doc.get("items") or []:
                row.set(fieldname, value)


def discard_bundles(names):
    """Deletes the draft Serial and Batch Bundles a receipt no longer references (best effort: an
    orphaned draft bundle is harmless, ERPNext ignores it)."""
    for name in names:
        try:
            if frappe.db.get_value("Serial and Batch Bundle", name, "docstatus") == 0:
                frappe.delete_doc("Serial and Batch Bundle", name, ignore_permissions=True, force=True)
        except Exception:
            frappe.log_error(title=f"WMS receiving: could not delete bundle {name}", message=frappe.get_traceback())


def submit_receipt(doc, stage=None):
    """Submits through the workflow (the user's action that leads to a submitted state) or directly."""
    from frappe.model.workflow import apply_workflow, get_transitions

    workflow = (stage or workflow_stage())["workflow"]
    if not workflow:
        doc.submit()
        return doc
    docstatus = {state.state: str(state.doc_status) for state in workflow.states}
    action = rules.choose_submit_action(get_transitions(doc, workflow), docstatus)
    if not action:
        frappe.throw(
            _("No workflow action available to you submits Purchase Receipt {0} from state {1}. Ask an administrator to allow your role to receive at this stage.").format(
                doc.name, doc.get(workflow.workflow_state_field) or _("Draft")
            ),
            frappe.PermissionError,
        )
    return apply_workflow(doc, action)


# --------------------------------------------------------------------------------------------
# Workflow stage
# --------------------------------------------------------------------------------------------


def workflow_stage() -> dict:
    """`{"workflow": <active Purchase Receipt Workflow or None>, "states": <receivable states or None>}`,
    computed once per request."""
    workflow = active_workflow()
    return {"workflow": workflow, "states": receivable_states(workflow)}


def receivable_states(workflow=None):
    """Workflow states a receipt must be in to appear in the app, or None when no workflow applies
    (every draft is receivable then). The states configured in *WMS Settings* only mean something
    while a Purchase Receipt workflow is active and the `workflow_state` field exists."""
    if workflow is None:
        workflow = active_workflow()
    if not workflow or not frappe.get_meta(PURCHASE_RECEIPT).has_field(WORKFLOW_STATE):
        return None
    configured = []
    try:
        configured = rules.parse_states(frappe.get_cached_doc("WMS Settings", "WMS Settings").get("receipt_workflow_states"))
    except Exception:
        configured = []
    if configured:
        return configured
    return rules.editable_states([s.as_dict() for s in workflow.states], frappe.get_roles())


def active_workflow():
    from frappe.model.workflow import get_workflow_name

    name = get_workflow_name(PURCHASE_RECEIPT)
    return frappe.get_cached_doc("Workflow", name) if name else None


def can_receive(doc, stage=None) -> bool:
    if doc.docstatus != 0:
        return False
    states = (stage or workflow_stage())["states"]
    if states is None:
        return True
    return (doc.get(WORKFLOW_STATE) or "") in states


# --------------------------------------------------------------------------------------------
# Serialization
# --------------------------------------------------------------------------------------------


def header_fields():
    meta = frappe.get_meta(PURCHASE_RECEIPT)
    return [field for field in HEADER_FIELDS if field in ALWAYS_PRESENT or meta.has_field(field)]


def str_or_none(value):
    return None if value in (None, "") else str(value)


def serialize_header(source, rows):
    return {
        "name": source.get("name"),
        "supplier": source.get("supplier"),
        "supplier_name": source.get("supplier_name") or source.get("supplier"),
        "posting_date": str_or_none(source.get("posting_date")),
        "company": source.get("company"),
        "set_warehouse": source.get("set_warehouse"),
        "status": source.get("status"),
        "workflow_state": source.get(WORKFLOW_STATE),
        "docstatus": cint(source.get("docstatus")),
        "supplier_delivery_note": source.get("supplier_delivery_note"),
        "item_count": len(rows),
        "total_qty": sum(flt(r.get("qty")) for r in rows),
        "modified": str_or_none(source.get("modified")),
    }


def bundle_batches(bundles) -> dict:
    """{bundle name: its single batch or None} for the rows whose batch lives in a bundle."""
    if not bundles:
        return {}
    entries = {}
    for entry in frappe.get_all("Serial and Batch Entry", filters={"parent": ["in", list(bundles)]}, fields=["parent", "batch_no"]):
        entries.setdefault(entry.parent, []).append(entry)
    return {name: rules.bundle_batch(entries.get(name)) for name in bundles}


def row_facts(doc):
    """The row attributes the receiving rules and the app need (one Item query, one bundle query)."""
    rows = doc.get("items") or []
    items = {row.item_code for row in rows if row.item_code}
    tracking = {}
    if items:
        fields = ["name", "item_name", "has_batch_no", "has_serial_no", "create_new_batch"]
        for item in frappe.get_all("Item", filters={"name": ["in", list(items)]}, fields=fields):
            tracking[item.name] = item
    batches = bundle_batches({row.get(BUNDLE) for row in rows if row.get(BUNDLE) and not row.get("batch_no")})
    facts = []
    for row in rows:
        info = tracking.get(row.item_code) or frappe._dict()
        has_batch_no = bool(cint(info.get("has_batch_no")))
        bundle = row.get(BUNDLE)
        batch_no = row.get("batch_no") or (batches.get(bundle) if bundle else None)
        facts.append(
            {
                "name": row.name,
                "item_code": row.item_code,
                "item_name": info.get("item_name"),
                "qty": flt(row.get("qty")),
                "counted_qty": flt(row.get(COUNTED_QTY)),
                "warehouse": row.get("warehouse"),
                "batch_no": batch_no,
                "bundle": bundle,
                "has_batch_no": has_batch_no,
                "has_serial_no": bool(cint(info.get("has_serial_no"))),
                # An existing batch must be chosen unless the row already carries one (batch field
                # or bundle) or ERPNext creates one on submit.
                "needs_batch": has_batch_no and not batch_no and not bundle and not cint(info.get("create_new_batch")),
            }
        )
    return facts


def serialize_receipt(doc, facts=None, stage=None):
    rows = doc.get("items") or []
    facts = {f["name"]: f for f in (facts if facts is not None else row_facts(doc))}
    stage = stage or workflow_stage()
    codes = list({row.item_code for row in rows if row.item_code})
    barcodes = {}
    if codes:
        for entry in frappe.get_all("Item Barcode", filters={"parent": ["in", codes], "parenttype": "Item"}, fields=["parent", "barcode"]):
            barcodes.setdefault(entry.parent, []).append(entry.barcode)
    header = serialize_header(doc, [{"qty": r.get("qty")} for r in rows])
    header.update(
        {
            "can_receive": can_receive(doc, stage) and frappe.has_permission(PURCHASE_RECEIPT, "write", doc),
            "has_workflow": stage["workflow"] is not None,
            "items": [serialize_row(row, facts.get(row.name) or {}, barcodes) for row in rows],
        }
    )
    return header


def serialize_row(row, fact, barcodes):
    return {
        "name": row.name,
        "idx": row.idx,
        "item_code": row.item_code,
        "item_name": row.get("item_name") or fact.get("item_name") or row.item_code,
        "description": row.get("description"),
        "qty": flt(row.get("qty")),
        "counted_qty": fact.get("counted_qty", 0.0),
        "received_qty": flt(row.get("received_qty")),
        "rejected_qty": flt(row.get("rejected_qty")),
        "uom": row.get("uom"),
        "stock_uom": row.get("stock_uom"),
        "conversion_factor": flt(row.get("conversion_factor")) or 1,
        "warehouse": row.get("warehouse"),
        "batch_no": fact.get("batch_no") or row.get("batch_no"),
        "has_batch_no": fact.get("has_batch_no", False),
        "has_serial_no": fact.get("has_serial_no", False),
        "needs_batch": fact.get("needs_batch", False),
        "purchase_order": row.get("purchase_order"),
        "purchase_order_item": row.get("purchase_order_item"),
        "barcodes": barcodes.get(row.item_code, []),
    }
