import frappe
from frappe import _
from frappe.model.document import Document

from wmserp_picking.picking import rules


class WMSSettings(Document):
    def validate(self):
        self.qr_item_key = (self.qr_item_key or rules.DEFAULT_QR_ITEM_KEY).strip()
        self.qr_batch_key = (self.qr_batch_key or rules.DEFAULT_QR_BATCH_KEY).strip()
        if self.qr_item_key == self.qr_batch_key:
            frappe.throw(_("QR Item Key and QR Batch Key must be different."))
        for key in (self.qr_item_key, self.qr_batch_key):
            if any(char in key for char in '"\\{}'):
                frappe.throw(_("QR key {0} contains characters that are not valid in a JSON key.").format(key))


def get_qr_keys() -> dict:
    """The JSON keys the app and the label print format use; defaults when the single is untouched."""
    item_key, batch_key = rules.DEFAULT_QR_ITEM_KEY, rules.DEFAULT_QR_BATCH_KEY
    try:
        settings = frappe.get_cached_doc("WMS Settings", "WMS Settings")
        item_key = (settings.get("qr_item_key") or "").strip() or item_key
        batch_key = (settings.get("qr_batch_key") or "").strip() or batch_key
    except Exception:
        # DocType not synced yet (fresh install) or no permission: fall back to the defaults.
        pass
    return {"qr_item_key": item_key, "qr_batch_key": batch_key}
