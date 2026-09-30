import frappe
from frappe import _
from frappe.model.document import Document
from frappe.utils import flt

from wmserp_picking.stocktaking import rules


class StocktakingCount(Document):
    """One physical count. The audit trail is append-only: counts are never edited or deleted
    (only a System Manager can delete, e.g. to clean a test site)."""

    def validate(self):
        if self.count_type not in rules.COUNT_TYPES:
            frappe.throw(_("Count Type must be one of {0}.").format(", ".join(rules.COUNT_TYPES)))
        if flt(self.qty) < 0:
            frappe.throw(_("A physical count cannot be negative."))
        if not self.is_new():
            frappe.throw(_("Stocktaking counts cannot be edited: record another count instead."))
        self.difference = flt(self.qty) - flt(self.erp_qty)
