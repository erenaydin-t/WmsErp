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


class WarehouseLabelRuleTests(unittest.TestCase):
    """The label-printer labels of what a warehouse holds."""

    items = {
        "PCT": {"item_name": "Paracetamol", "has_batch_no": 1, "stock_uom": "Box"},
        "GZ": {"item_name": "Gauze", "has_batch_no": 0, "stock_uom": "Nos"},
    }
    batches = {"B-LATE": {"expiry_date": "2028-01-01"}, "B-SOON": {"expiry_date": "2026-12-01"}, "B-EMPTY": {"expiry_date": None}}

    def test_one_label_per_batch_in_stock_and_per_plain_item(self):
        bins = [
            {"item_code": "PCT", "warehouse": "W1", "actual_qty": 12},
            {"item_code": "GZ", "warehouse": "W1", "actual_qty": 5},
            {"item_code": "ZERO", "warehouse": "W1", "actual_qty": 0},
        ]
        balances = {("PCT", "W1", "B-LATE"): 7, ("PCT", "W1", "B-SOON"): 3, ("PCT", "W1", "B-EMPTY"): 0}
        labels = rules.stocked_labels(bins, balances, self.items, self.batches)
        self.assertEqual(
            [("GZ", None, 5.0), ("PCT", "B-SOON", 3.0), ("PCT", "B-LATE", 7.0), ("PCT", None, 2.0)],
            [(l["item_code"], l["batch_no"], l["qty"]) for l in labels],
            "earliest expiry first; 2 units no batch carries get an item label; empty batches are skipped",
        )
        self.assertEqual("2026-12-01", labels[1]["expiry_date"])
        self.assertEqual("Box", labels[1]["uom"])

    def test_item_labels_only_and_group_warehouses_merge(self):
        bins = [{"item_code": "PCT", "warehouse": "W1", "actual_qty": 4}, {"item_code": "PCT", "warehouse": "W2", "actual_qty": 6}]
        balances = {("PCT", "W1", "B-SOON"): 4, ("PCT", "W2", "B-SOON"): 6}
        merged = rules.stocked_labels(bins, balances, self.items, self.batches)
        self.assertEqual([("PCT", "B-SOON", 10.0)], [(l["item_code"], l["batch_no"], l["qty"]) for l in merged])
        plain = rules.stocked_labels(bins, balances, self.items, self.batches, per_batch=False)
        self.assertEqual([("PCT", None, 10.0)], [(l["item_code"], l["batch_no"], l["qty"]) for l in plain])

    def test_sizes_copies_modes(self):
        self.assertEqual((50.0, 30.0), rules.label_size(None, ""))
        self.assertEqual((100.0, 50.0), rules.label_size("100", 50))
        self.assertEqual(25.0, rules.qr_size(50, 30), "half the width: the text keeps the other half")
        self.assertEqual(26.0, rules.qr_size(100, 30), "label height minus margins")
        self.assertEqual(10.0, rules.qr_size(15, 15))
        self.assertEqual(3, rules.copies("3"))
        self.assertEqual("batch", rules.label_mode(" Batch "))
        for bad in ((5, 30), (50, 500), ("x", 30)):
            with self.assertRaises(rules.LabelRuleError):
                rules.label_size(*bad)
        for bad in (0, 51, "x"):
            with self.assertRaises(rules.LabelRuleError):
                rules.copies(bad)
        with self.assertRaises(rules.LabelRuleError):
            rules.label_mode("everything")

    def test_copies_are_consecutive_and_limited(self):
        labels = [{"item_code": "A"}, {"item_code": "B"}]
        self.assertEqual(["A", "A", "B", "B"], [l["item_code"] for l in rules.with_copies(labels, 2)])
        with self.assertRaises(rules.LabelRuleError):
            rules.with_copies([{"item_code": "A"}] * (rules.MAX_ROLL_LABELS + 1), 1)
        self.assertTrue(rules.is_roll_template("<!-- wms-label-roll -->"))
        self.assertFalse(rules.is_roll_template("<!-- wms-label-sheet -->"))
        self.assertEqual("Warehouse", rules.FORMAT_DOCTYPES[rules.ROLL_FORMAT])

    def test_pdf_options_force_the_label_size(self):
        options = rules.roll_pdf_options(50.0, 30.0)
        self.assertEqual("Custom", options["page-size"], "Frappe keeps width / height only with a Custom page size")
        self.assertEqual(("50mm", "30mm"), (options["page-width"], options["page-height"]))
        self.assertEqual({"0mm"}, {options[k] for k in ("margin-top", "margin-bottom", "margin-left", "margin-right")})
        self.assertEqual("62.5mm", rules.roll_pdf_options(62.5, 29)["page-width"])


@unittest.skipUnless(jinja2, "jinja2 is not installed")
class RollRenderingTests(SheetRenderingTests):
    """The label-printer template: one label per page, sized from the page's choice."""

    def test_one_label_per_page_with_the_scanned_payloads(self):
        labels = [
            {"item_code": "PCT", "item_name": "Paracetamol", "batch_no": "B-SOON", "expiry_date": "2026-12-01", "payload": '{"item_code":"PCT","batch_no":"B-SOON"}'},
            {"item_code": "GZ", "item_name": "Gauze", "batch_no": None, "expiry_date": None, "payload": '{"item_code":"GZ"}'},
            {"item_code": "GZ", "item_name": "GZ", "batch_no": None, "expiry_date": None, "payload": '{"item_code":"GZ"}'},
        ]
        html = self.template(rules.ROLL_FORMAT).render(labels=labels, label_width=50, label_height=30, qr_size=rules.qr_size(50, 30), count=3)
        self.assertIn(rules.ROLL_MARKER, html)
        self.assertEqual(3, html.count('<table class="wms-label"'))
        self.assertEqual(2, html.count('class="wms-page wms-break"'), "a page break after every label but the last")
        self.assertEqual(3, html.count('<div class="wms-page'), "every label in its own clipped page box")
        self.assertIn("width: 50mm; height: 29.4mm", html)
        self.assertIn("width: 25.0mm; height: 25.0mm", html)
        self.assertEqual([label["payload"] for label in labels], [call[0] for call in self.svg_calls])
        self.assertIn('<div class="wms-batch">B-SOON</div>', html)
        self.assertIn("formatted(2026-12-01)", html)
        self.assertEqual(1, html.count(">Gauze<"), "the name is not repeated when it equals the code")
        self.assertIn(".print-format { margin-top: 0mm;", html)


class TemplateUpgradeTests(unittest.TestCase):
    def test_only_unedited_old_templates_are_upgraded(self):
        current = open(os.path.join(TEMPLATES, rules.TEMPLATE_FILES[rules.ROLL_FORMAT]), encoding="utf-8").read()
        self.assertFalse(rules.is_unedited_old_template(rules.ROLL_FORMAT, current), "the current template is not superseded")
        self.assertFalse(rules.is_unedited_old_template(rules.ROLL_FORMAT, current + "<!-- site edit -->"))
        self.assertFalse(rules.is_unedited_old_template("WMS Batch QR Label", current))
        old = next(iter(rules.SUPERSEDED_TEMPLATES[rules.ROLL_FORMAT]))
        self.assertEqual(64, len(old))
        self.assertEqual(rules.template_hash("  <b>x</b>\n"), rules.template_hash("<b>x</b>"))

