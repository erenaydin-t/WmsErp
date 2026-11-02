"""Pure helpers of the QR label sheets (no Frappe imports, unit-tested without a bench).

A label *sheet* print format renders many documents at once: `docs` is the list of selected
Batch / Item documents and the template lays them out in a grid, several per page. The layout
(labels per page, sizes, margins) lives in the Print Format itself, so it can be changed in
ERPNext without touching the app; this module only knows how to recognise such a format, how to
parse the selection and how to wrap the rendered body into a printable page.
"""

from __future__ import annotations

import json

# Present in every sheet template; a Print Format without it renders one document per page and
# must not be offered as a sheet (its `doc` variable would be undefined).
SHEET_MARKER = "wms-label-sheet"

SHEET_FORMATS = {
    "Batch": "WMS Batch QR Label Sheet",
    "Item": "WMS Item QR Label Sheet",
}

SINGLE_FORMATS = {
    "Batch": "WMS Batch QR Label",
    "Item": "WMS Item QR Label",
}

# Print Format name -> template file (templates/print_formats/<file>), for both kinds of label.
TEMPLATE_FILES = {
    "WMS Batch QR Label": "wms_batch_qr_label.html",
    "WMS Item QR Label": "wms_item_qr_label.html",
    "WMS Batch QR Label Sheet": "wms_batch_qr_label_sheet.html",
    "WMS Item QR Label Sheet": "wms_item_qr_label_sheet.html",
    "WMS Warehouse QR Labels": "wms_warehouse_qr_labels.html",
}

# DocType each shipped QR label format belongs to.
FORMAT_DOCTYPES = {
    "WMS Batch QR Label": "Batch",
    "WMS Item QR Label": "Item",
    "WMS Batch QR Label Sheet": "Batch",
    "WMS Item QR Label Sheet": "Item",
    "WMS Warehouse QR Labels": "Warehouse",
}

# ---- warehouse labels for label printers -------------------------------------------------------
# The *WMS QR Labels* page prints the items / batches in stock in a warehouse on a label printer:
# one label per PDF page, the page exactly the size of the label. The layout is the editable Print
# Format below (marked with ROLL_MARKER); the label size comes from the page.
ROLL_FORMAT = "WMS Warehouse QR Labels"
ROLL_MARKER = "wms-label-roll"
MODE_WAREHOUSE = "warehouse"  # everything in stock in the warehouse
MODE_ITEM = "item"  # one item
MODE_BATCH = "batch"  # one batch
LABEL_MODES = (MODE_WAREHOUSE, MODE_ITEM, MODE_BATCH)
DEFAULT_LABEL_SIZE = (50.0, 30.0)  # mm, a common thermal label
MIN_LABEL_MM, MAX_LABEL_MM = 15.0, 200.0
MAX_COPIES = 50
MAX_ROLL_LABELS = 3000
QTY_TOLERANCE = 1e-6

# Site document labels the app ships and owns: Print Format name -> (DocType, template file).
# Unlike the QR labels above, their HTML is re-applied on every migrate, so the shipped template
# is the only place to change them (no separate customization to maintain on the site).
MANAGED_TEMPLATES = {
    "Material Status Label QC-LAB-02": ("Purchase Receipt", "material_status_label_qc_lab_02.html"),
}

SUPPORTED_DOCTYPES = tuple(SHEET_FORMATS)
PAGE_SIZES = ("A4", "A5", "A3", "Letter", "Legal")
MAX_LABELS = 500


class LabelRuleError(ValueError):
    """A validation failure that the API layer turns into `frappe.throw`."""


def is_sheet_template(html) -> bool:
    return bool(html) and SHEET_MARKER in html


def default_sheet_format(doctype) -> str:
    try:
        return SHEET_FORMATS[doctype]
    except KeyError as exc:
        raise LabelRuleError(f"QR label sheets are not available for {doctype}") from exc


def parse_names(names) -> list[str]:
    """`names` arrives as a JSON list, a comma separated string or a list; duplicates and blanks
    are dropped while the order of the selection is kept."""
    if names in (None, ""):
        raise LabelRuleError("Select at least one document to print.")
    if isinstance(names, (bytes, bytearray)):
        names = names.decode("utf-8", errors="replace")
    if isinstance(names, str):
        text = names.strip()
        if text.startswith("["):
            try:
                names = json.loads(text)
            except ValueError as exc:
                raise LabelRuleError("The selection is not a valid JSON list.") from exc
        else:
            names = [part for part in text.split(",")]
    if not isinstance(names, (list, tuple)):
        raise LabelRuleError("The selection must be a list of document names.")
    cleaned, seen = [], set()
    for name in names:
        value = str(name).strip() if name is not None else ""
        if not value or value in seen:
            continue
        seen.add(value)
        cleaned.append(value)
    if not cleaned:
        raise LabelRuleError("Select at least one document to print.")
    if len(cleaned) > MAX_LABELS:
        raise LabelRuleError(f"At most {MAX_LABELS} labels can be printed at once ({len(cleaned)} selected).")
    return cleaned


def page_size(value) -> str:
    """A wkhtmltopdf page size; anything unknown falls back to A4."""
    text = (value or "").strip()
    for size in PAGE_SIZES:
        if text.lower() == size.lower():
            return size
    return "A4"


def wrap_page(body, css="", title="Labels") -> str:
    """The complete HTML document handed to the PDF renderer. The template's own `<style>` (with
    the `.print-format` margins Frappe reads) stays inside `body`; the Print Format's CSS field is
    added in the head."""
    return (
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>"
        + _escape(title)
        + "</title><style>"
        + (css or "")
        + "</style></head><body><div class=\"print-format\">"
        + (body or "")
        + "</div></body></html>"
    )


def is_roll_template(html) -> bool:
    return bool(html) and ROLL_MARKER in html


def label_mode(value) -> str:
    mode = (value or MODE_WAREHOUSE).strip().lower()
    if mode not in LABEL_MODES:
        raise LabelRuleError(f"Unknown label selection '{value}'.")
    return mode


def label_size(width=None, height=None) -> tuple[float, float]:
    """(width, height) in mm; empty values take the default 50 x 30 mm label."""
    out = []
    for value, default, name in ((width, DEFAULT_LABEL_SIZE[0], "width"), (height, DEFAULT_LABEL_SIZE[1], "height")):
        if value in (None, ""):
            out.append(default)
            continue
        try:
            number = float(value)
        except (TypeError, ValueError) as exc:
            raise LabelRuleError(f"The label {name} must be a number of millimetres.") from exc
        if not MIN_LABEL_MM <= number <= MAX_LABEL_MM:
            raise LabelRuleError(f"The label {name} must be between {MIN_LABEL_MM:g} and {MAX_LABEL_MM:g} mm.")
        out.append(number)
    return out[0], out[1]


def qr_size(width, height) -> float:
    """The printed QR size (mm) for a label: as tall as the label allows (2 mm margins), and at most
    half its width so the text beside it stays readable; never below 10 mm."""
    return round(max(10.0, min(height - 4.0, width * 0.5)), 1)


def copies(value) -> int:
    if value in (None, ""):
        return 1
    try:
        number = int(float(value))
    except (TypeError, ValueError) as exc:
        raise LabelRuleError("Copies must be a whole number.") from exc
    if not 1 <= number <= MAX_COPIES:
        raise LabelRuleError(f"Copies must be between 1 and {MAX_COPIES}.")
    return number


def stocked_labels(bins, batch_qty=None, items=None, batches=None, per_batch=True) -> list[dict]:
    """The labels of what a warehouse holds, one per item or, for batch tracked items, one per
    batch with stock (`per_batch`). Stock of a batch tracked item that no batch carries (entries
    from before batches) gets an item label. The same item or batch held in several warehouses of a
    group gets one label (quantities added up).

    `bins`: `[{item_code, warehouse, actual_qty}]`; `batch_qty`: `{(item_code, warehouse, batch_no): qty}`;
    `items`: `{item_code: {item_name, has_batch_no, stock_uom}}`; `batches`: `{batch_no: {expiry_date}}`.
    Labels are ordered by item code, then by expiry (earliest first) and batch.
    """
    batch_qty, items, batches = batch_qty or {}, items or {}, batches or {}
    by_item_wh = {}
    for (item_code, warehouse, batch_no), qty in batch_qty.items():
        held = by_item_wh.setdefault((item_code, warehouse), {})
        held[batch_no] = held.get(batch_no, 0.0) + float(qty or 0)
    merged = {}

    def add(item_code, batch_no, qty, warehouse):
        info = items.get(item_code) or {}
        key = (item_code, batch_no or "")
        label = merged.get(key)
        if label is None:
            label = merged[key] = {
                "item_code": item_code,
                "item_name": info.get("item_name") or item_code,
                "batch_no": batch_no,
                "expiry_date": (batches.get(batch_no) or {}).get("expiry_date") if batch_no else None,
                "qty": 0.0,
                "uom": info.get("stock_uom"),
                "warehouse": warehouse,
            }
        label["qty"] += qty

    for b in bins or []:
        item_code, warehouse, qty = b.get("item_code"), b.get("warehouse"), float(b.get("actual_qty") or 0)
        if not item_code or qty <= QTY_TOLERANCE:
            continue
        info = items.get(item_code) or {}
        if per_batch and info.get("has_batch_no"):
            held = 0.0
            for batch_no, batch_stock in (by_item_wh.get((item_code, warehouse)) or {}).items():
                if batch_stock > QTY_TOLERANCE:
                    add(item_code, batch_no, batch_stock, warehouse)
                    held += batch_stock
            if qty - held > QTY_TOLERANCE:
                add(item_code, None, qty - held, warehouse)
        else:
            add(item_code, None, qty, warehouse)

    def order(label):
        expiry = label["expiry_date"]
        return (label["item_code"], 0 if label["batch_no"] else 1, str(expiry) if expiry else "9999-12-31", label["batch_no"] or "")

    return sorted(merged.values(), key=order)


def with_copies(labels, count=1) -> list[dict]:
    """Each label `count` times in a row (as a label printer prints them), within the limit."""
    total = len(labels) * count
    if total > MAX_ROLL_LABELS:
        raise LabelRuleError(f"At most {MAX_ROLL_LABELS} labels can be printed at once ({total} requested); print fewer copies or one item group at a time.")
    return [label for label in labels for _copy in range(count)]


def file_name(doctype, count) -> str:
    return f"{doctype.lower().replace(' ', '_')}_qr_labels_{count}.pdf"


def _escape(text) -> str:
    return (
        str(text)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )
