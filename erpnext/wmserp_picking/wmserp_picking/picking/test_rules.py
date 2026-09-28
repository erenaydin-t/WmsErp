"""Unit tests for the pure picking rules (run without a bench:
`python -m unittest discover -s erpnext/wmserp_picking -p "test_*.py"`)."""

import unittest

from wmserp_picking.picking import rules


class RowStatusTests(unittest.TestCase):
    def test_row_status_thresholds(self):
        self.assertEqual(rules.ROW_NOT_PICKED, rules.row_status(0, 10))
        self.assertEqual(rules.ROW_PARTIAL, rules.row_status(4, 10))
        self.assertEqual(rules.ROW_PICKED, rules.row_status(10, 10))
        self.assertEqual(rules.ROW_PICKED, rules.row_status(9.9999999, 10))

    def test_mandatory_rows_need_the_exact_quantity(self):
        self.assertFalse(rules.is_row_complete(4, 10))
        self.assertTrue(rules.is_row_complete(10, 10))
        self.assertTrue(rules.is_row_complete(0, 0))

    def test_optional_rows_may_be_short_but_not_over(self):
        self.assertTrue(rules.is_row_complete(0, 10, optional=True))
        self.assertTrue(rules.is_row_complete(10, 10, optional=True))
        self.assertFalse(rules.is_row_complete(11, 10, optional=True))

    def test_incomplete_rows_lists_only_blocking_rows(self):
        rows = [
            {"name": "a", "item_code": "A", "picked_qty": 10, "required_qty": 10, "optional": 0},
            {"name": "b", "item_code": "B", "picked_qty": 3, "required_qty": 5, "optional": 0},
            {"name": "c", "item_code": "C", "picked_qty": 0, "required_qty": 2, "optional": 1},
        ]
        self.assertEqual(["b"], [row["name"] for row in rules.incomplete_rows(rows)])


class QuantityValidationTests(unittest.TestCase):
    def test_rejects_negative_and_over_picks(self):
        with self.assertRaises(rules.PickingRuleError):
            rules.validate_picked_qty(-1, 10, "A")
        with self.assertRaises(rules.PickingRuleError):
            rules.validate_picked_qty(11, 10, "A")
        self.assertEqual(10.0, rules.validate_picked_qty("10", 10, "A"))

    def test_rejects_non_numeric_quantities(self):
        with self.assertRaises(rules.PickingRuleError):
            rules.validate_picked_qty("ten", 10)


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


class ProgressPayloadTests(unittest.TestCase):
    def test_accepts_json_string_and_list(self):
        payload = '[{"name": "row1", "picked_qty": "2.5", "batch_no": " B-1 "}]'
        self.assertEqual([{"name": "row1", "picked_qty": 2.5, "batch_no": "B-1"}], rules.normalize_progress_rows(payload))
        self.assertEqual([{"name": "row1", "picked_qty": 0.0}], rules.normalize_progress_rows([{"name": "row1"}]))

    def test_rejects_bad_payloads(self):
        for bad in ("not json", {"name": "x"}, [{"picked_qty": 1}], [{"name": "a"}, {"name": "a"}], [{"name": "a", "picked_qty": -1}]):
            with self.assertRaises(rules.PickingRuleError, msg=repr(bad)):
                rules.normalize_progress_rows(bad)


class AssignmentTests(unittest.TestCase):
    def test_assigned_users_parses_the_assign_column(self):
        self.assertEqual([], rules.assigned_users(None))
        self.assertEqual([], rules.assigned_users("garbage"))
        self.assertEqual(["a@x.com", "b@x.com"], rules.assigned_users('["a@x.com", "b@x.com"]'))

    def test_can_act(self):
        self.assertTrue(rules.can_act("picker@x.com", "picker@x.com", [], ["Stock User"]))
        self.assertTrue(rules.can_act("helper@x.com", None, ["helper@x.com"], []))
        self.assertTrue(rules.can_act("boss@x.com", "picker@x.com", [], ["Stock Manager"]))
        self.assertFalse(rules.can_act("boss@x.com", "picker@x.com", [], ["Stock Manager"], allow_supervisor=False))
        self.assertFalse(rules.can_act("other@x.com", "picker@x.com", [], ["Stock User"]))
        self.assertFalse(rules.can_act("Guest", None, [], ["Guest"]))


if __name__ == "__main__":
    unittest.main()
