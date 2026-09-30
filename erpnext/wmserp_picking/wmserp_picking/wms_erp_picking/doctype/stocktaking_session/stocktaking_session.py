import frappe
from frappe import _
from frappe.model.document import Document

from wmserp_picking.stocktaking import rules

ITEM = "Stocktaking Item"
COUNT = "Stocktaking Count"


class StocktakingSession(Document):
    def validate(self):
        if not self.status:
            self.status = rules.SESSION_DRAFT
        if self.counting_mode not in rules.COUNTING_MODES:
            frappe.throw(_("Counting Mode must be Assigned or Open."))
        if self.duplicate_count_policy not in rules.DUPLICATE_POLICIES:
            self.duplicate_count_policy = rules.POLICY_LOCK
        if (self.qty_tolerance or 0) < 0:
            frappe.throw(_("Quantity Tolerance cannot be negative."))
        company = frappe.db.get_value("Warehouse", self.warehouse, "company")
        if company and self.company and company != self.company:
            frappe.throw(_("Warehouse {0} belongs to company {1}.").format(self.warehouse, company))
        self.dedupe_counters()
        if self.status != rules.SESSION_DRAFT and self.has_value_changed("warehouse"):
            frappe.throw(_("The warehouse cannot be changed once the session has started."))
        if self.status != rules.SESSION_DRAFT and self.has_value_changed("counting_mode"):
            frappe.throw(_("The counting mode cannot be changed once the session has started."))

    def dedupe_counters(self):
        seen = set()
        for row in list(self.get("counters") or []):
            if not row.user or row.user in seen:
                self.remove(row)
                continue
            seen.add(row.user)
        for idx, row in enumerate(self.get("counters") or [], start=1):
            row.idx = idx

    def on_trash(self):
        if self.status not in (rules.SESSION_DRAFT, rules.SESSION_CANCELLED):
            frappe.throw(_("Only Draft or Cancelled sessions can be deleted; cancel the session first."))
        if self.stock_reconciliation and frappe.db.get_value("Stock Reconciliation", self.stock_reconciliation, "docstatus") == 1:
            frappe.throw(_("Stock Reconciliation {0} was created from this session; it cannot be deleted.").format(self.stock_reconciliation))
        frappe.db.delete(COUNT, {"session": self.name})
        frappe.db.delete(ITEM, {"session": self.name})

    # ---- helpers used by the service --------------------------------------------------------

    @property
    def counter_users(self) -> list:
        return [row.user for row in (self.get("counters") or []) if row.user]

    def is_frozen_now(self) -> bool:
        return bool(self.frozen) and self.status in rules.FROZEN_SESSION_STATUSES

    def totals(self) -> dict:
        """Aggregates the rows of this session in one query."""
        rows = frappe.get_all(
            ITEM,
            filters={"session": self.name},
            fields=["status", "qty_difference", "value_difference"],
            limit_page_length=0,
        )
        return rules.progress(rows)

    def refresh_totals(self, extra=None) -> dict:
        totals = self.totals()
        updates = dict(totals)
        if extra:
            updates.update(extra)
        self.db_set(updates, update_modified=True)
        return totals

    def add_counter(self, user, area=None):
        if user in self.counter_users:
            return
        row = self.append("counters", {"user": user, "area": area})
        row.full_name = frappe.db.get_value("User", user, "full_name")
        row.db_insert()
        frappe.db.set_value(self.doctype, self.name, "modified", frappe.utils.now_datetime(), update_modified=False)
