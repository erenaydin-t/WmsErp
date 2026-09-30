"""Unit tests for the QR label helpers (run without a bench:
`python -m unittest discover -s erpnext/wmserp_picking -p "test_*.py"`).

The rendering tests run against the real pyqrcode when it is importable (it ships with Frappe) and
always against a stand-in that writes UTF-8 bytes exactly like `pyqrcode.QRCode.svg()` does, so the
bytes-vs-text regression is caught even on a machine without pyqrcode.
"""

import io
import json
import sys
import types
import unittest
import xml.etree.ElementTree as ET
from unittest import mock

from wmserp_picking import qr

try:  # pragma: no cover - depends on the machine running the tests
    import pyqrcode  # noqa: F401

    HAS_PYQRCODE = True
except ImportError:
    HAS_PYQRCODE = False

DEFAULT_KEYS = {"qr_item_key": "item_code", "qr_batch_key": "batch_no"}
BATCH = "DPL-20100067-00443"  # a representative batch name (also the item code in the label)
BATCH_PAYLOAD = '{"item_code":"DPL-20100067-00443","batch_no":"DPL-20100067-00443"}'


def fake_pyqrcode(calls):
    """A minimal pyqrcode stand-in whose `svg()` writes UTF-8 *bytes*, like pyqrcode 1.2.x."""

    class FakeCode:
        def __init__(self, data):
            self.data = data

        def svg(self, file, **kwargs):
            calls.append({"data": self.data, "svg_kwargs": kwargs})
            body = '<svg xmlns="http://www.w3.org/2000/svg" width="8" height="8"><!-- ' + self.data + ' é --><path d="M0 0h8v8H0z"/></svg>'
            file.write(body.encode("utf-8"))  # raises TypeError on a text buffer

    module = types.ModuleType("pyqrcode")

    def create(data, **kwargs):
        calls.append({"create": data, "create_kwargs": kwargs})
        return FakeCode(data)

    module.create = create
    return module


class QrPayloadTests(unittest.TestCase):
    def test_batch_payload_uses_the_configured_keys(self):
        with mock.patch.object(qr, "wms_qr_keys", return_value=DEFAULT_KEYS):
            payload = qr.wms_qr_payload(BATCH, BATCH)
        self.assertEqual(payload, BATCH_PAYLOAD)
        self.assertEqual(json.loads(payload), {"item_code": BATCH, "batch_no": BATCH})

    def test_payload_without_batch_and_with_custom_keys(self):
        with mock.patch.object(qr, "wms_qr_keys", return_value=DEFAULT_KEYS):
            self.assertEqual(qr.wms_qr_payload("ITEM-001"), '{"item_code":"ITEM-001"}')
        custom = {"qr_item_key": "sku", "qr_batch_key": "lot"}
        with mock.patch.object(qr, "wms_qr_keys", return_value=custom):
            self.assertEqual(qr.wms_qr_payload("ITEM-001", "L-1"), '{"sku":"ITEM-001","lot":"L-1"}')

    def test_batch_document_payload(self):
        doc = {"doctype": "Batch", "name": BATCH, "item": BATCH, "expiry_date": "2027-01-31"}
        with mock.patch.object(qr, "wms_qr_keys", return_value=DEFAULT_KEYS):
            self.assertEqual(qr.wms_batch_qr_payload(doc), BATCH_PAYLOAD)


class QrSvgTests(unittest.TestCase):
    def test_svg_accepts_the_utf8_bytes_pyqrcode_writes(self):
        """Regression: pyqrcode writes bytes, so a text buffer raised
        `TypeError: string argument expected, got 'bytes'` and the label never rendered."""
        calls = []
        with mock.patch.dict(sys.modules, {"pyqrcode": fake_pyqrcode(calls)}):
            svg = qr.wms_qr_svg(BATCH_PAYLOAD, scale=3)
        self.assertIsInstance(svg, str)
        self.assertTrue(svg.startswith("<svg"), svg[:40])
        self.assertIn(BATCH_PAYLOAD, svg)
        self.assertIn("é", svg)  # decoded back from UTF-8, not left as bytes
        self.assertEqual(calls[0], {"create": BATCH_PAYLOAD, "create_kwargs": {"error": "M", "encoding": "utf-8"}})
        self.assertEqual(calls[1]["svg_kwargs"], {"scale": 3, "xmldecl": False, "svgns": True, "quiet_zone": 2})

    def test_a_text_buffer_would_still_fail(self):
        """Documents the failure mode the fix guards against (pyqrcode's contract, not ours)."""
        calls = []
        code = fake_pyqrcode(calls).create(BATCH_PAYLOAD)
        with self.assertRaises(TypeError):
            code.svg(io.StringIO())

    def test_batch_svg_renders_the_batch_payload(self):
        calls = []
        doc = {"name": BATCH, "item": BATCH}
        with mock.patch.dict(sys.modules, {"pyqrcode": fake_pyqrcode(calls)}), mock.patch.object(
            qr, "wms_qr_keys", return_value=DEFAULT_KEYS
        ):
            svg = qr.wms_batch_qr_svg(doc)
        self.assertEqual(calls[0]["create"], BATCH_PAYLOAD)
        self.assertEqual(calls[1]["svg_kwargs"]["scale"], 4)
        self.assertIn(BATCH_PAYLOAD, svg)

    def test_missing_pyqrcode_degrades_to_a_notice(self):
        with mock.patch.dict(sys.modules, {"pyqrcode": None}):  # makes `import pyqrcode` raise ImportError
            svg = qr.wms_qr_svg(BATCH_PAYLOAD)
        self.assertIn("pyqrcode is not installed", svg)

    @unittest.skipUnless(HAS_PYQRCODE, "pyqrcode is not installed on this machine")
    def test_real_pyqrcode_returns_a_valid_svg_document(self):
        svg = qr.wms_qr_svg(BATCH_PAYLOAD)
        self.assertIsInstance(svg, str)
        self.assertTrue(svg.startswith("<svg"), svg[:40])  # xmldecl=False: inline-able in the print format
        self.assertIn('xmlns="http://www.w3.org/2000/svg"', svg)
        root = ET.fromstring(svg)  # well-formed XML
        self.assertEqual(root.tag, "{http://www.w3.org/2000/svg}svg")
        self.assertTrue(root.get("width") and root.get("height"))
        self.assertTrue(list(root.iter("{http://www.w3.org/2000/svg}path")), "no QR modules drawn")
        self.assertEqual(root.get("class"), "pyqrcode")

    @unittest.skipUnless(HAS_PYQRCODE, "pyqrcode is not installed on this machine")
    def test_real_pyqrcode_encodes_the_batch_payload_round_trip(self):
        import pyqrcode

        code = pyqrcode.create(BATCH_PAYLOAD, error="M", encoding="utf-8")
        self.assertEqual(code.data.decode("utf-8") if isinstance(code.data, bytes) else code.data, BATCH_PAYLOAD)
        larger = qr.wms_qr_svg(BATCH_PAYLOAD, scale=8)
        smaller = qr.wms_qr_svg(BATCH_PAYLOAD, scale=2)
        self.assertGreater(int(ET.fromstring(larger).get("width")), int(ET.fromstring(smaller).get("width")))


if __name__ == "__main__":
    unittest.main()
