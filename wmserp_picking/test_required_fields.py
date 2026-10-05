"""Unit tests for the mandatory-field helpers (no bench needed)."""

import unittest

from wmserp_picking import required_fields as rf


def df(fieldname, label=None, fieldtype="Link", reqd=1, default=None, options=None):
    return {"fieldname": fieldname, "label": label or fieldname.title(), "fieldtype": fieldtype, "reqd": reqd, "default": default, "options": options}


class AnswerableTests(unittest.TestCase):
    def test_only_required_answerable_fields_without_defaults(self):
        self.assertTrue(rf.is_answerable(df("department")))
        self.assertTrue(rf.is_answerable(df("remarks", fieldtype="Small Text")))
        self.assertFalse(rf.is_answerable(df("items", fieldtype="Table")))
        self.assertFalse(rf.is_answerable(df("posting_date", fieldtype="Date", default="Today")))
        self.assertFalse(rf.is_answerable(df("customer", reqd=0)))
        self.assertFalse(rf.is_answerable(df("x", reqd="0")))
        self.assertTrue(rf.is_answerable(df("x", reqd="1")))

    def test_describe_and_resolve(self):
        self.assertEqual(
            {"doctype": "Delivery Note Item", "fieldname": "department", "label": "Department", "fieldtype": "Link", "options": "Department"},
            rf.describe(df("department", options="Department"), "Delivery Note Item"),
        )
        values = {"Delivery Note Item.department": "Warehouse - WM", "cost_center": "Main - WM", "project": ""}
        self.assertEqual("Warehouse - WM", rf.resolve_value("Delivery Note Item", "department", values))
        self.assertEqual("Main - WM", rf.resolve_value("Delivery Note", "cost_center", values))
        self.assertIsNone(rf.resolve_value("Delivery Note", "project", values))
        self.assertIsNone(rf.resolve_value("Delivery Note", "department", None))


class ErrorParsingTests(unittest.TestCase):
    def test_mandatory_error_lists_fieldnames(self):
        self.assertEqual(["department", "cost_center"], rf.fieldnames_from_mandatory_error("[Delivery Note, new-delivery-note-1]: department, cost_center"))
        self.assertEqual(["department"], rf.fieldnames_from_mandatory_error("[Stock Entry Detail, None]: department, department"))
        self.assertEqual([], rf.fieldnames_from_mandatory_error("Something else"))
        self.assertEqual([], rf.fieldnames_from_mandatory_error(None))

    def test_labels_from_frappe_messages(self):
        messages = [
            "Error: Value missing for Delivery Note: Cost Center",
            "Error: <b>Delivery Note Item</b> Row #1: Value missing for: Department",
            "Error: <b>Delivery Note Item</b> Row #2: Value missing for: Department",
            "Department is mandatory",
            "Missing Fields: Project, Branch",
            "Please set a Department",  # not a mandatory message
        ]
        self.assertEqual(["Cost Center", "Department", "Project", "Branch"], rf.labels_from_messages(messages))
        self.assertEqual([], rf.labels_from_messages([]))

    def test_fields_by_label_and_name_prefer_the_first_match(self):
        meta = {
            "Delivery Note": [df("cost_center", "Cost Center", options="Cost Center"), df("department", "Department", options="Department")],
            "Delivery Note Item": [df("department", "Department", options="Department")],
        }
        by_label = rf.fields_by_label(meta, ["department", "Cost Center", "Nothing"])
        self.assertEqual([("Delivery Note", "cost_center"), ("Delivery Note", "department"), ("Delivery Note Item", "department")], [(f["doctype"], f["fieldname"]) for f in by_label])
        by_name = rf.fields_by_name(meta, ["department"])
        self.assertEqual([("Delivery Note", "department"), ("Delivery Note Item", "department")], [(f["doctype"], f["fieldname"]) for f in by_name])

    def test_missing_response_shape(self):
        response = rf.missing_response("Delivery Note", [rf.describe(df("department"), "Delivery Note Item")])
        self.assertFalse(response["created"])
        self.assertEqual("Delivery Note", response["doctype"])
        self.assertEqual("department", response["missing_fields"][0]["fieldname"])


if __name__ == "__main__":
    unittest.main()
