"""Fills the mandatory fields of documents the API creates or submits for a warehouse user.

Order of sources for an empty `reqd` field (header and every child row):
1. the values the app sends (`values`, keyed `"<Doctype>.<fieldname>"` or by fieldname), which it
   asked the user for and remembers on the device;
2. the same-named field of the source documents (the Sales Order / Material Request behind a pick
   list row, the Pick List itself);
3. the user's defaults (`frappe.defaults`), then the company's default accounting dimensions.

What is still empty is reported as `missing_fields` so the app can ask once and call again; the
document is never inserted half-filled. `frappe.MandatoryError`s raised anyway (fields made
mandatory by a Client / Server Script, for instance) are turned into the same answer.
"""

import frappe
from frappe import _

from wmserp_picking import required_fields as rules


class MissingRequiredFields(Exception):
    """Raised before insert/save when answerable required fields are still empty."""

    def __init__(self, doctype, fields):
        super().__init__(_("{0} needs: {1}").format(_(doctype), ", ".join(f["label"] for f in fields)))
        self.doctype = doctype
        self.fields = fields

    def response(self) -> dict:
        return rules.missing_response(self.doctype, self.fields)


def answerable_fields(doctype) -> list:
    """Required fields of `doctype` a person can answer (custom fields and property setters
    included, meta defaults excluded)."""
    meta = frappe.get_meta(doctype)
    return [df for df in meta.get("fields", {"reqd": 1}) if rules.is_answerable(df)]


def fields_by_doctype(doctype) -> dict:
    """{doctype: [df...]} for the header and every child table (for error → field mapping)."""
    out = {doctype: list(frappe.get_meta(doctype).get("fields", {"reqd": 1}))}
    for table in frappe.get_meta(doctype).get_table_fields():
        child = table.options
        if child and child not in out:
            out[child] = list(frappe.get_meta(child).get("fields", {"reqd": 1}))
    return out


def fill_required(doc, values=None, sources=(), company=None) -> list:
    """Fills empty answerable required fields of `doc` and its rows; returns the descriptors of
    the fields that are still empty (empty list when the document is complete)."""
    values = values or {}
    company = company or doc.get("company")
    dimension_defaults = default_dimensions(company)
    missing, seen = [], set()

    def fill(target, doctype, row_sources):
        for df in answerable_fields(doctype):
            if target.get(df.fieldname) not in (None, ""):
                continue
            value = rules.resolve_value(doctype, df.fieldname, values)
            if value in (None, ""):
                value = value_from_sources(df.fieldname, row_sources)
            if value in (None, ""):
                value = frappe.defaults.get_user_default(df.fieldname)
            if value in (None, ""):
                value = dimension_defaults.get(df.fieldname)
            if value in (None, ""):
                key = (doctype, df.fieldname)
                if key not in seen:
                    seen.add(key)
                    missing.append(rules.describe(df, doctype))
                continue
            target.set(df.fieldname, value)

    fill(doc, doc.doctype, list(sources))
    for table in doc.meta.get_table_fields():
        for row in doc.get(table.fieldname) or []:
            fill(row, row.doctype, list(sources) + [doc])
    return missing


def value_from_sources(fieldname, sources):
    for source in sources:
        if source is None:
            continue
        value = source.get(fieldname) if hasattr(source, "get") else getattr(source, fieldname, None)
        if value not in (None, ""):
            return value
    return None


def default_dimensions(company) -> dict:
    """`{fieldname: default}` of the company's accounting dimensions (empty when none / no ERPNext)."""
    if not company:
        return {}
    try:
        from erpnext.accounts.doctype.accounting_dimension.accounting_dimension import get_dimensions

        _filters, defaults = get_dimensions(with_cost_center_and_project=True)
    except Exception:
        return {}
    return {k: v for k, v in (defaults.get(company) or {}).items() if v not in (None, "")}


def missing_from_exception(doctype, exc) -> list:
    """The fields behind a `frappe.MandatoryError` (or any validation error whose messages name
    missing values), as descriptors; empty when the error is about something else."""
    meta = fields_by_doctype(doctype)
    fieldnames = rules.fieldnames_from_mandatory_error(str(exc))
    found = rules.fields_by_name(meta, fieldnames) if fieldnames else []
    if not found:
        messages = [m.get("message") if isinstance(m, dict) else m for m in frappe.get_message_log()]
        found = rules.fields_by_label(meta, rules.labels_from_messages(messages))
    return [f for f in found if f["fieldtype"] in rules.ANSWERABLE_FIELDTYPES]


def missing_fields_answer(doctype, exc):
    """Rolls back and returns the `missing_fields` answer for `exc`, or None when the error is not
    about mandatory fields (the caller re-raises then)."""
    missing = missing_from_exception(doctype, exc)
    if not missing:
        return None
    frappe.db.rollback()
    frappe.clear_messages()
    return rules.missing_response(doctype, missing)
