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
from frappe.utils import cint, flt
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
    summary = label_summary(labels, options)
    summary.update(
        {
            "rows": [{k: label[k] for k in ("item_code", "item_name", "batch_no", "expiry_date", "qty", "uom")} for label in unique[:200]],
            "html": render_roll(labels[: 6 * options["copies"]], fmt, options),
            "print_format": fmt.name,
        }
    )
    return summary


@frappe.whitelist()
def count_warehouse_labels(warehouse, mode=None, item_code=None, batch_no=None, per_batch=1, copies=1, label_width=None, label_height=None, print_format=None):
    """Validates a print request without rendering anything (the page calls it before it opens the
    PDF, so errors show as messages instead of a raw error page) and returns its totals."""
    labels, options = warehouse_labels(warehouse, mode, item_code, batch_no, per_batch, copies, label_width, label_height, for_print=True)
    roll_format(print_format)
    return label_summary(labels, options)


@frappe.whitelist()
def download_warehouse_labels(warehouse, mode=None, item_code=None, batch_no=None, per_batch=1, copies=1, label_width=None, label_height=None, print_format=None):
    """The labels as one PDF, one label per page sized for the label printer."""
    labels, options = warehouse_labels(warehouse, mode, item_code, batch_no, per_batch, copies, label_width, label_height, for_print=True)
    fmt = roll_format(print_format)
    html = render_roll(labels, fmt, options)
    frappe.local.response.filename = rules.file_name("Warehouse", len(labels))
    frappe.local.response.filecontent = get_pdf(html, options=rules.roll_pdf_options(options["label_width"], options["label_height"]))
    frappe.local.response.type = "pdf"


@frappe.whitelist()
def stocked_item_query(doctype, txt, searchfield, start, page_len, filters):
    """Link field query of the page: items with stock in the chosen warehouse."""
    filters = frappe.parse_json(filters) or {}
    warehouses = accessible_stock_warehouses(filters.get("warehouse"))
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
        {"warehouses": warehouses, "like": like, "start": cint(start), "page_len": cint(page_len) or 20},
    )


@frappe.whitelist()
def stocked_batch_query(doctype, txt, searchfield, start, page_len, filters):
    """Link field query of the page: batches with stock in the chosen warehouse (of the chosen
    item), filtered and paged in the database."""
    filters = frappe.parse_json(filters) or {}
    warehouses = accessible_stock_warehouses(filters.get("warehouse"))
    rows = batch_stock(warehouses, item_code=filters.get("item_code") or None, txt=txt, start=cint(start), limit=cint(page_len) or 20)
    return [(row.batch_no, row.item_code, row.qty) for row in rows]


def label_summary(labels, options) -> dict:
    unique = labels[:: options["copies"]] if options["copies"] > 1 else labels
    return {
        "count": len(labels),
        "labels": len(unique),
        "items": len({label["item_code"] for label in unique}),
        "batches": len({label["batch_no"] for label in unique if label["batch_no"]}),
        "label_width": options["label_width"],
        "label_height": options["label_height"],
    }


def warehouse_labels(warehouse, mode, item_code, batch_no, per_batch, copies, label_width, label_height, for_print=False):
    """The labels to print (each with its QR payload, copies expanded) and the layout options."""
    try:
        mode = rules.label_mode(mode)
        width, height = rules.label_size(label_width, label_height)
        count = rules.copies(copies)
    except rules.LabelRuleError as exc:
        frappe.throw(_(str(exc)))
    warehouses = accessible_stock_warehouses(warehouse, for_print=for_print)
    item_code = (item_code or "").strip() or None
    batch_no = (batch_no or "").strip() or None

    if mode == rules.MODE_BATCH:
        if not batch_no:
            frappe.throw(_("Choose the batch to print."))
        batch_item = frappe.db.get_value("Batch", batch_no, "item")
        if not batch_item:
            frappe.throw(_("Batch {0} does not exist.").format(batch_no))
        if item_code and item_code != batch_item:
            frappe.throw(_("Batch {0} belongs to item {1}, not {2}.").format(batch_no, batch_item, item_code))
        item_code = batch_item
    elif mode == rules.MODE_ITEM and not item_code:
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

    if mode == rules.MODE_ITEM:
        # One item label (the QR names the item only), whatever batches the stock is held in.
        labels = rules.stocked_labels(bins, {}, items, {}, per_batch=False)
        if not labels:
            frappe.throw(_("{0} has no stock in {1}.").format(item_code, warehouse))
    else:
        with_batches = mode == rules.MODE_BATCH or bool(cint(per_batch))
        balances = {}
        if with_batches and any(info["has_batch_no"] for info in items.values()):
            if mode == rules.MODE_BATCH:
                rows = batch_stock(warehouses, item_code=item_code, batch_no=batch_no, by_warehouse=True)
            else:
                rows = batch_stock(warehouses, by_warehouse=True)
            balances = {(row.item_code, row.warehouse, row.batch_no): flt(row.qty) for row in rows}
        batches = batch_expiries({key[2] for key in balances})
        labels = rules.stocked_labels(bins, balances, items, batches, per_batch=with_batches)
        if mode == rules.MODE_BATCH:
            labels = [label for label in labels if label["batch_no"] == batch_no]
            if not labels:
                frappe.throw(_("Batch {0} has no stock in {1}.").format(batch_no, warehouse))
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


def accessible_stock_warehouses(warehouse, for_print=False) -> list:
    """The warehouses whose stock may be labelled: the warehouse itself or the enabled leaf
    warehouses of a group, limited to what the user may read (roles and User Permissions on
    Warehouse). Printing also needs print permission on Item, as the label sheets do."""
    warehouse = (warehouse or "").strip()
    if not warehouse:
        frappe.throw(_("Choose the warehouse."))
    info = frappe.db.get_value("Warehouse", warehouse, ["name", "lft", "rgt", "is_group", "disabled"], as_dict=True)
    if not info:
        frappe.throw(_("Warehouse {0} does not exist.").format(warehouse), frappe.DoesNotExistError)
    if not frappe.has_permission("Warehouse", "read", doc=warehouse):
        frappe.throw(_("Not permitted to read Warehouse {0}.").format(warehouse), frappe.PermissionError)
    if not frappe.has_permission("Item", "read") or not frappe.has_permission("Bin", "read"):
        frappe.throw(_("Not permitted to read stock."), frappe.PermissionError)
    if for_print and not frappe.has_permission("Item", "print"):
        frappe.throw(_("Not permitted to print {0}.").format(_("Item")), frappe.PermissionError)
    if cint(info.disabled):
        frappe.throw(_("Warehouse {0} is disabled.").format(warehouse))
    if not cint(info.is_group):
        return [warehouse]
    # get_list applies the user's permissions, so a group only covers the warehouses they may see.
    leaves = frappe.get_list(
        "Warehouse",
        filters={"lft": [">", info.lft], "rgt": ["<", info.rgt], "is_group": 0, "disabled": 0},
        pluck="name",
        order_by="lft asc",
        limit_page_length=0,
    )
    if not leaves:
        frappe.throw(_("Warehouse {0} has no enabled warehouses you may print from.").format(warehouse))
    return leaves


def batch_stock(warehouses, item_code=None, batch_no=None, txt=None, start=0, limit=None, by_warehouse=False) -> list:
    """Batch balances held in `warehouses`, computed and filtered in the database: per (item,
    warehouse, batch) with `by_warehouse`, else per (item, batch) for the Link search (`txt` matches
    the batch or the item, paged with `start` / `limit`). Ledger entries made before Serial and Batch
    Bundles carry `batch_no` themselves; both kinds are added up, none counted twice."""
    params = {"warehouses": warehouses, "item_code": item_code, "batch_no": batch_no, "like": f"%{txt or ''}%"}
    conditions = "sle.warehouse in %(warehouses)s and sle.is_cancelled = 0"
    if item_code:
        conditions += " and sle.item_code = %(item_code)s"
    legacy_batch, bundle_batch = "sle.batch_no", "sbe.batch_no"
    legacy_where, bundle_where = conditions, conditions
    if batch_no:
        legacy_where += f" and {legacy_batch} = %(batch_no)s"
        bundle_where += f" and {bundle_batch} = %(batch_no)s"
    if txt:
        legacy_where += f" and ({legacy_batch} like %(like)s or sle.item_code like %(like)s)"
        bundle_where += f" and ({bundle_batch} like %(like)s or sle.item_code like %(like)s)"
    group = "item_code, warehouse, batch_no" if by_warehouse else "item_code, batch_no"
    paging = ""
    if limit:
        paging = " limit %(start)s, %(limit)s"
        params.update({"start": cint(start), "limit": cint(limit)})
    return frappe.db.sql(
        f"""
        select {group}, sum(qty) as qty from (
            select sle.item_code, sle.warehouse, sle.batch_no, sle.actual_qty as qty
            from `tabStock Ledger Entry` sle
            where {legacy_where} and ifnull(sle.batch_no, '') != '' and ifnull(sle.serial_and_batch_bundle, '') = ''
            union all
            select sle.item_code, sle.warehouse, sbe.batch_no, sbe.qty
            from `tabStock Ledger Entry` sle
            inner join `tabSerial and Batch Entry` sbe on sbe.parent = sle.serial_and_batch_bundle
            where {bundle_where} and ifnull(sle.serial_and_batch_bundle, '') != '' and ifnull(sbe.batch_no, '') != ''
        ) moves
        group by {group}
        having sum(qty) > 0
        order by batch_no, item_code{paging}
        """,
        params,
        as_dict=True,
    )


def batch_expiries(batch_nos) -> dict:
    batch_nos = [b for b in batch_nos or [] if b]
    if not batch_nos:
        return {}
    return {b.name: {"expiry_date": b.expiry_date} for b in frappe.get_all("Batch", filters={"name": ["in", batch_nos]}, fields=["name", "expiry_date"], limit_page_length=0)}


def roll_format(name=None):
    """The requested (or the shipped) label-printer Print Format, created if a site lost it. Only an
    enabled Warehouse format marked as a label-printer layout is accepted."""
    name = (name or "").strip() or rules.ROLL_FORMAT
    if not frappe.db.exists(PRINT_FORMAT, name):
        if name != rules.ROLL_FORMAT:
            frappe.throw(_("Print Format {0} does not exist.").format(name), frappe.DoesNotExistError)
        from wmserp_picking.setup.custom_fields import ensure_label_print_formats

        ensure_label_print_formats()
    fmt = frappe.get_doc(PRINT_FORMAT, name)
    if fmt.doc_type != rules.FORMAT_DOCTYPES[rules.ROLL_FORMAT]:
        frappe.throw(_("Print Format {0} belongs to {1}, not {2}.").format(name, _(fmt.doc_type), _("Warehouse")))
    if not rules.is_roll_template(fmt.html):
        frappe.throw(_("Print Format {0} is not a label-printer format (it must contain the '{1}' marker).").format(name, rules.ROLL_MARKER))
    if fmt.disabled:
        if name != rules.ROLL_FORMAT:
            frappe.throw(_("Print Format {0} is disabled.").format(name))
        # The shipped format is the page's fallback: a disabled one is brought back.
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
