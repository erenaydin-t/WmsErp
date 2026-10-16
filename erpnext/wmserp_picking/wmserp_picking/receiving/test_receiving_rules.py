"""Unit tests for the pure receiving rules (no bench needed)."""

import unittest

from wmserp_picking.receiving import rules


def row(name, item, qty, warehouse="Stores - WM", batch_no=None, needs_batch=False, has_batch_no=None):
    return {
        "name": name,
        "item_code": item,
        "qty": qty,
        "warehouse": warehouse,
        "batch_no": batch_no,
        "needs_batch": needs_batch,
        "has_batch_no": has_batch_no if has_batch_no is not None else bool(batch_no or needs_batch),
    }


class StateTests(unittest.TestCase):
    def test_parse_states_accepts_lines_and_commas(self):
        self.assertEqual(["Approved", "At Warehouse"], rules.parse_states("Approved\n At Warehouse \n\nApproved"))
        self.assertEqual(["A", "B"], rules.parse_states("A, B"))
        self.assertEqual([], rules.parse_states(None))

    def test_editable_states_are_the_draft_states_of_the_users_roles(self):
        states = [
            {"state": "Draft", "doc_status": "0", "allow_edit": "Purchase User"},
            {"state": "Pending Approval", "doc_status": "0", "allow_edit": "Purchase Manager"},
            {"state": "At Warehouse", "doc_status": "0", "allow_edit": "Stock User"},
            {"state": "Received", "doc_status": "1", "allow_edit": "Stock User"},
        ]
        self.assertEqual(["At Warehouse"], rules.editable_states(states, ["Stock User", "Employee"]))
        self.assertEqual(["Draft"], rules.editable_states(states, ["Purchase User"]))
        self.assertEqual([], rules.editable_states(states, ["Accounts User"]))

    def test_choose_submit_action_prefers_receiving_words(self):
        docstatus = {"At Warehouse": "0", "Received": "1", "Rejected": "0", "Done": "1"}
        transitions = [
            {"action": "Reject", "next_state": "Rejected"},
            {"action": "Close", "next_state": "Done"},
            {"action": "Receive Goods", "next_state": "Received"},
        ]
        self.assertEqual("Receive Goods", rules.choose_submit_action(transitions, docstatus))
        self.assertEqual("Close", rules.choose_submit_action(transitions[:2], docstatus))
        self.assertIsNone(rules.choose_submit_action(transitions[:1], docstatus))
        self.assertIsNone(rules.choose_submit_action([], docstatus))


class FieldValueTests(unittest.TestCase):
    def test_parse_field_values(self):
        self.assertEqual({}, rules.parse_field_values(None))
        self.assertEqual({}, rules.parse_field_values("  "))
        self.assertEqual(
            {"Purchase Receipt Item.department": "Warehouse - E", "cost_center": "Main - E"},
            rules.parse_field_values('{"Purchase Receipt Item.department": "Warehouse - E", "cost_center": "Main - E", "project": "", "x": null}'),
        )
        with self.assertRaises(rules.ReceivingRuleError):
            rules.parse_field_values("{not json")
        with self.assertRaises(rules.ReceivingRuleError):
            rules.parse_field_values("[1, 2]")

    def test_split_field_key(self):
        self.assertEqual(("Purchase Receipt Item", "department"), rules.split_field_key("Purchase Receipt Item.department"))
        self.assertEqual((None, "department"), rules.split_field_key("department"))


class BundleTests(unittest.TestCase):
    def test_bundle_batch_is_the_single_batch_of_the_entries(self):
        self.assertEqual("LOT-1", rules.bundle_batch([{"batch_no": "LOT-1", "qty": 4}, {"batch_no": "LOT-1", "qty": 6}]))
        self.assertIsNone(rules.bundle_batch([{"batch_no": "LOT-1"}, {"batch_no": "LOT-2"}]))
        self.assertIsNone(rules.bundle_batch([{"serial_no": "SN-1"}]))
        self.assertIsNone(rules.bundle_batch([]))
        self.assertIsNone(rules.bundle_batch(None))


class PlanReceiptTests(unittest.TestCase):
    rows = [row("r1", "A", 10), row("r2", "B", 5, batch_no="B-1"), row("r3", "C", 2)]

    def test_full_receipt_has_no_differences(self):
        plan = rules.plan_receipt(self.rows, [{"row": "r1", "qty": 10}, {"row": "r2", "qty": 5}, {"row": "r3", "qty": 2}])
        self.assertEqual([], plan["differences"])
        self.assertEqual([], plan["removed"])
        self.assertEqual([], plan["cleared"])
        self.assertEqual(17.0, plan["total_qty"])
        self.assertEqual({"qty": 5.0, "warehouse": "Stores - WM", "batch_no": "B-1", "batches": [{"batch_no": "B-1", "qty": 5.0}]}, plan["updates"]["r2"])

    def test_partial_receipt_updates_removes_and_reports(self):
        plan = rules.plan_receipt(self.rows, [{"row": "r1", "qty": "8", "warehouse": "Quarantine - WM"}, {"row": "r2", "qty": 0}])
        self.assertEqual({"qty": 8.0, "warehouse": "Quarantine - WM", "batch_no": None, "batches": []}, plan["updates"]["r1"])
        self.assertEqual(["r2", "r3"], plan["removed"])
        self.assertEqual(
            [
                {"row": "r1", "item_code": "A", "expected": 10.0, "counted": 8.0},
                {"row": "r2", "item_code": "B", "expected": 5.0, "counted": 0.0},
                {"row": "r3", "item_code": "C", "expected": 2.0, "counted": 0.0},
            ],
            plan["differences"],
        )

    def test_over_receipt_is_allowed_but_reported(self):
        plan = rules.plan_receipt(self.rows, [{"row": "r1", "qty": 12}, {"row": "r2", "qty": 5}, {"row": "r3", "qty": 2}])
        self.assertEqual([{"row": "r1", "item_code": "A", "expected": 10.0, "counted": 12.0}], plan["differences"])

    def test_refusals(self):
        with self.assertRaises(rules.ReceivingRuleError):
            rules.plan_receipt(self.rows, [{"row": "nope", "qty": 1}])
        with self.assertRaises(rules.ReceivingRuleError):
            rules.plan_receipt(self.rows, [{"row": "r1", "qty": -1}])
        with self.assertRaises(rules.ReceivingRuleError):
            rules.plan_receipt(self.rows, [{"row": "r1", "qty": 1}, {"row": "r1", "qty": 2}])
        with self.assertRaises(rules.ReceivingRuleError):
            rules.plan_receipt(self.rows, [])
        with self.assertRaises(rules.ReceivingRuleError):
            rules.plan_receipt(self.rows, [{"row": "r1", "qty": "ten"}])

    def test_saving_progress_keeps_uncounted_rows_untouched(self):
        plan = rules.plan_receipt(self.rows, [{"row": "r1", "qty": 1}, {"row": "r2", "qty": 0}], remove_unreceived=False)
        self.assertEqual(["r1"], list(plan["updates"]))
        self.assertEqual([], plan["removed"])
        self.assertEqual(["r2"], plan["cleared"])  # sent as 0: its saved count is reset; r3 was not sent and stays
        self.assertEqual([{"row": "r1", "item_code": "A", "expected": 10.0, "counted": 1.0}], plan["differences"])

    def test_resetting_every_row_is_still_a_progress_save(self):
        plan = rules.plan_receipt(self.rows, [{"row": "r1", "qty": 0}], remove_unreceived=False)
        self.assertEqual(({}, ["r1"], 0.0), (plan["updates"], plan["cleared"], plan["total_qty"]))
        with self.assertRaises(rules.ReceivingRuleError):  # the final receive needs at least one row
            rules.plan_receipt(self.rows, [{"row": "r1", "qty": 0}], remove_unreceived=True)

    def test_batch_tracked_rows_need_a_batch(self):
        rows = [row("r1", "A", 10, needs_batch=True)]
        with self.assertRaises(rules.ReceivingRuleError):
            rules.plan_receipt(rows, [{"row": "r1", "qty": 10}])
        plan = rules.plan_receipt(rows, [{"row": "r1", "qty": 10, "batch_no": " LOT-7 "}])
        self.assertEqual("LOT-7", plan["updates"]["r1"]["batch_no"])
        self.assertEqual([{"batch_no": "LOT-7", "qty": 10.0}], plan["updates"]["r1"]["batches"])

    def test_a_row_received_in_several_batches_sums_them_and_keeps_each(self):
        rows = [row("r1", "A", 10, needs_batch=True)]
        counted = [{"row": "r1", "qty": 999, "batches": [{"batch_no": " 12-A-LOT1 ", "qty": "6"}, {"batch_no": "13-A-LOT2", "qty": 3}, {"batch_no": "14-A-LOT3", "qty": 0}, {"batch_no": "", "qty": 0}]}]
        plan = rules.plan_receipt(rows, counted)
        update = plan["updates"]["r1"]
        self.assertEqual(9.0, update["qty"])  # the sum of the batches, not the row's own qty
        self.assertEqual("12-A-LOT1", update["batch_no"])
        self.assertEqual([{"batch_no": "12-A-LOT1", "qty": 6.0}, {"batch_no": "13-A-LOT2", "qty": 3.0}], update["batches"])
        self.assertEqual([{"row": "r1", "item_code": "A", "expected": 10.0, "counted": 9.0}], plan["differences"])
        self.assertEqual(({"batch_no": "12-A-LOT1", "qty": 6.0}, [{"batch_no": "13-A-LOT2", "qty": 3.0}]), rules.split_batches(update))
        # Only zero batches: the row counts as not received.
        plan = rules.plan_receipt(rows, [{"row": "r1", "batches": [{"batch_no": "12-A-LOT1", "qty": 0}]}], remove_unreceived=False)
        self.assertEqual(["r1"], plan["cleared"])

    def test_batch_refusals(self):
        rows = [row("r1", "A", 10, needs_batch=True), row("r2", "B", 5)]
        with self.assertRaises(rules.ReceivingRuleError):  # same batch twice on one row
            rules.plan_receipt(rows, [{"row": "r1", "batches": [{"batch_no": "L1", "qty": 1}, {"batch_no": "L1", "qty": 2}]}])
        with self.assertRaises(rules.ReceivingRuleError):  # a quantity without a batch number
            rules.plan_receipt(rows, [{"row": "r1", "batches": [{"batch_no": "", "qty": 2}]}])
        with self.assertRaises(rules.ReceivingRuleError):  # batches on a plain item
            rules.plan_receipt(rows, [{"row": "r2", "batches": [{"batch_no": "L1", "qty": 2}]}])
        with self.assertRaises(rules.ReceivingRuleError):
            rules.plan_receipt(rows, [{"row": "r1", "batches": [{"batch_no": "L1", "qty": -2}]}])

    def test_split_batches_of_a_row_without_batches(self):
        self.assertEqual(({"batch_no": None, "qty": 4.0}, []), rules.split_batches({"qty": 4.0, "batch_no": None, "batches": []}))


class BatchNamingTests(unittest.TestCase):
    def test_batch_key_ignores_spaces_and_case(self):
        self.assertEqual(rules.batch_key(" SPH 405000439-001 "), rules.batch_key("sph405000439-001"))
        self.assertEqual("", rules.batch_key(None))

    def test_patterns(self):
        self.assertEqual("{counter}-{item}-{batch_no}", rules.default_batch_id_pattern(True))
        self.assertEqual("{batch_no}", rules.default_batch_id_pattern(False))
        self.assertEqual("7582-13100074-SPH-1", rules.render_batch_id("{counter}-{item}-{batch_no}", "13100074", "SPH-1", 7582))
        self.assertEqual("SPH-1", rules.render_batch_id("", "13100074", "SPH-1"))
        self.assertEqual("SPH-1", rules.render_batch_id(None, "13100074", "SPH-1", 3))
        self.assertTrue(rules.needs_counter("{counter}-{batch_no}"))
        self.assertFalse(rules.needs_counter("{batch_no}"))
        with self.assertRaises(rules.ReceivingRuleError):
            rules.render_batch_id("{supplier}-{batch_no}", "A", "B", 1)

    def test_next_counter_continues_the_numeric_prefix(self):
        self.assertEqual(506037, rules.next_counter("506036-20100067-MY262838A11"))
        self.assertEqual(1, rules.next_counter(None))
        self.assertEqual(1, rules.next_counter("LOT-7"))

    def test_counted_batches_round_trip(self):
        text = rules.serialize_counted_batches([{"batch_no": "12-A-L1", "qty": 4.0}, {"batch_no": "", "qty": 2}])
        self.assertEqual('[{"batch_no": "12-A-L1", "qty": 4.0}]', text)
        self.assertEqual([{"batch_no": "12-A-L1", "qty": 4.0}], rules.parse_counted_batches(text))
        self.assertIsNone(rules.serialize_counted_batches([]))
        self.assertEqual([], rules.parse_counted_batches(None))
        self.assertEqual([], rules.parse_counted_batches("{not json"))
        self.assertEqual([], rules.parse_counted_batches('[{"batch_no": "x", "qty": 0}, 5]'))


if __name__ == "__main__":
    unittest.main()
