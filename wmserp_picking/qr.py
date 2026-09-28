"""QR label helpers exposed to Jinja (print formats) through `hooks.jinja`.

The label content is a JSON object using the keys configured in *WMS Settings*, e.g.
`{"item_code": "ITEM-001", "batch_no": "B-2026-01"}`, which is exactly what the WMS app expects
when a picker scans a batch.
"""

import io
import json

from wmserp_picking.wms_erp_picking.doctype.wms_settings.wms_settings import get_qr_keys


def wms_qr_keys() -> dict:
    return get_qr_keys()


def wms_qr_payload(item_code, batch_no=None) -> str:
    keys = get_qr_keys()
    data = {keys["qr_item_key"]: item_code}
    if batch_no:
        data[keys["qr_batch_key"]] = batch_no
    return json.dumps(data, ensure_ascii=False, separators=(",", ":"))


def wms_batch_qr_payload(doc) -> str:
    """Payload for a Batch document (item + batch name)."""
    return wms_qr_payload(doc.get("item"), doc.get("name"))


def wms_qr_svg(data, scale=4) -> str:
    """Inline SVG for `data`, rendered with pyqrcode (a dependency of Frappe itself)."""
    try:
        import pyqrcode
    except ImportError:  # pragma: no cover - pyqrcode ships with frappe
        return '<div class="text-muted">pyqrcode is not installed on this site</div>'
    code = pyqrcode.create(data, error="M", encoding="utf-8")
    buffer = io.StringIO()
    code.svg(buffer, scale=scale, xmldecl=False, svgns=True, quiet_zone=2)
    return buffer.getvalue()


def wms_batch_qr_svg(doc, scale=4) -> str:
    return wms_qr_svg(wms_batch_qr_payload(doc), scale)
