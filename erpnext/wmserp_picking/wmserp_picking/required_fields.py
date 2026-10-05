"""Pure helpers around mandatory fields of generated documents (no Frappe imports).

A site adds its own mandatory fields to standard documents (a *Department* on Delivery Note rows,
a *Cost Center* on the Purchase Receipt header...). Documents the app creates on behalf of a
warehouse user (the Delivery Note / Stock Entry of a picked card, the submitted Purchase Receipt)
cannot know those values, so the API fills what it can (the source order, user and company
defaults, the values the app already asked for) and reports the rest as `missing_fields` for the
app to ask once, instead of ERPNext rejecting the document with *Value missing for: Department*.

This module parses Frappe's error texts and resolves answers; the Frappe-aware part lives in
`wmserp_picking.documents`.
"""

from __future__ import annotations

import re

# Field types a person can answer in a dialog; tables, breaks, attachments... cannot be asked for.
ANSWERABLE_FIELDTYPES = (
    "Link",
    "Dynamic Link",
    "Select",
    "Data",
    "Small Text",
    "Text",
    "Long Text",
    "Int",
    "Float",
    "Currency",
    "Percent",
    "Date",
    "Datetime",
    "Time",
)

# frappe.MandatoryError: "[Delivery Note, new-delivery-note-1]: department, cost_center"
_MANDATORY_ERROR = re.compile(r"^\s*\[[^\]]*\]:\s*(?P<fields>.+?)\s*$", re.S)

# msgprint texts of _validate_mandatory (Frappe v13-v16), with or without translation markup:
#   "Error: Value missing for Delivery Note: Department"
#   "Error: <b>Delivery Note Item</b> Row #1: Value missing for: Department"
#   "Value missing for: Department" / "Department is mandatory" / "Missing Fields: Department"
_VALUE_MISSING = re.compile(r"Value missing for(?:\s+[^:]+)?:\s*(?P<label>[^<\n]+)", re.I)
_IS_MANDATORY = re.compile(r"(?P<label>[^<\n:]+?)\s+is (?:mandatory|required)\b", re.I)
_MISSING_FIELDS = re.compile(r"Missing Fields?:\s*(?P<labels>[^<\n]+)", re.I)
_TAGS = re.compile(r"<[^>]+>")


def is_truthy(value) -> bool:
    return str(value).strip().lower() in ("1", "true", "yes")


def is_answerable(df) -> bool:
    """A `reqd` field a dialog can ask for; fields with a meta default are filled by Frappe itself."""
    if not is_truthy(_get(df, "reqd")):
        return False
    if _get(df, "fieldtype") not in ANSWERABLE_FIELDTYPES:
        return False
    if _get(df, "default") not in (None, ""):
        return False
    return bool(_get(df, "fieldname"))


def describe(df, doctype) -> dict:
    """The shape the app expects for one missing field."""
    options = _get(df, "options")
    return {
        "doctype": doctype,
        "fieldname": _get(df, "fieldname"),
        "label": _get(df, "label") or _get(df, "fieldname"),
        "fieldtype": _get(df, "fieldtype"),
        "options": options if options not in (None, "") else None,
    }


def resolve_value(doctype, fieldname, values):
    """An answer for `fieldname`: `values` may be keyed `"<Doctype>.<fieldname>"` (the app's keys)
    or plainly by fieldname; blanks count as no answer."""
    if not values:
        return None
    for key in (f"{doctype}.{fieldname}", fieldname):
        value = values.get(key)
        if value not in (None, ""):
            return value
    return None


def fieldnames_from_mandatory_error(message) -> list[str]:
    """Fieldnames named by a `frappe.MandatoryError` ("[Doctype, name]: a, b")."""
    match = _MANDATORY_ERROR.match(_strip(message))
    if not match:
        return []
    return _dedupe(part.strip() for part in match.group("fields").split(",") if part.strip())


def labels_from_messages(messages) -> list[str]:
    """Field labels named by validation messages (`_server_messages` / msgprint texts)."""
    labels = []
    for message in messages or []:
        text = _strip(message)
        if not text:
            continue
        found = False
        for match in _VALUE_MISSING.finditer(text):
            labels.append(match.group("label").strip())
            found = True
        for match in _MISSING_FIELDS.finditer(text):
            labels.extend(part.strip() for part in match.group("labels").split(",") if part.strip())
            found = True
        if not found:
            for match in _IS_MANDATORY.finditer(text):
                labels.append(match.group("label").strip().strip("'\""))
    return _dedupe(label for label in labels if label)


def fields_by_label(fields_by_doctype, labels) -> list[dict]:
    """Maps labels back to field descriptors: `fields_by_doctype` is {doctype: [df, ...]} (header
    first, then child tables); the first field with that label wins."""
    out, seen = [], set()
    wanted = [label.lower() for label in labels]
    for doctype, fields in fields_by_doctype.items():
        for df in fields:
            label = (_get(df, "label") or _get(df, "fieldname") or "").lower()
            fieldname = _get(df, "fieldname")
            if label in wanted and (doctype, fieldname) not in seen:
                seen.add((doctype, fieldname))
                out.append(describe(df, doctype))
    return out


def fields_by_name(fields_by_doctype, fieldnames) -> list[dict]:
    out, seen = [], set()
    for doctype, fields in fields_by_doctype.items():
        for df in fields:
            fieldname = _get(df, "fieldname")
            if fieldname in fieldnames and (doctype, fieldname) not in seen:
                seen.add((doctype, fieldname))
                out.append(describe(df, doctype))
    return out


def missing_response(doctype, fields) -> dict:
    """What the API returns (HTTP 200) when the document cannot be created yet."""
    return {"doctype": doctype, "missing_fields": list(fields), "created": False}


def _get(df, key):
    if isinstance(df, dict):
        return df.get(key)
    return getattr(df, key, None)


def _strip(text) -> str:
    return _TAGS.sub("", str(text or "")).strip()


def _dedupe(values) -> list:
    out = []
    for value in values:
        if value not in out:
            out.append(value)
    return out
