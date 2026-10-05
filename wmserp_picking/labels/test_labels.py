"""Unit tests for the QR label sheets (run without a bench:
`python -m unittest discover -s erpnext/wmserp_picking -p "test_*.py"`).

The pure helpers are tested directly; the shipped sheet templates are rendered with plain Jinja
(the same engine Frappe uses) and stand-ins for the `frappe` helpers, so the layout promise - twenty
batches on two pages (12 + 8), every label carrying its own QR payload - is checked here, not only
on a site.
"""

import json
import os
import re
import unittest

from wmserp_picking.labels import rules

try:  # pragma: no cover - depends on the machine running the tests
    import jinja2
    import jinja2.sandbox
except ImportError:  # pragma: no cover
    jinja2 = None

TEMPLATES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "templates", "print_formats")


class ParseNamesTests(unittest.TestCase):
    def test_accepts_json_lists_comma_strings_and_lists(self):
        self.assertEqual(["A", "B"], rules.parse_names('["A", "B"]'))
        self.assertEqual(["A", "B"], rules.parse_names("A, B"))
        self.assertEqual(["A", "B"], rules.parse_names(["A", "B"]))

    def test_drops_blanks_and_duplicates_but_keeps_the_order(self):
        self.assertEqual(["B-002", "B-001"], rules.parse_names(["B-002", "", None, "B-001", "B-002"]))

    def test_rejects_empty_and_oversized_selections(self):
        for value in (None, "", "[]", [None, ""]):
            with self.assertRaises(rules.LabelRuleError):
                rules.parse_names(value)
        with self.assertRaises(rules.LabelRuleError):
            rules.parse_names([f"B-{i}" for i in range(rules.MAX_LABELS + 1)])
        with self.assertRaises(rules.LabelRuleError):
            rules.parse_names("[not json")


class LayoutHelperTests(unittest.TestCase):
    def test_chunk_pages(self):
        self.assertEqual([12, 8], [len(page) for page in rules.chunk(list(range(20)), 12)])
        self.assertEqual([], rules.chunk([], 12))
        with self.assertRaises(rules.LabelRuleError):
            rules.chunk([1], 0)

    def test_page_size_and_file_name(self):
        self.assertEqual("A4", rules.page_size(None))
        self.assertEqual("Letter", rules.page_size("letter"))
        self.assertEqual("A4", rules.page_size("Tabloid"))
        self.assertEqual("batch_qr_labels_20.pdf", rules.file_name("Batch", 20))

    def test_sheet_detection_and_defaults(self):
        self.assertTrue(rules.is_sheet_template("<!-- wms-label-sheet --><div></div>"))
        self.assertFalse(rules.is_sheet_template("<div>{{ doc.name }}</div>"))
        self.assertFalse(rules.is_sheet_template(None))
        self.assertEqual("WMS Batch QR Label Sheet", rules.default_sheet_format("Batch"))
        self.assertEqual("WMS Item QR Label Sheet", rules.default_sheet_format("Item"))
        with self.assertRaises(rules.LabelRuleError):
            rules.default_sheet_format("Sales Order")

    def test_wrap_page_keeps_the_print_format_wrapper_and_escapes_the_title(self):
        html = rules.wrap_page("<p>x</p>", css=".a{}", title='Batch <"QR">')
        self.assertTrue(html.startswith("<!DOCTYPE html>"))
        self.assertIn('<div class="print-format"><p>x</p></div>', html)
        self.assertIn("<style>.a{}</style>", html)
        self.assertIn("Batch &lt;&quot;QR&quot;&gt;", html)

    def test_every_shipped_template_exists(self):
        for name, file_name in rules.TEMPLATE_FILES.items():
            path = os.path.join(TEMPLATES, file_name)
            self.assertTrue(os.path.exists(path), f"{name}: {path} is missing")
            with open(path, encoding="utf-8") as handle:
                html = handle.read()
            self.assertEqual(name.endswith("Sheet"), rules.is_sheet_template(html), name)


@unittest.skipUnless(jinja2, "jinja2 is not installed")
class SheetRenderingTests(unittest.TestCase):
    """Renders the shipped sheet templates like Frappe does (`frappe.render_template` is Jinja)."""

    def setUp(self):
        self.svg_calls = []

        def qr_svg(data, scale=4, omit_size=False):
            self.svg_calls.append((data, scale, omit_size))
            return f'<svg viewBox="0 0 29 29"><!--{data}--></svg>'

        def payload(item, batch=None):
            data = {"item_code": item}
            if batch:
                data["batch_no"] = batch
            return json.dumps(data, separators=(",", ":"))

        class FakeFrappe:
            @staticmethod
            def format_date(value):
                return f"formatted({value})"

        self.env = jinja2.sandbox.SandboxedEnvironment()
        self.env.globals.update(
            {
                "_": lambda text: text,
                "frappe": FakeFrappe(),
                "wms_qr_svg": qr_svg,
                "wms_batch_qr_payload": lambda doc: payload(doc.get("item"), doc.get("name")),
                "wms_item_qr_payload": lambda doc: payload(doc.get("item_code") or doc.get("name")),
            }
        )

    def template(self, name):
        with open(os.path.join(TEMPLATES, rules.TEMPLATE_FILES[name]), encoding="utf-8") as handle:
            return self.env.from_string(handle.read())

    def test_twenty_batches_fill_two_pages_with_twelve_and_eight_labels(self):
        docs = [{"name": f"B-{i:03d}", "item": f"ITEM-{i % 3}", "item_name": f"Item {i}", "expiry_date": "2027-01-31" if i % 2 else None} for i in range(20)]
        html = self.template("WMS Batch QR Label Sheet").render(docs=docs, doctype="Batch", count=20)

        pages = re.findall(r'<table class="wms-page( wms-break)?">(.*?)</table>', html, flags=re.S)
        self.assertEqual(2, len(pages))
        self.assertEqual(" wms-break", pages[0][0], "every page but the last breaks the page")
        self.assertEqual("", pages[1][0])
        self.assertEqual(12, pages[0][1].count('<td class="wms-label">'))
        self.assertEqual(8, pages[1][1].count('<td class="wms-label">'))
        self.assertEqual(4, pages[0][1].count("<tr>"), "12 labels in 3 columns are 4 rows")
        self.assertEqual(20, len(self.svg_calls))
        self.assertEqual(('{"item_code":"ITEM-0","batch_no":"B-000"}', 1, True), self.svg_calls[0])
        self.assertIn("Batch: B-019", html)
        self.assertIn("Expiry: formatted(2027-01-31)", html)
        self.assertIn(rules.SHEET_MARKER, html)
        self.assertIn(".print-format { margin-top: 8mm;", html, "margins stay in the template for Frappe's PDF renderer")
        self.assertIn("width: 62mm; height: 66mm", html)
        self.assertIn("width: 30mm; height: 30mm", html)

    def test_item_sheet_renders_item_only_payloads(self):
        docs = [{"name": f"ITEM-{i}", "item_code": f"ITEM-{i}", "item_name": f"Item {i}", "item_group": "Drugs"} for i in range(5)]
        html = self.template("WMS Item QR Label Sheet").render(docs=docs, doctype="Item", count=5)

        self.assertEqual(1, html.count('<table class="wms-page">'))
        self.assertEqual(5, html.count('<td class="wms-label">'))
        self.assertEqual(2, html.count("<tr>"))
        self.assertEqual('{"item_code":"ITEM-0"}', self.svg_calls[0][0])
        self.assertIn("Drugs", html)

    def test_single_item_label_renders_without_a_batch(self):
        env_globals = self.env.globals
        env_globals["wms_item_qr_svg"] = lambda doc, scale=4: env_globals["wms_qr_svg"](env_globals["wms_item_qr_payload"](doc), scale)
        html = self.template("WMS Item QR Label").render(doc={"name": "ITEM-7", "item_code": "ITEM-7", "item_name": "Seven", "item_group": "G"})
        self.assertIn("ITEM-7", html)
        self.assertIn('{"item_code":"ITEM-7"}', html)
        self.assertFalse(rules.is_sheet_template(html))


if __name__ == "__main__":
    unittest.main()
