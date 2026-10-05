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


def chunk(items, size) -> list[list]:
    """Splits `items` into pages of `size` (the last page may be shorter): 20 labels at 12 per
    page give [12, 8]."""
    size = int(size)
    if size <= 0:
        raise LabelRuleError("labels per page must be positive")
    return [list(items[i : i + size]) for i in range(0, len(items), size)]


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
