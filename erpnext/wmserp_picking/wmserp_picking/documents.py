"""Fills the mandatory fields of documents the API creates or submits for a warehouse user.

Order of sources for an empty `reqd` field (header and every child row):
1. the values the app sends (`values`, keyed `"<Doctype>.<fieldname>"` or by fieldname), which it
   asked the user for and remembers on the device;
2. the same-named field of the source documents (the Sales Order / Material Request behind a pick
   list row, the Pick List itself), except `no_copy` / `set_only_once` fields and the fields Frappe
   names itself (`naming_series`...), which are never taken from a source nor asked for;
3. the user's defaults (`frappe.defaults`), then the company's default accounting dimensions.

What is still empty is reported as `missing_fields` so the app can ask once and call again; the
document is never inserted half-filled. `frappe.MandatoryError`s raised anyway (fields made
mandatory by a Client / Server Script, for instance) are turned into the same answer.

The second half stamps what the warehouse owns on every document it processes: the warehouse
department on each row (*WMS Settings > Warehouse Department*), the employee processing the
document in the site's *Warehouse Document Processing Officer* field (whose signature the printed
document carries) and the posting date and time of the moment it was processed.
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
            if value in (None, "") and rules.copies_from_sources(df):
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
    """The first non-empty `fieldname` of `sources` (documents or plain dicts/rows)."""
    for source in sources:
        if source is None:
            continue
        value = source.get(fieldname) if hasattr(source, "get") else getattr(source, fieldname, None)
        if value not in (None, ""):
            return value
    return None


def source_values(doctype, name):
    """A source document as one row read (`frappe.db.get_value(..., "*")`): `fill_required` only
    needs header fields, so the child tables of a full `get_doc` are never loaded."""
    if not name:
        return None
    return frappe.db.get_value(doctype, name, "*", as_dict=True)


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


# --------------------------------------------------------------------------------------------
# Values the warehouse stamps on every document it processes
# --------------------------------------------------------------------------------------------

OFFICER_LABELS = ("warehouse document processing officer", "document processing officer")


def wms_setting(fieldname):
    try:
        return frappe.get_cached_doc("WMS Settings", "WMS Settings").get(fieldname)
    except Exception:
        return None


def warehouse_department():
    """*WMS Settings > Warehouse Department*: the department written on every row (and on the
    header, when it has the field) of documents the app confirms or creates."""
    return (wms_setting("warehouse_department") or "").strip() or None


def apply_department(doc, department=None):
    """Writes the warehouse department to `department` of the header (only when empty) and of
    every row (always: site rules require the warehouse's own department on each row). No-op
    without a configured department or without such fields."""
    department = department or warehouse_department()
    if not department:
        return
    if doc.meta.has_field("department") and not doc.get("department"):
        doc.set("department", department)
    for table in doc.meta.get_table_fields():
        rows = doc.get(table.fieldname) or []
        if rows and frappe.get_meta(table.options).has_field("department"):
            for row in rows:
                row.set("department", department)


def officer_field(doctype):
    """The field of `doctype` that names the warehouse employee who processed the document:
    *WMS Settings > Officer Field* when `doctype` has it, else a field labelled *Warehouse Document
    Processing Officer*. None when the DocType keeps no such field."""
    meta = frappe.get_meta(doctype)
    configured = (wms_setting("officer_field") or "").strip()
    if configured:
        return meta.get_field(configured)
    for df in meta.fields:
        if (df.label or "").strip().lower() in OFFICER_LABELS:
            return df
    return None


def employee_of(user=None):
    """`{name, employee_name}` of the active Employee linked to `user` (the session user by
    default), or None."""
    user = user or frappe.session.user
    employee = frappe.db.get_value("Employee", {"user_id": user, "status": "Active"}, ["name", "employee_name"], as_dict=True)
    if not employee:
        employee = frappe.db.get_value("Employee", {"user_id": user}, ["name", "employee_name"], as_dict=True)
    return employee


def officer_value(df, user=None):
    """What goes into the officer field: the Employee for a link to Employee, the user for a link
    to User, the employee's (else the user's) full name for a text field."""
    user = user or frappe.session.user
    employee = employee_of(user)
    if df.fieldtype in ("Link", "Dynamic Link"):
        if df.options == "Employee":
            return employee.name if employee else None
        if df.options == "User":
            return user
        return None
    if employee and employee.employee_name:
        return employee.employee_name
    return frappe.utils.get_fullname(user)


def stamp_officer(doc, user=None):
    """Names the warehouse employee processing `doc` in its officer field (see `officer_field`):
    the person whose signature the printed document carries. Returns the value written, or None
    when the DocType has no such field or the user is no employee the field can hold."""
    df = officer_field(doc.doctype)
    if df is None:
        return None
    value = officer_value(df, user)
    if value in (None, ""):
        return None
    doc.set(df.fieldname, value)
    return value


def stamp_posting_now(doc):
    """Dates the document at the moment the warehouse processes it: posting date and time are
    set to now (with *Edit Posting Date and Time* on, so ERPNext keeps them), the way a receipt
    confirmed today must not carry the date purchasing drafted it."""
    from frappe.utils import nowdate, nowtime

    if doc.meta.has_field("posting_date"):
        doc.set("posting_date", nowdate())
    if doc.meta.has_field("posting_time"):
        doc.set("posting_time", nowtime())
    if doc.meta.has_field("set_posting_time"):
        doc.set("set_posting_time", 1)
