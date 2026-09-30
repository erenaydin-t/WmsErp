import frappe
from frappe import _
from frappe.model.document import Document
from frappe.utils import flt

from wmserp_picking.stocktaking import rules


class StocktakingItem(Document):
    def validate(self):
        if not self.status:
            self.status = rules.ITEM_ASSIGNED if self.counter else rules.ITEM_NOT_COUNTED
        if not self.next_count_type:
            self.next_count_type = rules.COUNT_1
        if self.batch_no and not self.has_batch_no:
            self.has_batch_no = 1
        self.validate_unique()
        # Assigning / unassigning a row in the desk keeps the status in step.
        if self.status in (rules.ITEM_NOT_COUNTED, rules.ITEM_ASSIGNED):
            self.status = rules.ITEM_ASSIGNED if self.counter else rules.ITEM_NOT_COUNTED
        self.qty_difference, self.value_difference = rules.differences(self.final_qty, self.erp_qty, self.valuation_rate)

    def validate_unique(self):
        filters = {
            "session": self.session,
            "item_code": self.item_code,
            "warehouse": self.warehouse,
            "batch_no": self.batch_no or ["is", "not set"],
            "name": ["!=", self.name or ""],
        }
        if frappe.get_all("Stocktaking Item", filters=filters, pluck="name", limit_page_length=1):
            frappe.throw(
                _("{0} / {1} / {2} is already part of session {3}.").format(self.item_code, self.warehouse, self.batch_no or _("no batch"), self.session)
            )

    def on_trash(self):
        status = frappe.db.get_value("Stocktaking Session", self.session, "status")
        if status not in (rules.SESSION_DRAFT, rules.SESSION_CANCELLED) and self.status not in rules.UNCOUNTED_STATUSES:
            frappe.throw(_("Counted rows cannot be deleted while the session is {0}.").format(status))
        frappe.db.delete("Stocktaking Count", {"stocktaking_item": self.name})

    @property
    def difference(self) -> float:
        return flt(self.final_qty) - flt(self.erp_qty) if self.final_qty not in (None, "") else 0.0
