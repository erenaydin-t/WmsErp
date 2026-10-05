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

Which drafts are "at the warehouse stage": the states listed in *WMS Settings > Receipt Workflow
States*, or, when that is empty, the draft states of the Purchase Receipt workflow in which one of
the user's roles may edit. Without a workflow every draft the user may read is listed.

Mandatory fields a site added (a *Department*...) that the receipt lacks are filled from the values
the app sends, the user's defaults and the company's default accounting dimensions; what is still
empty comes back as `missing_fields` (HTTP 200) so the app asks once and calls again.
"""

import frappe
from frappe import _
from frappe.utils import cint, flt

from wmserp_picking import documents
from wmserp_picking.receiving import rules

PURCHASE_RECEIPT = "Purchase Receipt"
PURCHASE_RECEIPT_ITEM = "Purchase Receipt Item"
WORKFLOW_STATE = "workflow_state"

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
    filters = {"docstatus": 0}
    meta = frappe.get_meta(PURCHASE_RECEIPT)
    if meta.has_field("is_return"):
        filters["is_return"] = 0
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
    """One draft receipt with its rows (expected quantities, warehouses, batches, item barcodes)."""
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
    `remove_unreceived=0` with `submit=0` to save the progress of a count without touching them.
    `values`: answers to required fields (`{"Purchase Receipt Item.department": "..."}`).
    """
    doc = frappe.get_doc(PURCHASE_RECEIPT, name)
    doc.check_permission("write")
    if doc.docstatus != 0:
        frappe.throw(_("Purchase Receipt {0} is already {1}.").format(name, _("submitted") if doc.docstatus == 1 else _("cancelled")))
    if not can_receive(doc):
        frappe.throw(
            _("Purchase Receipt {0} is not at the warehouse stage ({1}).").format(name, doc.get(WORKFLOW_STATE) or _("Draft")),
            frappe.PermissionError,
        )

    counted = frappe.parse_json(rows) or []
    try:
        plan = rules.plan_receipt(row_facts(doc), counted, remove_unreceived=cint(remove_unreceived) != 0)
    except rules.ReceivingRuleError as exc:
        frappe.throw(_(str(exc)))

    for child in list(doc.get("items") or []):
        if child.name in plan["removed"]:
            doc.remove(child)
            continue
        update = plan["updates"].get(child.name)
        if update:
            apply_count(child, update)

    missing = documents.fill_required(doc, frappe.parse_json(values) or {}, company=doc.company)
    if missing:
        return documents.MissingRequiredFields(PURCHASE_RECEIPT, missing).response()

    try:
        doc.save()
        submitted = False
        if cint(submit):
            doc = submit_receipt(doc)
            submitted = doc.docstatus == 1
    except frappe.MandatoryError as exc:
        answer = documents.missing_fields_answer(PURCHASE_RECEIPT, exc)
        if answer:
            return answer
        raise
    doc.add_comment("Info", _("Received through the WMS app ({0} row(s), {1} unit(s))").format(len(plan["updates"]), flt(plan["total_qty"])))
    result = serialize_receipt(doc)
    result.update({"created": True, "submitted": submitted, "differences": plan["differences"], "removed_rows": plan["removed"]})
    return result


def apply_count(child, update):
    conversion_factor = flt(child.get("conversion_factor")) or 1
    qty = flt(update["qty"])
    child.received_qty = qty
    child.qty = qty
    child.rejected_qty = 0
    child.stock_qty = qty * conversion_factor
    if child.meta.has_field("received_stock_qty"):
        child.received_stock_qty = qty * conversion_factor
    if update.get("warehouse"):
        child.warehouse = update["warehouse"]
    if update.get("batch_no") and update["batch_no"] != child.get("batch_no"):
        child.batch_no = update["batch_no"]
        if child.meta.has_field("use_serial_batch_fields"):
            child.use_serial_batch_fields = 1  # ERPNext v15+ builds the Serial and Batch Bundle from this


def submit_receipt(doc):
    """Submits through the workflow (the user's action that leads to a submitted state) or directly."""
    from frappe.model.workflow import apply_workflow, get_transitions, get_workflow_name

    workflow_name = get_workflow_name(PURCHASE_RECEIPT)
    if not workflow_name:
        doc.submit()
        return doc
    workflow = frappe.get_cached_doc("Workflow", workflow_name)
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


def receivable_states():
    """Workflow states a receipt must be in to appear in the app, or None when no workflow applies."""
    configured = []
    try:
        configured = rules.parse_states(frappe.get_cached_doc("WMS Settings", "WMS Settings").get("receipt_workflow_states"))
    except Exception:
        configured = []
    workflow = active_workflow()
    if configured:
        return configured
    if not workflow:
        return None
    return rules.editable_states([s.as_dict() for s in workflow.states], frappe.get_roles())


def active_workflow():
    from frappe.model.workflow import get_workflow_name

    name = get_workflow_name(PURCHASE_RECEIPT)
    return frappe.get_cached_doc("Workflow", name) if name else None


def can_receive(doc) -> bool:
    if doc.docstatus != 0:
        return False
    states = receivable_states()
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


def row_facts(doc):
    """The row attributes the receiving rules need."""
    items = {row.item_code for row in doc.get("items") or [] if row.item_code}
    tracking = {}
    if items:
        fields = ["name", "has_batch_no", "has_serial_no"]
        if frappe.get_meta("Item").has_field("create_new_batch"):
            fields.append("create_new_batch")
        for item in frappe.get_all("Item", filters={"name": ["in", list(items)]}, fields=fields):
            tracking[item.name] = item
    facts = []
    for row in doc.get("items") or []:
        info = tracking.get(row.item_code) or frappe._dict()
        facts.append(
            {
                "name": row.name,
                "item_code": row.item_code,
                "qty": flt(row.get("qty")),
                "warehouse": row.get("warehouse"),
                "batch_no": row.get("batch_no"),
                "has_batch_no": bool(cint(info.get("has_batch_no"))),
                "has_serial_no": bool(cint(info.get("has_serial_no"))),
                # An existing batch must be chosen unless ERPNext creates one on submit.
                "needs_batch": bool(cint(info.get("has_batch_no"))) and not row.get("batch_no") and not cint(info.get("create_new_batch")),
            }
        )
    return facts


def serialize_receipt(doc):
    rows = doc.get("items") or []
    facts = {f["name"]: f for f in row_facts(doc)}
    codes = list({row.item_code for row in rows if row.item_code})
    barcodes = {}
    names = {}
    if codes:
        for entry in frappe.get_all("Item Barcode", filters={"parent": ["in", codes], "parenttype": "Item"}, fields=["parent", "barcode"]):
            barcodes.setdefault(entry.parent, []).append(entry.barcode)
        for item in frappe.get_all("Item", filters={"name": ["in", codes]}, fields=["name", "item_name"]):
            names[item.name] = item.item_name
    header = serialize_header(doc, [{"qty": r.get("qty")} for r in rows])
    header.update(
        {
            "can_receive": can_receive(doc) and frappe.has_permission(PURCHASE_RECEIPT, "write", doc),
            "has_workflow": active_workflow() is not None,
            "items": [
                {
                    "name": row.name,
                    "idx": row.idx,
                    "item_code": row.item_code,
                    "item_name": row.get("item_name") or names.get(row.item_code) or row.item_code,
                    "description": row.get("description"),
                    "qty": flt(row.get("qty")),
                    "received_qty": flt(row.get("received_qty")),
                    "rejected_qty": flt(row.get("rejected_qty")),
                    "uom": row.get("uom"),
                    "stock_uom": row.get("stock_uom"),
                    "conversion_factor": flt(row.get("conversion_factor")) or 1,
                    "warehouse": row.get("warehouse"),
                    "batch_no": row.get("batch_no"),
                    "has_batch_no": facts.get(row.name, {}).get("has_batch_no", False),
                    "has_serial_no": facts.get(row.name, {}).get("has_serial_no", False),
                    "needs_batch": facts.get(row.name, {}).get("needs_batch", False),
                    "purchase_order": row.get("purchase_order"),
                    "purchase_order_item": row.get("purchase_order_item"),
                    "barcodes": barcodes.get(row.item_code, []),
                }
                for row in rows
            ],
        }
    )
    return header
