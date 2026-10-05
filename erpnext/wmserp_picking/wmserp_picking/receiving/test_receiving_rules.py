"""Unit tests for the pure receiving rules (no bench needed)."""

import unittest

from wmserp_picking.receiving import rules


def row(name, item, qty, warehouse="Stores - WM", batch_no=None, needs_batch=False):
    return {"name": name, "item_code": item, "qty": qty, "warehouse": warehouse, "batch_no": batch_no, "needs_batch": needs_batch}


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


class PlanReceiptTests(unittest.TestCase):
    rows = [row("r1", "A", 10), row("r2", "B", 5, batch_no="B-1"), row("r3", "C", 2)]

    def test_full_receipt_has_no_differences(self):
        plan = rules.plan_receipt(self.rows, [{"row": "r1", "qty": 10}, {"row": "r2", "qty": 5}, {"row": "r3", "qty": 2}])
        self.assertEqual([], plan["differences"])
        self.assertEqual([], plan["removed"])
        self.assertEqual(17.0, plan["total_qty"])
        self.assertEqual({"qty": 5.0, "warehouse": "Stores - WM", "batch_no": "B-1"}, plan["updates"]["r2"])

    def test_partial_receipt_updates_removes_and_reports(self):
        plan = rules.plan_receipt(self.rows, [{"row": "r1", "qty": "8", "warehouse": "Quarantine - WM"}, {"row": "r2", "qty": 0}])
        self.assertEqual({"qty": 8.0, "warehouse": "Quarantine - WM", "batch_no": None}, plan["updates"]["r1"])
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
        self.assertEqual([{"row": "r1", "item_code": "A", "expected": 10.0, "counted": 1.0}], plan["differences"])

    def test_batch_tracked_rows_need_a_batch(self):
        rows = [row("r1", "A", 10, needs_batch=True)]
        with self.assertRaises(rules.ReceivingRuleError):
            rules.plan_receipt(rows, [{"row": "r1", "qty": 10}])
        plan = rules.plan_receipt(rows, [{"row": "r1", "qty": 10, "batch_no": " LOT-7 "}])
        self.assertEqual("LOT-7", plan["updates"]["r1"]["batch_no"])


if __name__ == "__main__":
    unittest.main()
