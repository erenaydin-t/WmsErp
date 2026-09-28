"""Unit tests for the pure picking rules (run without a bench:
`python -m unittest discover -s erpnext/wmserp_picking -p "test_*.py"`)."""

import datetime as dt
import unittest

from wmserp_picking.picking import rules


class RowCompletionTests(unittest.TestCase):
    def test_rows_need_the_exact_quantity(self):
        self.assertFalse(rules.is_row_complete(4, 10))
        self.assertTrue(rules.is_row_complete(10, 10))
        self.assertTrue(rules.is_row_complete(9.9999999, 10))
        self.assertTrue(rules.is_row_complete(0, 0))

    def test_rejects_negative_and_over_picks(self):
        with self.assertRaises(rules.PickingRuleError):
            rules.validate_picked_qty(-1, 10, "A")
        with self.assertRaises(rules.PickingRuleError):
            rules.validate_picked_qty(11, 10, "A")
        with self.assertRaises(rules.PickingRuleError):
            rules.validate_picked_qty("ten", 10)
        self.assertEqual(10.0, rules.validate_picked_qty("10", 10, "A"))

    def test_next_row_status(self):
        self.assertEqual(rules.ROW_NOT_PICKED, rules.next_row_status(0, 10))
        self.assertEqual(rules.ROW_PICKING, rules.next_row_status(0, 10, rules.ROW_PICKING))
        self.assertEqual(rules.ROW_PICKING, rules.next_row_status(3, 10))
        self.assertEqual(rules.ROW_PICKED, rules.next_row_status(10, 10))

    def test_card_completes_only_when_every_row_is_picked(self):
        self.assertFalse(rules.card_complete([]))
        self.assertFalse(rules.card_complete(["Picked", "Picking"]))
        self.assertFalse(rules.card_complete(["Picked", None]))
        self.assertTrue(rules.card_complete(["Picked", "Picked"]))

    def test_duration_prefers_server_timestamps(self):
        start = dt.datetime(2026, 9, 28, 9, 0, 0)
        end = dt.datetime(2026, 9, 28, 9, 2, 30)
        self.assertEqual(150.0, rules.duration_seconds(start, end, elapsed_hint=999))
        self.assertEqual(42.0, rules.duration_seconds(None, end, elapsed_hint="42"))
        self.assertEqual(0.0, rules.duration_seconds(None, end))
        self.assertEqual(0.0, rules.duration_seconds(end, start))


class QrLabelTests(unittest.TestCase):
    def test_parses_json_labels_with_configured_keys(self):
        parsed = rules.parse_qr('{"item_code": "ITEM-001", "batch_no": "B-1"}')
        self.assertEqual({"item_code": "ITEM-001", "batch_no": "B-1"}, parsed)
        parsed = rules.parse_qr('{"sku": " ITEM-001 ", "lot": 12}', item_key="sku", batch_key="lot")
        self.assertEqual({"item_code": "ITEM-001", "batch_no": "12"}, parsed)
        self.assertIsNone(rules.parse_qr('{"item_code": "ITEM-001"}')["batch_no"])
        self.assertIsNone(rules.parse_qr('{"item_code": "ITEM-001", "batch_no": null}')["batch_no"])

    def test_rejects_anything_that_is_not_a_json_label(self):
        for bad in ("", "ITEM-001", "8690000000017", "[1,2]", '{"batch_no": "B-1"}', '{"item_code": ""}', b"\x00"):
            with self.assertRaises(rules.PickingRuleError, msg=repr(bad)):
                rules.parse_qr(bad)

    def test_scan_check_against_row(self):
        self.assertEqual(rules.SCAN_MATCH, rules.check_scan("ITEM-001", "B-1", "ITEM-001", "B-1"))
        self.assertEqual(rules.SCAN_MATCH, rules.check_scan("item-001", "b-1", "ITEM-001", "B-1"))
        self.assertEqual(rules.SCAN_MATCH, rules.check_scan("ITEM-001", None, "ITEM-001", None))
        self.assertEqual(rules.SCAN_MATCH, rules.check_scan("ITEM-001", "ANY", "ITEM-001", None))
        self.assertEqual(rules.SCAN_WRONG_ITEM, rules.check_scan("ITEM-002", "B-1", "ITEM-001", "B-1"))
        self.assertEqual(rules.SCAN_WRONG_BATCH, rules.check_scan("ITEM-001", "B-2", "ITEM-001", "B-1"))
        self.assertEqual(rules.SCAN_MISSING_BATCH, rules.check_scan("ITEM-001", None, "ITEM-001", "B-1"))


class TargetDocumentTests(unittest.TestCase):
    def test_purpose_mapping(self):
        self.assertEqual((rules.DELIVERY_NOTE, None), rules.target_document("Delivery"))
        self.assertEqual((rules.STOCK_ENTRY, "Material Transfer"), rules.target_document("Material Transfer"))
        self.assertEqual((rules.STOCK_ENTRY, "Material Issue"), rules.target_document("Material Issue"))
        self.assertEqual(
            (rules.STOCK_ENTRY, "Material Transfer for Manufacture"),
            rules.target_document("Material Transfer for Manufacture"),
        )

    def test_unknown_purpose(self):
        with self.assertRaises(rules.PickingRuleError):
            rules.target_document("Something Else")


class PermissionTests(unittest.TestCase):
    def test_assigned_users_parses_the_assign_column(self):
        self.assertEqual([], rules.assigned_users(None))
        self.assertEqual([], rules.assigned_users("garbage"))
        self.assertEqual(["a@x.com", "b@x.com"], rules.assigned_users('["a@x.com", "b@x.com"]'))

    def test_row_and_card_permissions(self):
        self.assertTrue(rules.can_act_on_row("p@x.com", "p@x.com", ["Stock User"]))
        self.assertFalse(rules.can_act_on_row("o@x.com", "p@x.com", ["Stock User"]))
        self.assertTrue(rules.can_act_on_row("boss@x.com", "p@x.com", ["Stock Manager"]))
        self.assertFalse(rules.can_act_on_row("boss@x.com", "p@x.com", ["Stock Manager"], allow_supervisor=False))
        self.assertFalse(rules.can_act_on_row("Guest", None, ["Guest"]))
        self.assertTrue(rules.can_act_on_card("p@x.com", ["a@x.com", "p@x.com"], []))
        self.assertFalse(rules.can_act_on_card("o@x.com", ["a@x.com", "p@x.com"], ["Stock User"]))
        self.assertTrue(rules.can_act_on_card("boss@x.com", ["a@x.com"], ["System Manager"]))


class KpiTests(unittest.TestCase):
    def test_summary_aggregates_rows(self):
        rows = [
            {"parent": "PL-1", "picked_qty": 10, "duration_seconds": 60},
            {"parent": "PL-1", "picked_qty": 5, "duration_seconds": 30},
            {"parent": "PL-2", "picked_qty": 2, "duration_seconds": None},
        ]
        summary = rules.kpi_summary(rows)
        self.assertEqual(3, summary["rows_picked"])
        self.assertEqual(17.0, summary["qty_picked"])
        self.assertEqual(2, summary["pick_lists_touched"])
        self.assertEqual(90.0, summary["total_seconds"])
        self.assertEqual(45.0, summary["avg_seconds_per_row"])
        self.assertEqual(30.0, summary["fastest_seconds"])
        self.assertEqual(60.0, summary["slowest_seconds"])
        self.assertAlmostEqual(80.0, summary["rows_per_hour"])

    def test_empty_summary(self):
        summary = rules.kpi_summary([])
        self.assertEqual(0, summary["rows_picked"])
        self.assertIsNone(summary["avg_seconds_per_row"])
        self.assertIsNone(summary["rows_per_hour"])


if __name__ == "__main__":
    unittest.main()
