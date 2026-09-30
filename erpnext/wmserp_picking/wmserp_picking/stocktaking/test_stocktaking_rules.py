"""Unit tests for the pure stocktaking rules (no bench needed)."""

import unittest

from wmserp_picking.stocktaking import rules


class CountEvaluationTests(unittest.TestCase):
    def test_first_count_that_matches_is_accepted(self):
        result = rules.evaluate_count(rules.ITEM_ASSIGNED, None, 100, 100)
        self.assertEqual(rules.COUNT_1, result["count_type"])
        self.assertEqual(rules.ITEM_COUNTED, result["status"])
        self.assertEqual(100.0, result["final_qty"])
        self.assertEqual(rules.OUTCOME_ACCEPTED, result["outcome"])
        self.assertTrue(result["matched"])

    def test_tolerance_makes_small_differences_match(self):
        self.assertTrue(rules.is_matched(99.5, 100, tolerance=0.5))
        self.assertFalse(rules.is_matched(99, 100, tolerance=0.5))
        result = rules.evaluate_count(rules.ITEM_NOT_COUNTED, None, 99.5, 100, tolerance=0.5)
        self.assertEqual(rules.ITEM_COUNTED, result["status"])

    def test_first_count_that_differs_requires_a_second_count(self):
        result = rules.evaluate_count(rules.ITEM_NOT_COUNTED, None, 95, 100)
        self.assertEqual(rules.COUNT_1, result["count_type"])
        self.assertEqual(rules.ITEM_RECOUNT_REQUIRED, result["status"])
        self.assertEqual(rules.COUNT_2, result["next_count_type"])
        self.assertIsNone(result["final_qty"])
        self.assertEqual(rules.OUTCOME_SECOND_COUNT_REQUIRED, result["outcome"])

    def test_second_count_goes_to_the_manager_when_it_still_differs(self):
        result = rules.evaluate_count(rules.ITEM_RECOUNT_REQUIRED, rules.COUNT_2, 97, 100)
        self.assertEqual(rules.COUNT_2, result["count_type"])
        self.assertEqual(rules.ITEM_MANAGER_REVIEW, result["status"])
        self.assertEqual(97.0, result["final_qty"])
        self.assertEqual(rules.OUTCOME_MANAGER_REVIEW, result["outcome"])

    def test_second_count_that_matches_erp_is_accepted(self):
        result = rules.evaluate_count(rules.ITEM_RECOUNT_REQUIRED, rules.COUNT_2, 100, 100)
        self.assertEqual(rules.ITEM_COUNTED, result["status"])
        self.assertEqual(rules.OUTCOME_ACCEPTED, result["outcome"])

    def test_without_second_count_a_difference_goes_straight_to_review(self):
        result = rules.evaluate_count(rules.ITEM_NOT_COUNTED, None, 95, 100, require_second_count=False)
        self.assertEqual(rules.ITEM_MANAGER_REVIEW, result["status"])
        self.assertEqual(95.0, result["final_qty"])

    def test_manager_recount(self):
        differing = rules.evaluate_count(rules.ITEM_RECOUNT_REQUIRED, rules.RECOUNT, 98, 100)
        self.assertEqual(rules.RECOUNT, differing["count_type"])
        self.assertEqual(rules.ITEM_RECOUNTED, differing["status"])
        self.assertEqual(98.0, differing["final_qty"])
        self.assertEqual(rules.OUTCOME_RECOUNT_RECORDED, differing["outcome"])
        matching = rules.evaluate_count(rules.ITEM_RECOUNT_REQUIRED, rules.RECOUNT, 100, 100)
        self.assertEqual(rules.ITEM_COUNTED, matching["status"])
        self.assertEqual(rules.OUTCOME_ACCEPTED, matching["outcome"])

    def test_counted_rows_are_locked_by_default(self):
        for status in (rules.ITEM_COUNTED, rules.ITEM_MANAGER_REVIEW, rules.ITEM_RECOUNTED, rules.ITEM_APPROVED):
            with self.assertRaises(rules.AlreadyCountedError) as ctx:
                rules.evaluate_count(status, None, 100, 100, counted_by="ali@example.com")
            self.assertEqual("ali@example.com", ctx.exception.counted_by)
        with self.assertRaises(rules.AlreadyCountedError):
            rules.evaluate_count(rules.ITEM_FINALIZED, None, 100, 100, policy=rules.POLICY_ALLOW)

    def test_allow_policy_records_an_additional_count(self):
        result = rules.evaluate_count(rules.ITEM_COUNTED, None, 90, 100, policy=rules.POLICY_ALLOW)
        self.assertEqual(rules.RECOUNT, result["count_type"])
        self.assertEqual(rules.ITEM_MANAGER_REVIEW, result["status"])
        self.assertEqual(rules.OUTCOME_ADDITIONAL_COUNT, result["outcome"])
        again = rules.evaluate_count(rules.ITEM_MANAGER_REVIEW, None, 100, 100, policy=rules.POLICY_ALLOW)
        self.assertEqual(rules.ITEM_COUNTED, again["status"])

    def test_rejects_negative_and_non_numeric_counts(self):
        with self.assertRaises(rules.StocktakingRuleError):
            rules.evaluate_count(rules.ITEM_NOT_COUNTED, None, -1, 10)
        with self.assertRaises(rules.StocktakingRuleError):
            rules.validate_count_qty("ten")
        self.assertEqual(0.0, rules.validate_count_qty("0"))

    def test_differences(self):
        self.assertEqual((-5.0, -50.0), rules.differences(95, 100, 10))
        self.assertEqual((0.0, 0.0), rules.differences(None, 100, 10))


class PermissionTests(unittest.TestCase):
    def test_counts_only_while_the_session_is_counting(self):
        for status in (rules.SESSION_DRAFT, rules.SESSION_MANAGER_REVIEW, rules.SESSION_FINAL_APPROVAL, rules.SESSION_COMPLETED):
            with self.assertRaises(rules.StocktakingRuleError):
                rules.can_count(status, rules.MODE_OPEN, rules.ITEM_NOT_COUNTED, None, "u@x", [], ["u@x"])
        rules.can_count(rules.SESSION_COUNTING, rules.MODE_OPEN, rules.ITEM_NOT_COUNTED, None, "u@x", [], ["u@x"])
        rules.can_count(rules.SESSION_RECOUNT, rules.MODE_OPEN, rules.ITEM_RECOUNT_REQUIRED, None, "u@x", [], ["u@x"])

    def test_assigned_mode_locks_rows_to_their_counter(self):
        with self.assertRaises(rules.NotAssignedError) as ctx:
            rules.can_count(rules.SESSION_COUNTING, rules.MODE_ASSIGNED, rules.ITEM_ASSIGNED, "reza@x", "ali@x", [], ["ali@x", "reza@x"])
        self.assertEqual("reza@x", ctx.exception.counter)
        rules.can_count(rules.SESSION_COUNTING, rules.MODE_ASSIGNED, rules.ITEM_ASSIGNED, "ali@x", "ali@x", [], ["ali@x"])
        # unassigned rows may be counted by any registered counter of the session
        rules.can_count(rules.SESSION_COUNTING, rules.MODE_ASSIGNED, rules.ITEM_NOT_COUNTED, None, "ali@x", [], ["ali@x"])
        with self.assertRaises(rules.NotAssignedError):
            rules.can_count(rules.SESSION_COUNTING, rules.MODE_ASSIGNED, rules.ITEM_NOT_COUNTED, None, "sara@x", [], ["ali@x"])
        # supervisors may count anything
        rules.can_count(rules.SESSION_COUNTING, rules.MODE_ASSIGNED, rules.ITEM_ASSIGNED, "reza@x", "boss@x", ["Stock Manager"], ["ali@x"])

    def test_locked_rows_are_reported_before_assignment(self):
        with self.assertRaises(rules.AlreadyCountedError) as ctx:
            rules.can_count(rules.SESSION_COUNTING, rules.MODE_ASSIGNED, rules.ITEM_COUNTED, "reza@x", "ali@x", [], ["ali@x", "reza@x"], counted_by="reza@x")
        self.assertEqual("reza@x", ctx.exception.counted_by)
        self.assertTrue(rules.is_locked(rules.ITEM_APPROVED))
        self.assertFalse(rules.is_locked(rules.ITEM_COUNTED, rules.POLICY_ALLOW))
        self.assertFalse(rules.is_locked(rules.ITEM_RECOUNT_REQUIRED))
        # with additional counts allowed the assignment still applies
        with self.assertRaises(rules.NotAssignedError):
            rules.can_count(rules.SESSION_COUNTING, rules.MODE_ASSIGNED, rules.ITEM_COUNTED, "reza@x", "ali@x", [], ["ali@x"], policy=rules.POLICY_ALLOW)

    def test_open_mode_accepts_any_registered_counter(self):
        rules.can_count(rules.SESSION_COUNTING, rules.MODE_OPEN, rules.ITEM_NOT_COUNTED, None, "ali@x", [], ["ali@x", "reza@x"])
        rules.can_count(rules.SESSION_COUNTING, rules.MODE_OPEN, rules.ITEM_NOT_COUNTED, None, "ali@x", [], [])
        with self.assertRaises(rules.NotAssignedError):
            rules.can_count(rules.SESSION_COUNTING, rules.MODE_OPEN, rules.ITEM_NOT_COUNTED, None, "sara@x", [], ["ali@x"])
        with self.assertRaises(rules.StocktakingRuleError):
            rules.can_count(rules.SESSION_COUNTING, rules.MODE_OPEN, rules.ITEM_FINALIZED, None, "ali@x", [], ["ali@x"])
        with self.assertRaises(rules.StocktakingRuleError):
            rules.can_count(rules.SESSION_COUNTING, rules.MODE_OPEN, rules.ITEM_NOT_COUNTED, None, "Guest", [], [])


class SessionTransitionTests(unittest.TestCase):
    def test_recount_session_returns_to_review_when_the_last_recount_is_in(self):
        self.assertEqual(rules.SESSION_RECOUNT, rules.session_status_after_count(rules.SESSION_RECOUNT, 2))
        self.assertEqual(rules.SESSION_MANAGER_REVIEW, rules.session_status_after_count(rules.SESSION_RECOUNT, 0))
        self.assertEqual(rules.SESSION_COUNTING, rules.session_status_after_count(rules.SESSION_COUNTING, 0))

    def test_recount_requests_move_reviewed_sessions_back_to_recount(self):
        self.assertEqual(rules.SESSION_RECOUNT, rules.session_status_after_recount_request(rules.SESSION_MANAGER_REVIEW))
        self.assertEqual(rules.SESSION_RECOUNT, rules.session_status_after_recount_request(rules.SESSION_FINAL_APPROVAL))
        self.assertEqual(rules.SESSION_COUNTING, rules.session_status_after_recount_request(rules.SESSION_COUNTING))

    def test_complete_counting_needs_every_row_counted(self):
        with self.assertRaises(rules.StocktakingRuleError):
            rules.complete_counting_status({"uncounted_items": 3, "pending_review": 0})
        with self.assertRaises(rules.StocktakingRuleError):
            rules.complete_counting_status({"uncounted_items": 0, "recount_required": 1, "pending_review": 0})
        self.assertEqual(rules.SESSION_MANAGER_REVIEW, rules.complete_counting_status({"uncounted_items": 0, "pending_review": 4}))
        self.assertEqual(rules.SESSION_FINAL_APPROVAL, rules.complete_counting_status({"uncounted_items": 0, "pending_review": 0}))

    def test_approval_requires_everything_reviewed(self):
        rules.ready_for_approval(rules.SESSION_MANAGER_REVIEW, {"uncounted_items": 0, "recount_required": 0, "pending_review": 0})
        with self.assertRaises(rules.StocktakingRuleError) as ctx:
            rules.ready_for_approval(rules.SESSION_MANAGER_REVIEW, {"uncounted_items": 2, "recount_required": 1, "pending_review": 3})
        self.assertIn("2 not counted", str(ctx.exception))
        self.assertIn("3 waiting for manager review", str(ctx.exception))
        with self.assertRaises(rules.StocktakingRuleError):
            rules.ready_for_approval(rules.SESSION_DRAFT, {})

    def test_cancel_rules(self):
        rules.can_cancel(rules.SESSION_COUNTING)
        with self.assertRaises(rules.StocktakingRuleError):
            rules.can_cancel(rules.SESSION_COMPLETED)
        with self.assertRaises(rules.StocktakingRuleError):
            rules.can_cancel(rules.SESSION_RECONCILED, reconciliation_docstatus=1)
        rules.can_cancel(rules.SESSION_RECONCILED, reconciliation_docstatus=0)
        self.assertTrue(rules.can_reconcile(rules.SESSION_FINAL_APPROVAL))
        self.assertFalse(rules.can_reconcile(rules.SESSION_MANAGER_REVIEW))


class AggregationTests(unittest.TestCase):
    rows = [
        {"item_code": "A", "status": "Counted", "erp_qty": 10, "final_qty": 10, "qty_difference": 0, "value_difference": 0},
        {"item_code": "B", "status": "Approved", "erp_qty": 100, "final_qty": 95, "qty_difference": -5, "value_difference": -50},
        {"item_code": "C", "status": "Manager Review", "erp_qty": 200, "final_qty": 205, "qty_difference": 5, "value_difference": 100},
        {"item_code": "D", "status": "Recount Required", "erp_qty": 3},
        {"item_code": "E", "status": "Not Counted", "erp_qty": 7},
        {"item_code": "F", "status": "Assigned", "erp_qty": 7},
    ]

    def test_progress(self):
        totals = rules.progress(self.rows)
        self.assertEqual(6, totals["total_items"])
        self.assertEqual(3, totals["counted_items"])
        self.assertEqual(2, totals["uncounted_items"])
        self.assertEqual(1, totals["matched_items"])
        self.assertEqual(2, totals["variance_items"])
        self.assertEqual(1, totals["recount_required"])
        self.assertEqual(1, totals["pending_review"])
        self.assertEqual(1, totals["approved_items"])
        self.assertEqual(0.0, totals["qty_variance"])
        self.assertEqual(50.0, totals["value_variance"])

    def test_reconciliation_rows_only_carry_reviewed_differences(self):
        rows = [self.rows[0], self.rows[1], {"item_code": "G", "status": "Approved", "erp_qty": 5, "final_qty": 5, "qty_difference": 0}]
        self.assertEqual(["B"], [r["item_code"] for r in rules.reconciliation_rows(rows)])
        with self.assertRaises(rules.StocktakingRuleError):
            rules.reconciliation_rows(self.rows)
        with self.assertRaises(rules.StocktakingRuleError):
            rules.reconciliation_rows([self.rows[4]])


class ScanTests(unittest.TestCase):
    def test_json_labels_and_plain_barcodes(self):
        qr = rules.resolve_scan('{"item_code": "PCT-500", "batch_no": "PCT-250901"}')
        self.assertEqual({"kind": "qr", "item_code": "PCT-500", "batch_no": "PCT-250901", "raw": '{"item_code": "PCT-500", "batch_no": "PCT-250901"}'}, qr)
        self.assertIsNone(rules.resolve_scan('{"item_code": "PCT-500"}')["batch_no"])
        custom = rules.resolve_scan('{"sku": "X", "lot": 5}', item_key="sku", batch_key="lot")
        self.assertEqual(("X", "5"), (custom["item_code"], custom["batch_no"]))
        barcode = rules.resolve_scan(" 8690000000017\x1d ")
        self.assertEqual({"kind": "barcode", "item_code": None, "batch_no": None, "raw": "8690000000017"}, barcode)
        self.assertEqual("barcode", rules.resolve_scan('{"batch_no": "only"}')["kind"])
        self.assertEqual("barcode", rules.resolve_scan("{not json")["kind"])
        with self.assertRaises(rules.StocktakingRuleError):
            rules.resolve_scan("   ")

    def test_match_rows(self):
        rows = [
            {"name": "r1", "item_code": "PCT-500", "batch_no": "B1", "warehouse": "Main - C"},
            {"name": "r2", "item_code": "PCT-500", "batch_no": "B2", "warehouse": "Main - C"},
            {"name": "r3", "item_code": "pct-500", "batch_no": "", "warehouse": "Cold - C"},
            {"name": "r4", "item_code": "OTHER", "batch_no": "B1", "warehouse": "Main - C"},
        ]
        self.assertEqual(["r1", "r2", "r3"], [r["name"] for r in rules.match_rows(rows, "PCT-500")])
        self.assertEqual(["r2"], [r["name"] for r in rules.match_rows(rows, "PCT-500", "b2")])
        self.assertEqual(["r3"], [r["name"] for r in rules.match_rows(rows, "PCT-500", warehouse="cold - c")])
        self.assertEqual([], rules.match_rows(rows, "PCT-500", "B9"))
        self.assertEqual(("A", "W", ""), rules.snapshot_key(" A ", "W", None))


if __name__ == "__main__":
    unittest.main()
