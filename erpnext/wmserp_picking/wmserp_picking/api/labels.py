"""QR label sheets: many Batch / Item labels on one A4 page.

Frappe's own *Actions > Print* renders every selected document on its own page (it merges one PDF
per document), so twenty batches cost twenty sheets of paper. These endpoints render a *sheet*
print format once with all selected documents, and the Print Format decides how many labels fit on
a page and how big they are:

    GET /api/method/wmserp_picking.api.labels.download_label_sheet
        ?doctype=Batch&names=["B-001","B-002"]&print_format=WMS Batch QR Label Sheet&page_size=A4

The Batch and Item list views get an *Actions > Print QR Label Sheet* entry (public/js) that opens
this URL for the selected rows. Templates: templates/print_formats/*_sheet.html, created as editable
Print Formats on install / migrate (see setup/custom_fields.py).
"""

import frappe
from frappe import _
from frappe.utils.pdf import get_pdf

from wmserp_picking.labels import rules
from wmserp_picking.qr import wms_qr_keys

PRINT_FORMAT = "Print Format"


@frappe.whitelist()
def get_label_sheet_formats(doctype):
    """Sheet print formats available for `doctype` (the shipped one plus any copy the site made),
    for the selector of the list action."""
    ensure_supported(doctype)
    default = rules.default_sheet_format(doctype)
    formats = frappe.get_all(
        PRINT_FORMAT,
        filters={"doc_type": doctype, "disabled": 0},
        fields=["name", "html", "modified"],
        order_by="name asc",
    )
    out = [{"name": f.name, "default": f.name == default} for f in formats if rules.is_sheet_template(f.html)]
    if not any(f["default"] for f in out) and frappe.db.exists(PRINT_FORMAT, default):
        out.insert(0, {"name": default, "default": True})
    return out


@frappe.whitelist()
def label_sheet_html(doctype, names, print_format=None):
    """The rendered sheet as HTML (what the PDF is made of), useful to preview a layout change."""
    docs, fmt = load(doctype, names, print_format)
    return render(doctype, docs, fmt)


@frappe.whitelist()
def download_label_sheet(doctype, names, print_format=None, page_size=None):
    """One PDF with every selected document as a label, several labels per page."""
    docs, fmt = load(doctype, names, print_format)
    html = render(doctype, docs, fmt)
    # Margins (and an explicit page size) in the template's `.print-format` rule win over these.
    options = {"page-size": rules.page_size(page_size)}
    frappe.local.response.filename = rules.file_name(doctype, len(docs))
    frappe.local.response.filecontent = get_pdf(html, options=options)
    frappe.local.response.type = "pdf"


# --------------------------------------------------------------------------------------------
# Internals
# --------------------------------------------------------------------------------------------


def ensure_supported(doctype):
    if doctype not in rules.SUPPORTED_DOCTYPES:
        frappe.throw(_("QR label sheets are not available for {0}.").format(_(doctype or "")))


def load(doctype, names, print_format):
    ensure_supported(doctype)
    try:
        names = rules.parse_names(names)
    except rules.LabelRuleError as exc:
        frappe.throw(_(str(exc)))
    fmt = sheet_format(doctype, print_format)
    docs = []
    for name in names:
        doc = frappe.get_doc(doctype, name)
        validate_print_permission(doc)
        docs.append(doc.as_dict())
    return docs, fmt


def sheet_format(doctype, name=None):
    """The requested (or the shipped) sheet Print Format of `doctype`, created if a site lost it."""
    try:
        default = rules.default_sheet_format(doctype)
    except rules.LabelRuleError as exc:
        frappe.throw(_(str(exc)))
    name = (name or "").strip() or default
    if not frappe.db.exists(PRINT_FORMAT, name):
        if name != default:
            frappe.throw(_("Print Format {0} does not exist.").format(name), frappe.DoesNotExistError)
        from wmserp_picking.setup.custom_fields import ensure_label_print_formats

        ensure_label_print_formats()
    fmt = frappe.get_doc(PRINT_FORMAT, name)
    if fmt.doc_type != doctype:
        frappe.throw(_("Print Format {0} belongs to {1}, not {2}.").format(name, _(fmt.doc_type), _(doctype)))
    if fmt.disabled:
        frappe.throw(_("Print Format {0} is disabled.").format(name))
    if not rules.is_sheet_template(fmt.html):
        frappe.throw(
            _("Print Format {0} prints one document per page. Choose a label sheet format (it contains the '{1}' marker).").format(
                name, rules.SHEET_MARKER
            )
        )
    return fmt


def render(doctype, docs, fmt) -> str:
    context = {
        "docs": docs,
        "doctype": doctype,
        "count": len(docs),
        "print_format": fmt.as_dict(),
        "qr_keys": wms_qr_keys(),
    }
    body = frappe.render_template(fmt.html, context)
    return rules.wrap_page(body, css=fmt.css or "", title=f"{_(doctype)} {_('QR labels')}")


def validate_print_permission(doc):
    try:
        from frappe.www.printview import validate_print_permission as frappe_validate
    except ImportError:  # pragma: no cover - very old frappe
        doc.check_permission("read")
        return
    frappe_validate(doc)
