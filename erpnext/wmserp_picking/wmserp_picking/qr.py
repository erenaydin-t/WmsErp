"""QR label helpers exposed to Jinja (print formats) through `hooks.jinja`.

The label content is a JSON object using the keys configured in *WMS Settings*, e.g.
`{"item_code": "ITEM-001", "batch_no": "B-2026-01"}` for a batch label or
`{"item_code": "ITEM-001"}` for an item-level label (items that are not batch tracked), which is
exactly what the WMS app expects when a picker or a counter scans a label.

Only `wms_qr_keys` needs a running Frappe site (it reads the *WMS Settings* single); the payload and
SVG helpers are plain Python so they can be unit-tested without a bench (`test_qr.py`).
"""

import io
import json


def wms_qr_keys() -> dict:
    # Imported lazily: the settings DocType module imports frappe, the helpers below do not.
    from wmserp_picking.wms_erp_picking.doctype.wms_settings.wms_settings import get_qr_keys

    return get_qr_keys()


def wms_qr_payload(item_code, batch_no=None) -> str:
    keys = wms_qr_keys()
    data = {keys["qr_item_key"]: item_code}
    if batch_no:
        data[keys["qr_batch_key"]] = batch_no
    return json.dumps(data, ensure_ascii=False, separators=(",", ":"))


def wms_batch_qr_payload(doc) -> str:
    """Payload for a Batch document (item + batch name)."""
    return wms_qr_payload(doc.get("item"), doc.get("name"))


def wms_item_qr_payload(doc) -> str:
    """Payload for an Item document (item only, no batch)."""
    return wms_qr_payload(doc.get("item_code") or doc.get("name"))


def wms_qr_svg(data, scale=4, omit_size=False) -> str:
    """Inline SVG for `data`, rendered with pyqrcode (a dependency of Frappe itself).

    `QRCode.svg()` encodes everything it writes as UTF-8 *bytes* (it accepts a path or a binary
    stream), so it must be given a bytes buffer: a text buffer raises
    `TypeError: string argument expected, got 'bytes'` and the print format fails to render.

    With `omit_size` the SVG carries a `viewBox` instead of fixed `width`/`height` attributes, so a
    print format can size it with CSS (`svg { width: 32mm; height: 32mm }`) independently of the
    number of modules in the code - that is what the label sheets use.
    """
    try:
        import pyqrcode
    except ImportError:  # pragma: no cover - pyqrcode ships with frappe
        return '<div class="text-muted">pyqrcode is not installed on this site</div>'
    code = pyqrcode.create(data, error="M", encoding="utf-8")
    buffer = io.BytesIO()
    code.svg(buffer, scale=scale, xmldecl=False, svgns=True, quiet_zone=2, omithw=bool(omit_size))
    return buffer.getvalue().decode("utf-8")


def wms_batch_qr_svg(doc, scale=4, omit_size=False) -> str:
    return wms_qr_svg(wms_batch_qr_payload(doc), scale, omit_size)


def wms_item_qr_svg(doc, scale=4, omit_size=False) -> str:
    return wms_qr_svg(wms_item_qr_payload(doc), scale, omit_size)
