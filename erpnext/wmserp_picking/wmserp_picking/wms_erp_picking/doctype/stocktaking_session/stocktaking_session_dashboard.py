from frappe import _


def get_data():
    return {
        "fieldname": "session",
        "non_standard_fieldnames": {"Stock Reconciliation": "custom_stocktaking_session"},
        "transactions": [
            {"label": _("Counting"), "items": ["Stocktaking Item", "Stocktaking Count"]},
            {"label": _("Stock"), "items": ["Stock Reconciliation"]},
        ],
    }
