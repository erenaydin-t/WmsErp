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


class HeldProgressTests(unittest.TestCase):
    def test_next_row_status_hold(self):
        self.assertEqual(rules.next_row_status(10, 10), rules.ROW_PICKED)
        self.assertEqual(rules.next_row_status(10, 10, hold=True), rules.ROW_PICKING)
        self.assertEqual(rules.next_row_status(0, 10, hold=True), rules.ROW_NOT_PICKED)
        self.assertEqual(rules.next_row_status(0, 10, rules.ROW_PICKING, hold=True), rules.ROW_PICKING)

    def test_stock_shortfalls(self):
        rows = [
            {"item_code": "A", "batch_no": "B1", "warehouse": "W", "picked": 5, "available": 3},
            {"item_code": "B", "batch_no": None, "warehouse": "W", "picked": 2, "available": 2},
            {"item_code": "C", "batch_no": "B3", "warehouse": "W", "picked": 1, "available": None},
            {"item_code": "D", "batch_no": "B4", "warehouse": "W", "picked": 0, "available": 0},
        ]
        short = rules.stock_shortfalls(rows)
        self.assertEqual([r["item_code"] for r in short], ["A"])
        self.assertAlmostEqual(short[0]["short"], 2)
        self.assertEqual(rules.stock_shortfalls([]), [])



class TargetQtyTests(unittest.TestCase):
    def test_picked_qty_set_by_the_senior_user_is_the_target(self):
        self.assertEqual(6.0, rules.target_qty(6, 10, 10, 1))
        self.assertEqual(12.0, rules.target_qty(12, 10, 10, 1))

    def test_falls_back_to_the_stock_quantity(self):
        self.assertEqual(10.0, rules.target_qty(0, 10, 10, 1))
        self.assertEqual(24.0, rules.target_qty(None, None, 2, 12))
        self.assertEqual(0.0, rules.target_qty(None, None, None, None))


class MultiBatchTests(unittest.TestCase):
    def test_which_rows_take_several_batches(self):
        self.assertTrue(rules.accepts_several_batches(1, None))
        self.assertTrue(rules.accepts_several_batches(1, " "))
        self.assertTrue(rules.accepts_several_batches(1, "B-1", batch_from_wms=1))
        self.assertFalse(rules.accepts_several_batches(1, "B-1"))
        self.assertFalse(rules.accepts_several_batches(0, None))
        self.assertFalse(rules.accepts_several_batches(1, None, foreign_bundle=True))

    def test_parse_batches_merges_and_drops_empty_entries(self):
        parsed = rules.parse_batches('[{"batch_no": " B-1 ", "qty": 3}, {"batch_no": "b-1", "qty": 2}, {"batch_no": "B-2", "qty": "5"}, {"batch_no": "B-3", "qty": 0}]')
        self.assertEqual([{"batch_no": "B-1", "qty": 5.0}, {"batch_no": "B-2", "qty": 5.0}], parsed)
        self.assertEqual(10.0, rules.batches_total(parsed))
        self.assertEqual([], rules.parse_batches(None))
        self.assertEqual([], rules.parse_batches("[]"))

    def test_parse_batches_rejects_bad_entries(self):
        for bad in ("nope", '{"batch_no": "B"}', '[{"batch_no": "B", "qty": -1}]', '[{"batch_no": "", "qty": 2}]', '[{"batch_no": "B", "qty": "x"}]', "[1]"):
            with self.assertRaises(rules.PickingRuleError, msg=bad):
                rules.parse_batches(bad)

    def test_single_batch_calls_of_older_apps(self):
        self.assertEqual([{"batch_no": "B-1", "qty": 4.0}], rules.single_batch_update([], "B-1", 4))
        # A scan of another batch replaces a list that holds nothing else.
        self.assertEqual([{"batch_no": "B-2", "qty": 4.0}], rules.single_batch_update([{"batch_no": "B-1", "qty": 0}], "B-2", 4))
        self.assertEqual([{"batch_no": "B-1", "qty": 7.0}], rules.single_batch_update([{"batch_no": "B-1", "qty": 4}], "b-1", 7))
        # A quantity correction without a batch applies to the only batch.
        self.assertEqual([{"batch_no": "B-1", "qty": 2.0}], rules.single_batch_update([{"batch_no": "B-1", "qty": 4}], None, 2))
        self.assertEqual([], rules.single_batch_update([{"batch_no": "B-1", "qty": 4}], None, 0))
        with self.assertRaises(rules.PickingRuleError):
            rules.single_batch_update([{"batch_no": "B-1", "qty": 4}], "B-2", 6)
        with self.assertRaises(rules.PickingRuleError):
            rules.single_batch_update([{"batch_no": "B-1", "qty": 4}, {"batch_no": "B-2", "qty": 2}], None, 6)
        with self.assertRaises(rules.PickingRuleError):
            rules.single_batch_update([], None, 3)


class AllocateBatchesTests(unittest.TestCase):
    def test_takes_batches_in_the_given_order(self):
        available = [{"batch_no": "OLD", "qty": 4}, {"batch_no": "NEW", "qty": 10}]
        self.assertEqual([{"batch_no": "OLD", "qty": 4.0}, {"batch_no": "NEW", "qty": 3.0}], rules.allocate_batches(available, 7))
        self.assertEqual([{"batch_no": "OLD", "qty": 2.0}], rules.allocate_batches(available, 2))

    def test_shortfall_and_bad_quantities(self):
        with self.assertRaises(rules.PickingRuleError):
            rules.allocate_batches([{"batch_no": "A", "qty": 1}], 2)
        with self.assertRaises(rules.PickingRuleError):
            rules.allocate_batches([{"batch_no": "A", "qty": 1}], 0)
        # Entries of the same batch (one per warehouse ledger line) are merged.
        self.assertEqual([{"batch_no": "A", "qty": 3.0}], rules.allocate_batches([{"batch_no": "A", "qty": 1}, {"batch_no": "A", "qty": 5}], 3))
