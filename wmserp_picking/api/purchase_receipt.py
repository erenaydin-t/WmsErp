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

Batch tracked items (`has_batch_no` without `create_new_batch`): the Batch must exist before the
receipt is submitted. The app reads the supplier's batch number off the material, `find_batch`
looks it up (by Batch ID or by the site's batch number field, spaces and case ignored) and
`create_batch` creates it when it is new, named after *WMS Settings > Batch ID Pattern*. One
shipment may bring the same item under several batch numbers: the app counts each batch under the
row (`rows[].batches`), the progress save keeps the per-batch counts in *WMS Counted Batches*, and
the confirmation splits the row into one Purchase Receipt Item per batch (same Purchase Order row,
rate, UOM and warehouse), which is how ERPNext records one bundle and one stock ledger entry per
batch - the same shape desk users give receipts of several batches.

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
COUNTED_BATCHES = "custom_wms_counted_batches"
BUNDLE = "serial_and_batch_bundle"
BATCH = "Batch"
# Fields of a Purchase Receipt Item that a copy made for a further batch must not take over.
ROW_COPY_SKIPPED = {
    "name", "idx", "owner", "creation", "modified", "modified_by", "docstatus", "parent", "parentfield", "parenttype",
    "doctype", "batch_no", "serial_no", BUNDLE, "rejected_serial_and_batch_bundle", "use_serial_batch_fields",
    COUNTED_QTY, COUNTED_BATCHES, "custom_batch_expiry_date",
}
# The pricing block a split-off row must keep from its source row. Sites put these fields at a
# permission level the warehouse role lacks; Frappe then resets them to their defaults on every row
# that role adds (and to the stored values on rows it edits), so ERPNext re-prices the new row at
# the price list rate. `restore_split_pricing` writes the source row's values to the stored row.
PRICING_FIELDS = ("price_list_rate", "discount_percentage", "discount_amount", "margin_type", "margin_rate_or_amount", "rate_with_margin", "rate", "expense_account", "item_tax_rate")
BATCH_ITEM_FIELDS = ["name", "item_name", "has_batch_no", "has_serial_no", "create_new_batch", "has_expiry_date", "shelf_life_in_days", "stock_uom"]

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

    `rows`: `[{"row": <Purchase Receipt Item name>, "qty": <counted, row UOM>, "warehouse"?, "batch_no"?,
    "batches"?: [{"batch_no", "qty"}, ...]}]`; with `batches` the row's quantity is their sum and a
    confirmed row is split into one Purchase Receipt Item per batch.
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
    extras = []  # (source row, further batches) to split off once the loop is done
    splits = []  # (source row, its copy) to re-check after the save
    for child in list(doc.get("items") or []):
        if child.name in plan["removed"]:
            doc.remove(child)
            continue
        update = plan["updates"].get(child.name)
        if update:
            stale_bundles.extend(apply_count(child, update, facts_by_row[child.name], progress=progress))
            if not progress:
                _first, more = rules.split_batches(update)
                if more:
                    extras.append((child, more))
        elif child.name in plan["cleared"]:
            child.set(COUNTED_QTY, 0)  # counted earlier, now sent as 0 while saving progress
            if row_meta_has(COUNTED_BATCHES):
                child.set(COUNTED_BATCHES, None)
    for child, more in extras:
        splits.extend((child, copy) for copy in split_row(doc, child, more))

    if not progress:
        apply_field_values(doc, configured_field_values())
    missing = documents.fill_required(doc, frappe.parse_json(values) or {}, company=doc.company)
    if missing:
        return documents.MissingRequiredFields(PURCHASE_RECEIPT, missing).response()

    try:
        doc.save()
        restore_split_pricing(doc, splits)
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
    if row_meta_has(COUNTED_BATCHES):
        # The per-batch counts survive a progress save; a confirmed row carries one batch only.
        child.set(COUNTED_BATCHES, rules.serialize_counted_batches(update.get("batches")) if progress else None)
    if update.get("warehouse"):
        child.warehouse = update["warehouse"]

    first, _more = rules.split_batches(update)
    if not progress:
        qty = flt(first["qty"])  # the first batch stays on this row; the others get their own rows
        child.set(COUNTED_QTY, qty)
    current_batch = child.get("batch_no") or fact.get("batch_no")
    batch_no = first.get("batch_no") or current_batch
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


def split_row(doc, source, batches):
    """Appends one copy of `source` per further batch, right after it: same item, Purchase Order
    row, rate, UOM, conversion factor, warehouse and site fields; its own batch and quantity.
    ERPNext's over-receipt check sums every row of a Purchase Order row, so the split cannot
    receive more than a single row could."""
    items = doc.get("items") or []
    position = items.index(source)
    conversion_factor = flt(source.get("conversion_factor")) or 1
    template = {k: v for k, v in source.as_dict().items() if k not in ROW_COPY_SKIPPED and not str(k).startswith("__")}
    copies = []
    for batch in batches:
        qty = flt(batch["qty"])
        copy = doc.append("items", dict(template))
        copy.batch_no = batch["batch_no"]
        copy.use_serial_batch_fields = 1
        copy.qty = qty
        copy.received_qty = qty
        copy.rejected_qty = 0
        copy.stock_qty = qty * conversion_factor
        copy.received_stock_qty = qty * conversion_factor
        copy.set(COUNTED_QTY, qty)
        copies.append(copy)
    # `append` adds at the end: move the copies behind their source and renumber.
    rows = [row for row in doc.get("items") if row not in copies]
    rows[position + 1 : position + 1] = copies
    for index, row in enumerate(rows, start=1):
        row.idx = index
    doc.set("items", rows)
    return copies


def pricing_differs(copy, source) -> bool:
    for field in PRICING_FIELDS:
        a, b = copy.get(field), source.get(field)
        if isinstance(a, str) or isinstance(b, str):
            if (a or "") != (b or ""):
                return True
        elif abs(flt(a) - flt(b)) > 1e-6:
            return True
    return False


def restore_split_pricing(doc, splits):
    """Split-off rows that lost their source row's pricing on the first save (the warehouse role
    lacks the permission level of the pricing fields, so Frappe reset them to defaults and ERPNext
    priced the row at the price list rate) get the source values written to the stored row; the
    receipt is then reloaded and saved again, which recomputes the amounts and totals from the
    restored rates - stored values of a protected field are what Frappe keeps for an existing row.
    Refuses to go on when the rates still differ: a batch row must never be valued at list price."""
    changed = [(source, copy) for source, copy in splits if pricing_differs(copy, source)]
    if not changed:
        return
    for source, copy in changed:
        values = {field: source.get(field) for field in PRICING_FIELDS if doc.meta.get_field("items") and frappe.get_meta(PURCHASE_RECEIPT_ITEM).has_field(field)}
        frappe.db.set_value(PURCHASE_RECEIPT_ITEM, copy.name, values, update_modified=False)
    doc.reload()
    doc.save()
    rows = {row.name: row for row in doc.get("items") or []}
    for source, copy in changed:
        row = rows.get(copy.name)
        if row is None or pricing_differs(row, source):
            frappe.throw(
                _("Row {0} ({1}): ERPNext priced the batch row at {2} instead of the Purchase Order rate {3}. Ask an administrator to give your role the permission level of the Purchase Receipt Item pricing fields, then receive again.").format(
                    copy.idx, copy.item_code, flt((row or copy).get("rate")), flt(source.get("rate"))
                )
            )


def row_meta_has(fieldname) -> bool:
    return frappe.get_meta(PURCHASE_RECEIPT_ITEM).has_field(fieldname)


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
# Batches
# --------------------------------------------------------------------------------------------


@frappe.whitelist()
def find_batch(code, item_code=None, receipt=None):
    """The existing Batches a scanned or typed batch number stands for.

    `code` is matched against the Batch ID / name and, on sites that keep the supplier's batch
    number in its own field (*WMS Settings > Batch No Field*, `custom_batch_no` by default when it
    exists), against that field with spaces and case ignored. The search is limited to `item_code`
    or to the items of draft receipt `receipt`, so a bare supplier number cannot land on another
    item. Several batches can share one supplier number (a site that keeps a QC number per batch
    creates one Batch per QC run): the app lets the warehouse pick one. Returns
    `{"code", "found", "batches": [...], "item": <item facts, when item_code was given>}`.
    """
    code = (code or "").strip()
    if not code:
        frappe.throw(_("Scan or enter a batch number."))
    items = []
    if item_code:
        items = [item_code]
    elif receipt:
        doc = frappe.get_doc(PURCHASE_RECEIPT, receipt)
        doc.check_permission("read")
        items = list({row.item_code for row in doc.get("items") or [] if row.item_code})
    frappe.has_permission(BATCH, "read", throw=True)
    matches = search_batches(code, items)
    item = batch_item_facts(item_code) if item_code else None
    return {"code": code, "found": bool(matches), "batches": [serialize_batch(b) for b in matches], "item": item}


def search_batches(code, items) -> list:
    """Batches named `code` (name / Batch ID) or carrying it as supplier number, among `items`
    (every item when empty). The supplier number is compared with spaces and case ignored, the way
    `rules.batch_key` does it."""
    field = batch_no_field()
    fields = ["name", "batch_id", "item", "item_name", "expiry_date", "manufacturing_date", "disabled", "supplier"] + ([field] if field else [])
    filters = {"item": ["in", items]} if items else {}
    out = list(frappe.get_all(BATCH, filters={**filters, "name": code}, fields=fields))
    seen = {b.name for b in out}
    for batch in frappe.get_all(BATCH, filters={**filters, "batch_id": code}, fields=fields):
        if batch.name not in seen:
            out.append(batch)
            seen.add(batch.name)
    key = rules.batch_key(code)
    if field and key:
        columns = ", ".join(f"`{f}`" for f in fields)
        conditions = ["lower(regexp_replace(ifnull(`{0}`, ''), '[[:space:]]', '')) = %(key)s".format(field)]
        values = {"key": key}
        if items:
            conditions.append("`item` in %(items)s")
            values["items"] = tuple(items)
        for batch in frappe.db.sql(f"select {columns} from `tabBatch` where {' and '.join(conditions)} order by creation desc limit 50", values, as_dict=True):
            if batch.name not in seen:
                out.append(batch)
                seen.add(batch.name)
    return out


@frappe.whitelist()
def create_batch(item_code, batch_no, manufacturing_date=None, expiry_date=None, supplier=None, receipt=None, manufacturer=None):
    """Creates the Batch of a batch number the supplier printed on the material, for an item that
    needs its batches created by hand (`has_batch_no` without `create_new_batch`).

    The Batch ID follows *WMS Settings > Batch ID Pattern* (`{counter}-{item}-{batch_no}` on
    sites with a batch number field, else the batch number itself); `{counter}` continues the
    numeric prefix of the last batch created, assigned under a lock and retried when another user
    took it meanwhile. The expiry date defaults to the manufacturing date plus the item's shelf
    life (ERPNext does that itself); items with an expiry date and no shelf life need it sent.
    When a Batch with that number already exists for the item it is returned as is
    (`created: false`) instead of creating a duplicate.
    """
    item_code = (item_code or "").strip()
    batch_no = (batch_no or "").strip()
    if not item_code or not batch_no:
        frappe.throw(_("Item and batch number are required."))
    facts = batch_item_facts(item_code)
    if not facts:
        frappe.throw(_("Item {0} does not exist.").format(item_code), frappe.DoesNotExistError)
    if not facts["has_batch_no"]:
        frappe.throw(_("Item {0} is not batch tracked.").format(item_code))
    frappe.has_permission(BATCH, "create", throw=True)
    existing = search_batches(batch_no, [item_code])
    if existing:
        return {"created": False, "batch": serialize_batch(existing[0]), "item": facts}

    pattern = batch_id_pattern()
    field = batch_no_field()
    values = {
        "doctype": BATCH,
        "item": item_code,
        "manufacturing_date": manufacturing_date or None,
        "expiry_date": expiry_date or None,
        "supplier": supplier or None,
    }
    if field:
        values[field] = batch_no
    if manufacturer and frappe.get_meta(BATCH).has_field("custom_manufacturer"):
        values["custom_manufacturer"] = manufacturer
    if receipt:
        values["reference_doctype"] = PURCHASE_RECEIPT
        values["reference_name"] = receipt
        if not supplier:
            values["supplier"] = frappe.db.get_value(PURCHASE_RECEIPT, receipt, "supplier")

    attempts = 5 if rules.needs_counter(pattern) else 1
    for attempt in range(attempts):
        counter = next_batch_counter() if rules.needs_counter(pattern) else None
        try:
            batch_id = rules.render_batch_id(pattern, item_code, batch_no, counter)
        except rules.ReceivingRuleError as exc:
            frappe.throw(_(str(exc)))
        if not batch_id:
            frappe.throw(_("WMS Settings > Batch ID Pattern produced an empty Batch ID."))
        if frappe.db.exists(BATCH, batch_id):
            if attempt < attempts - 1:
                continue
            frappe.throw(_("Batch {0} already exists.").format(batch_id), frappe.DuplicateEntryError)
        doc = frappe.get_doc({**values, "batch_id": batch_id})
        try:
            doc.insert()
        except frappe.DuplicateEntryError:
            if attempt < attempts - 1:
                frappe.db.rollback()
                continue
            raise
        break
    if receipt:
        doc.add_comment("Info", _("Created through the WMS app while receiving {0}").format(receipt))
    return {"created": True, "batch": serialize_batch(doc), "item": facts}


def next_batch_counter() -> int:
    """The next `{counter}`: numeric prefix of the last Batch created plus one, past any id that
    is already taken. The row lock serializes two receivers creating batches at the same moment."""
    last = frappe.db.sql(
        "select batch_id from `tabBatch` where ifnull(batch_id, '') != '' order by creation desc limit 1 for update",
        as_dict=True,
    )
    counter = rules.next_counter(last[0].batch_id if last else None)
    while frappe.db.exists(BATCH, {"batch_id": ["like", f"{counter}-%"]}):
        counter += 1
    return counter


def batch_item_facts(item_code):
    item = frappe.db.get_value("Item", item_code, BATCH_ITEM_FIELDS, as_dict=True)
    if not item:
        return None
    return {
        "item_code": item.name,
        "item_name": item.item_name,
        "stock_uom": item.stock_uom,
        "has_batch_no": bool(cint(item.has_batch_no)),
        "has_serial_no": bool(cint(item.has_serial_no)),
        "has_expiry_date": bool(cint(item.has_expiry_date)),
        "shelf_life_in_days": cint(item.shelf_life_in_days),
        "create_new_batch": bool(cint(item.create_new_batch)),
    }


def serialize_batch(batch) -> dict:
    field = batch_no_field()
    label = (batch.get(field) if field else None) or batch.get("batch_id") or batch.get("name")
    return {
        "name": batch.get("name"),
        "batch_no": label,
        "item": batch.get("item"),
        "item_name": batch.get("item_name"),
        "expiry_date": str_or_none(batch.get("expiry_date")),
        "manufacturing_date": str_or_none(batch.get("manufacturing_date")),
        "supplier": batch.get("supplier"),
        "disabled": bool(cint(batch.get("disabled"))),
    }


def wms_settings():
    try:
        return frappe.get_cached_doc("WMS Settings", "WMS Settings")
    except Exception:
        return frappe._dict()


def batch_no_field():
    """The Batch field that holds the supplier's batch number: *WMS Settings > Batch No Field*,
    else `custom_batch_no` when the site has it, else None (the Batch ID is the number)."""
    configured = (wms_settings().get("batch_no_field") or "").strip()
    meta = frappe.get_meta(BATCH)
    if configured:
        if not meta.has_field(configured):
            frappe.throw(_("WMS Settings > Batch No Field: Batch has no field {0}.").format(configured))
        return configured
    return "custom_batch_no" if meta.has_field("custom_batch_no") else None


def batch_id_pattern() -> str:
    configured = (wms_settings().get("batch_id_pattern") or "").strip()
    return configured or rules.default_batch_id_pattern(bool(batch_no_field()))


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
        for item in frappe.get_all("Item", filters={"name": ["in", list(items)]}, fields=BATCH_ITEM_FIELDS):
            tracking[item.name] = item
    batches = bundle_batches({row.get(BUNDLE) for row in rows if row.get(BUNDLE) and not row.get("batch_no")})
    has_counted_batches = row_meta_has(COUNTED_BATCHES)
    facts = []
    for row in rows:
        info = tracking.get(row.item_code) or frappe._dict()
        has_batch_no = bool(cint(info.get("has_batch_no")))
        bundle = row.get(BUNDLE)
        batch_no = row.get("batch_no") or (batches.get(bundle) if bundle else None)
        counted_batches = rules.parse_counted_batches(row.get(COUNTED_BATCHES)) if has_counted_batches else []
        facts.append(
            {
                "name": row.name,
                "item_code": row.item_code,
                "item_name": info.get("item_name"),
                "qty": flt(row.get("qty")),
                "counted_qty": flt(row.get(COUNTED_QTY)),
                "counted_batches": counted_batches,
                "warehouse": row.get("warehouse"),
                "batch_no": batch_no,
                "bundle": bundle,
                "has_batch_no": has_batch_no,
                "has_serial_no": bool(cint(info.get("has_serial_no"))),
                "has_expiry_date": bool(cint(info.get("has_expiry_date"))),
                "shelf_life_in_days": cint(info.get("shelf_life_in_days")),
                "create_new_batch": bool(cint(info.get("create_new_batch"))),
                # An existing batch must be chosen unless the row already carries one (batch field
                # or bundle) or ERPNext creates one on submit.
                "needs_batch": has_batch_no and not batch_no and not bundle and not cint(info.get("create_new_batch")),
            }
        )
    names = {f["batch_no"] for f in facts if f["batch_no"]} | {b["batch_no"] for f in facts for b in f["counted_batches"]}
    labels = batch_labels(names)
    for fact in facts:
        fact["batch_label"] = labels.get(fact["batch_no"]) if fact["batch_no"] else None
        for batch in fact["counted_batches"]:
            batch["batch_label"] = labels.get(batch["batch_no"])
    return facts


def batch_labels(names) -> dict:
    """{batch name: the supplier's batch number} for sites with a batch number field, else the name."""
    names = [n for n in names if n]
    if not names:
        return {}
    field = batch_no_field()
    if not field:
        return {name: name for name in names}
    out = {}
    for row in frappe.get_all(BATCH, filters={"name": ["in", names]}, fields=["name", field]):
        out[row.name] = row.get(field) or row.name
    return out


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
        "batch_label": fact.get("batch_label") or fact.get("batch_no") or row.get("batch_no"),
        "counted_batches": fact.get("counted_batches", []),
        "has_batch_no": fact.get("has_batch_no", False),
        "has_serial_no": fact.get("has_serial_no", False),
        "has_expiry_date": fact.get("has_expiry_date", False),
        "shelf_life_in_days": fact.get("shelf_life_in_days", 0),
        "create_new_batch": fact.get("create_new_batch", False),
        "needs_batch": fact.get("needs_batch", False),
        "purchase_order": row.get("purchase_order"),
        "purchase_order_item": row.get("purchase_order_item"),
        "barcodes": barcodes.get(row.item_code, []),
    }
