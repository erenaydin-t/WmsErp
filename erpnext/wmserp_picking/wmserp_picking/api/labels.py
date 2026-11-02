"""QR label sheets: many Batch / Item labels on one A4 page.

Frappe's own *Actions > Print* renders every selected document on its own page (it merges one PDF
per document), so twenty batches cost twenty sheets of paper. These endpoints render a *sheet*
print format once with all selected documents, and the Print Format decides how many labels fit on
a page and how big they are:

    GET /api/method/wmserp_picking.api.labels.download_label_sheet
        ?doctype=Batch&names=["B-001","B-002"]&print_format=WMS Batch QR Label Sheet&page_size=A4

The Batch and Item list views get an *Actions > Print QR Label Sheet* entry (public/js) that opens
this URL for the selected rows. Templates: templates/print_formats/*_sheet.html, created as editable
Print Formats on install / migrate (see setup/custom_fields.py).
"""

import frappe
from frappe import _
from frappe.utils import cint
from frappe.utils.pdf import get_pdf

from wmserp_picking.labels import rules
from wmserp_picking.qr import wms_qr_keys

PRINT_FORMAT = "Print Format"


@frappe.whitelist()
def get_label_sheet_formats(doctype):
    """Sheet print formats available for `doctype` (the shipped one plus any copy the site made),
    for the selector of the list action."""
    ensure_supported(doctype)
    default = rules.default_sheet_format(doctype)
    formats = frappe.get_all(
        PRINT_FORMAT,
        filters={"doc_type": doctype, "disabled": 0},
        fields=["name", "html", "modified"],
        order_by="name asc",
    )
    out = [{"name": f.name, "default": f.name == default} for f in formats if rules.is_sheet_template(f.html)]
    if not any(f["default"] for f in out) and frappe.db.exists(PRINT_FORMAT, default):
        out.insert(0, {"name": default, "default": True})
    return out


@frappe.whitelist()
def label_sheet_html(doctype, names, print_format=None):
    """The rendered sheet as HTML (what the PDF is made of), useful to preview a layout change."""
    docs, fmt = load(doctype, names, print_format)
    return render(doctype, docs, fmt)


@frappe.whitelist()
def download_label_sheet(doctype, names, print_format=None, page_size=None):
    """One PDF with every selected document as a label, several labels per page."""
    docs, fmt = load(doctype, names, print_format)
    html = render(doctype, docs, fmt)
    # Margins (and an explicit page size) in the template's `.print-format` rule win over these.
    options = {"page-size": rules.page_size(page_size)}
    frappe.local.response.filename = rules.file_name(doctype, len(docs))
    frappe.local.response.filecontent = get_pdf(html, options=options)
    frappe.local.response.type = "pdf"


# --------------------------------------------------------------------------------------------
# Internals
# --------------------------------------------------------------------------------------------


def ensure_supported(doctype):
    if doctype not in rules.SUPPORTED_DOCTYPES:
        frappe.throw(_("QR label sheets are not available for {0}.").format(_(doctype or "")))


def load(doctype, names, print_format):
    ensure_supported(doctype)
    try:
        names = rules.parse_names(names)
    except rules.LabelRuleError as exc:
        frappe.throw(_(str(exc)))
    fmt = sheet_format(doctype, print_format)
    return load_documents(doctype, names), fmt


def load_documents(doctype, names) -> list:
    """The selected documents as rows, in the order of the selection, in one query. The templates
    read header fields only, so the child tables a `get_doc` would load are not needed.
    `frappe.get_list` applies the caller's read permissions (roles and user permissions), and the
    print permission is checked once for the DocType, as Frappe's print view does per document."""
    if not frappe.has_permission(doctype, "print"):
        frappe.throw(_("Not permitted to print {0}.").format(_(doctype)), frappe.PermissionError)
    rows = frappe.get_list(doctype, filters={"name": ["in", names]}, fields=["*"], limit_page_length=len(names))
    by_name = {row.name: row for row in rows}
    missing = [name for name in names if name not in by_name]
    if missing:
        frappe.throw(_("{0} {1} not found or not permitted.").format(_(doctype), ", ".join(missing)), frappe.DoesNotExistError)
    return [by_name[name] for name in names]


def sheet_format(doctype, name=None):
    """The requested (or the shipped) sheet Print Format of `doctype`, created if a site lost it."""
    try:
        default = rules.default_sheet_format(doctype)
    except rules.LabelRuleError as exc:
        frappe.throw(_(str(exc)))
    name = (name or "").strip() or default
    if not frappe.db.exists(PRINT_FORMAT, name):
        if name != default:
            frappe.throw(_("Print Format {0} does not exist.").format(name), frappe.DoesNotExistError)
        from wmserp_picking.setup.custom_fields import ensure_label_print_formats

        ensure_label_print_formats()
    fmt = frappe.get_doc(PRINT_FORMAT, name)
    if fmt.doc_type != doctype:
        frappe.throw(_("Print Format {0} belongs to {1}, not {2}.").format(name, _(fmt.doc_type), _(doctype)))
    if fmt.disabled:
        if name != default:
            frappe.throw(_("Print Format {0} is disabled.").format(name))
        # The shipped sheet is the fallback of the list action: a disabled one is brought back
        # (its HTML is left as the site edited it; `reset_label_print_formats` restores the template).
        frappe.db.set_value(PRINT_FORMAT, name, "disabled", 0, update_modified=False)
        fmt.disabled = 0
    if not rules.is_sheet_template(fmt.html):
        frappe.throw(
            _("Print Format {0} prints one document per page. Choose a label sheet format (it contains the '{1}' marker).").format(
                name, rules.SHEET_MARKER
            )
        )
    return fmt


def render(doctype, docs, fmt) -> str:
    context = {
        "docs": docs,
        "doctype": doctype,
        "count": len(docs),
        "print_format": fmt.as_dict(),
        "qr_keys": wms_qr_keys(),
    }
    body = frappe.render_template(fmt.html, context)
    return rules.wrap_page(body, css=fmt.css or "", title=f"{_(doctype)} {_('QR labels')}")




# --------------------------------------------------------------------------------------------
# Warehouse labels for label printers (the *WMS QR Labels* page)
# --------------------------------------------------------------------------------------------
#
#   GET /api/method/wmserp_picking.api.labels.download_warehouse_labels
#       ?warehouse=Stores - E&mode=warehouse|item|batch&item_code=&batch_no=
#        &per_batch=1&copies=1&label_width=50&label_height=30&print_format=
#
# One PDF page per label, the page exactly the label size, so a thermal / roll label printer feeds
# one label per page. "warehouse" prints every item (and, for batch tracked items, every batch) in
# stock in the warehouse (all its warehouses for a group), "item" one item, "batch" one batch.


@frappe.whitelist()
def warehouse_label_preview(warehouse, mode=None, item_code=None, batch_no=None, per_batch=1, copies=1, label_width=None, label_height=None, print_format=None):
    """What would be printed: the number of labels, items and batches, the first rows, and the first
    few labels rendered as HTML (for the page's preview)."""
    labels, options = warehouse_labels(warehouse, mode, item_code, batch_no, per_batch, copies, label_width, label_height)
    fmt = roll_format(print_format)
    unique = labels[:: options["copies"]] if options["copies"] > 1 else labels
    return {
        "count": len(labels),
        "labels": len(unique),
        "items": len({label["item_code"] for label in unique}),
        "batches": len({label["batch_no"] for label in unique if label["batch_no"]}),
        "rows": [{k: label[k] for k in ("item_code", "item_name", "batch_no", "expiry_date", "qty", "uom")} for label in unique[:200]],
        "html": render_roll(labels[: 6 * options["copies"]], fmt, options),
        "label_width": options["label_width"],
        "label_height": options["label_height"],
        "print_format": fmt.name,
    }


@frappe.whitelist()
def download_warehouse_labels(warehouse, mode=None, item_code=None, batch_no=None, per_batch=1, copies=1, label_width=None, label_height=None, print_format=None):
    """The labels as one PDF, one label per page sized for the label printer."""
    labels, options = warehouse_labels(warehouse, mode, item_code, batch_no, per_batch, copies, label_width, label_height)
    fmt = roll_format(print_format)
    html = render_roll(labels, fmt, options)
    pdf_options = {
        "page-width": f"{options['label_width']}mm",
        "page-height": f"{options['label_height']}mm",
        "margin-top": "0mm",
        "margin-bottom": "0mm",
        "margin-left": "0mm",
        "margin-right": "0mm",
    }
    frappe.local.response.filename = rules.file_name("Warehouse", len(labels))
    frappe.local.response.filecontent = get_pdf(html, options=pdf_options)
    frappe.local.response.type = "pdf"


@frappe.whitelist()
def stocked_item_query(doctype, txt, searchfield, start, page_len, filters):
    """Link field query of the page: items with stock in the chosen warehouse."""
    filters = frappe.parse_json(filters) or {}
    warehouses = stock_warehouses(filters.get("warehouse"))
    if not warehouses:
        return []
    like = f"%{txt or ''}%"
    return frappe.db.sql(
        """
        select bin.item_code, item.item_name, sum(bin.actual_qty)
        from `tabBin` bin inner join `tabItem` item on item.name = bin.item_code
        where bin.warehouse in %(warehouses)s and bin.actual_qty > 0
          and (bin.item_code like %(like)s or item.item_name like %(like)s)
        group by bin.item_code, item.item_name
        order by bin.item_code
        limit %(start)s, %(page_len)s
        """,
        {"warehouses": warehouses, "like": like, "start": int(start or 0), "page_len": int(page_len or 20)},
    )


@frappe.whitelist()
def stocked_batch_query(doctype, txt, searchfield, start, page_len, filters):
    """Link field query of the page: batches with stock in the chosen warehouse (of the chosen item)."""
    filters = frappe.parse_json(filters) or {}
    warehouses = stock_warehouses(filters.get("warehouse"))
    if not warehouses:
        return []
    item_code = filters.get("item_code")
    text = (txt or "").lower()
    totals = {}
    for (item, _warehouse, batch_no), qty in batch_balances(warehouses).items():
        if item_code and item != item_code:
            continue
        totals[(batch_no, item)] = totals.get((batch_no, item), 0.0) + qty
    rows = [(batch_no, item, qty) for (batch_no, item), qty in totals.items() if qty > rules.QTY_TOLERANCE and (not text or text in batch_no.lower() or text in item.lower())]
    rows.sort()
    start, page_len = int(start or 0), int(page_len or 20)
    return rows[start : start + page_len]


def warehouse_labels(warehouse, mode, item_code, batch_no, per_batch, copies, label_width, label_height):
    """The labels to print (each with its QR payload, copies expanded) and the layout options."""
    try:
        mode = rules.label_mode(mode)
        width, height = rules.label_size(label_width, label_height)
        count = rules.copies(copies)
    except rules.LabelRuleError as exc:
        frappe.throw(_(str(exc)))
    check_label_permissions()
    warehouses = stock_warehouses(warehouse)
    if not warehouses:
        frappe.throw(_("Warehouse {0} has no enabled warehouses to print from.").format(warehouse))

    if mode == rules.MODE_BATCH:
        if not batch_no:
            frappe.throw(_("Choose the batch to print."))
        item_code = frappe.db.get_value("Batch", batch_no, "item")
        if not item_code:
            frappe.throw(_("Batch {0} does not exist.").format(batch_no))
    if mode == rules.MODE_ITEM and not item_code:
        frappe.throw(_("Choose the item to print."))

    bin_filters = {"warehouse": ["in", warehouses], "actual_qty": [">", 0]}
    if mode != rules.MODE_WAREHOUSE:
        bin_filters["item_code"] = item_code
    bins = frappe.get_all("Bin", filters=bin_filters, fields=["item_code", "warehouse", "actual_qty"], limit_page_length=0)
    codes = sorted({b.item_code for b in bins})
    items = {}
    if codes:
        for item in frappe.get_all("Item", filters={"name": ["in", codes]}, fields=["name", "item_name", "has_batch_no", "stock_uom"], limit_page_length=0):
            items[item.name] = {"item_name": item.item_name, "has_batch_no": cint(item.has_batch_no), "stock_uom": item.stock_uom}
    with_batches = mode == rules.MODE_BATCH or (mode == rules.MODE_WAREHOUSE and cint(per_batch))
    balances = batch_balances(warehouses) if with_batches and any(info["has_batch_no"] for info in items.values()) else {}
    if mode == rules.MODE_BATCH:
        balances = {key: qty for key, qty in balances.items() if key[2] == batch_no}
    batch_names = {key[2] for key in balances}
    batches = {}
    if batch_names:
        for batch in frappe.get_all("Batch", filters={"name": ["in", list(batch_names)]}, fields=["name", "expiry_date"], limit_page_length=0):
            batches[batch.name] = {"expiry_date": batch.expiry_date}

    labels = rules.stocked_labels(bins, balances, items, batches, per_batch=with_batches)
    if mode == rules.MODE_BATCH:
        labels = [label for label in labels if label["batch_no"] == batch_no]
        if not labels:
            frappe.throw(_("Batch {0} has no stock in {1}.").format(batch_no, warehouse))
    elif mode == rules.MODE_ITEM:
        # One item label (item code only in the QR), whatever batches the item is held in.
        total = sum(label["qty"] for label in labels)
        labels = labels[:1]
        if not labels:
            frappe.throw(_("{0} has no stock in {1}.").format(item_code, warehouse))
        labels[0].update({"batch_no": None, "expiry_date": None, "qty": total})
    elif not labels:
        frappe.throw(_("Nothing is in stock in {0}.").format(warehouse))

    keys = wms_qr_keys()
    for label in labels:
        label["payload"] = qr_payload(keys, label["item_code"], label["batch_no"])
    try:
        labels = rules.with_copies(labels, count)
    except rules.LabelRuleError as exc:
        frappe.throw(_(str(exc)))
    return labels, {"label_width": width, "label_height": height, "copies": count, "warehouse": warehouse, "mode": mode}


def qr_payload(keys, item_code, batch_no=None) -> str:
    """The JSON the WMS app scans (same as `wmserp_picking.qr.wms_qr_payload`, keys read once)."""
    import json

    data = {keys["qr_item_key"]: item_code}
    if batch_no:
        data[keys["qr_batch_key"]] = batch_no
    return json.dumps(data, ensure_ascii=False, separators=(",", ":"))


def check_label_permissions():
    if not frappe.has_permission("Item", "read") or not frappe.has_permission("Bin", "read"):
        frappe.throw(_("Not permitted to print stock labels."), frappe.PermissionError)


def stock_warehouses(warehouse) -> list:
    if not warehouse:
        return []
    from wmserp_picking.stocktaking.service import leaf_warehouses

    return leaf_warehouses(warehouse)


def batch_balances(warehouses) -> dict:
    from wmserp_picking.stocktaking.service import batch_balances as balances

    return balances(warehouses)


def roll_format(name=None):
    """The requested (or the shipped) label-printer Print Format, created if a site lost it."""
    name = (name or "").strip() or rules.ROLL_FORMAT
    if not frappe.db.exists(PRINT_FORMAT, name):
        if name != rules.ROLL_FORMAT:
            frappe.throw(_("Print Format {0} does not exist.").format(name), frappe.DoesNotExistError)
        from wmserp_picking.setup.custom_fields import ensure_label_print_formats

        ensure_label_print_formats()
    fmt = frappe.get_doc(PRINT_FORMAT, name)
    if not rules.is_roll_template(fmt.html):
        frappe.throw(_("Print Format {0} is not a label-printer format (it must contain the '{1}' marker).").format(name, rules.ROLL_MARKER))
    if fmt.disabled and name == rules.ROLL_FORMAT:
        frappe.db.set_value(PRINT_FORMAT, name, "disabled", 0, update_modified=False)
    return fmt


@frappe.whitelist()
def get_roll_formats():
    """Label-printer Print Formats (the shipped one plus copies the site made)."""
    formats = frappe.get_all(PRINT_FORMAT, filters={"doc_type": "Warehouse", "disabled": 0}, fields=["name", "html"], order_by="name asc")
    names = [f.name for f in formats if rules.is_roll_template(f.html)]
    if rules.ROLL_FORMAT not in names:
        names.insert(0, rules.ROLL_FORMAT)
    return names


def render_roll(labels, fmt, options) -> str:
    context = {
        "labels": labels,
        "count": len(labels),
        "label_width": options["label_width"],
        "label_height": options["label_height"],
        "qr_size": rules.qr_size(options["label_width"], options["label_height"]),
        "warehouse": options["warehouse"],
        "print_format": fmt.as_dict(),
    }
    body = frappe.render_template(fmt.html, context)
    return rules.wrap_page(body, css=fmt.css or "", title=_("QR labels"))
