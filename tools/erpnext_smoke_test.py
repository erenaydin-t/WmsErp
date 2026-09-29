#!/usr/bin/env python3
"""Replays every ERPNext request the WMS ERP Android app makes against a real site and reports
what fails, so server-side problems (permissions, missing fields, missing custom app, report
filters) can be found without a device.

Only the Python standard library is used. Run it from any machine that can reach the site:

    python3 tools/erpnext_smoke_test.py --url https://erp.example.com --key API_KEY --secret API_SECRET

    # or with environment variables
    ERP_URL=https://erp.example.com ERP_KEY=... ERP_SECRET=... python3 tools/erpnext_smoke_test.py

Options:
    --barcode 8690000000017     also test the barcode lookup used by the Scan screen
    --pick-list STO-PICK-00001  run the row-level picking workflow (start_row / save_row_progress /
                                complete_row / generate_document) on the rows of this submitted Pick List
                                that are assigned to the API user. THIS WRITES DATA; use a test site.
    --json                      print the raw JSON of every response (verbose)

Read checks never modify anything (the make_purchase_receipt / make_delivery_note calls only return the
mapped draft the app edits; nothing is inserted). Exit code is 1 when at least one check fails.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

# Mirrors app/src/main/java/com/wmserp/app/data/repository/*.kt
PO_OPEN_STATUSES = ["To Receive and Bill", "To Receive"]
SO_OPEN_STATUSES = ["To Deliver and Bill", "To Deliver"]
PO_LIST_FIELDS = ["name", "supplier", "supplier_name", "status", "transaction_date", "schedule_date", "grand_total", "currency", "per_received", "set_warehouse", "company", "docstatus"]
SO_LIST_FIELDS = ["name", "customer", "customer_name", "status", "transaction_date", "delivery_date", "grand_total", "currency", "per_delivered", "set_warehouse", "company", "docstatus"]
DN_LIST_FIELDS = ["name", "customer", "customer_name", "status", "posting_date", "docstatus", "grand_total", "currency"]
ITEM_LIST_FIELDS = ["name", "item_code", "item_name", "item_group", "stock_uom", "description", "image", "disabled", "is_stock_item", "valuation_rate", "standard_rate", "brand"]
BIN_FIELDS = ["name", "item_code", "warehouse", "actual_qty", "reserved_qty", "ordered_qty", "projected_qty", "stock_uom"]
WAREHOUSE_FIELDS = ["name", "warehouse_name", "company", "is_group", "parent_warehouse", "disabled", "warehouse_type", "city"]
SLE_FIELDS = ["name", "item_code", "warehouse", "actual_qty", "voucher_type", "voucher_no", "posting_date", "posting_time"]
PICKING_METHOD = "wmserp_picking.api.pick_list."
GET_DOCTYPE = "frappe.desk.form.load.getdoctype"
GET_DIMENSIONS = "erpnext.accounts.doctype.accounting_dimension.accounting_dimension.get_dimensions"
ANSWERABLE_FIELDTYPES = {"Link", "Dynamic Link", "Select", "Data", "Small Text", "Text", "Long Text", "Int", "Float", "Currency", "Percent", "Date", "Datetime", "Time"}
MAKE_PURCHASE_RECEIPT = "erpnext.buying.doctype.purchase_order.purchase_order.make_purchase_receipt"
MAKE_DELIVERY_NOTE = "erpnext.selling.doctype.sales_order.sales_order.make_delivery_note"
GET_BATCH_QTY = "erpnext.stock.doctype.batch.batch.get_batch_qty"


class Client:
    def __init__(self, url: str, key: str, secret: str, verbose: bool = False):
        self.base = url.rstrip("/")
        self.headers = {
            "Authorization": f"token {key}:{secret}",
            "Accept": "application/json",
            "User-Agent": "WmsErp-smoke-test/1.1",
        }
        self.verbose = verbose

    def request(self, method: str, path: str, params: dict | None = None, body: dict | None = None):
        url = f"{self.base}/{path.lstrip('/')}"
        if params:
            url += "?" + urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
        data = None
        headers = dict(self.headers)
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=60) as response:
                raw = response.read().decode()
                status = response.status
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode(errors="replace")
            status = exc.code
        except (urllib.error.URLError, OSError) as exc:
            return 0, None, f"connection error: {exc}"
        try:
            payload = json.loads(raw) if raw else {}
        except ValueError:
            payload = None
        if self.verbose:
            print(f"    {method} {url}\n    -> {status} {raw[:600]}")
        return status, payload, raw

    # -- the exact call shapes used by the app --------------------------------------------

    def get_list(self, doctype, fields, filters=None, or_filters=None, order_by=None, limit=20):
        params = {"fields": json.dumps(fields), "limit_page_length": limit}
        if filters:
            params["filters"] = json.dumps(filters)
        if or_filters:
            params["or_filters"] = json.dumps(or_filters)
        if order_by:
            params["order_by"] = order_by
        return self.request("GET", f"api/resource/{urllib.parse.quote(doctype)}", params)

    def get_doc(self, doctype, name):
        return self.request("GET", f"api/resource/{urllib.parse.quote(doctype)}/{urllib.parse.quote(name, safe='')}")

    def get_count(self, doctype, filters):
        return self.request("GET", "api/method/frappe.client.get_count", {"doctype": doctype, "filters": json.dumps(filters)})

    def get_single_value(self, doctype, field):
        return self.request("GET", "api/method/frappe.client.get_single_value", {"doctype": doctype, "field": field})

    def run_report(self, report_name, filters):
        return self.request("GET", "api/method/frappe.desk.query_report.run", {"report_name": report_name, "filters": json.dumps(filters), "ignore_prepared_report": "1"})

    def call(self, method, params=None):
        return self.request("GET", f"api/method/{method}", params)

    def post(self, method, body):
        return self.request("POST", f"api/method/{method}", body=body)


def server_message(payload, raw) -> str:
    """Extracts the human readable error the app would show."""
    if not isinstance(payload, dict):
        return (raw or "")[:300]
    parts = []
    messages = payload.get("_server_messages")
    if messages:
        try:
            for item in json.loads(messages):
                try:
                    parts.append(json.loads(item).get("message", item))
                except (ValueError, AttributeError):
                    parts.append(str(item))
        except ValueError:
            parts.append(str(messages))
    if payload.get("exc_type"):
        parts.append(f"[{payload['exc_type']}]")
    if not parts and payload.get("exception"):
        parts.append(str(payload["exception"]).splitlines()[-1])
    if not parts and payload.get("message") and isinstance(payload["message"], str):
        parts.append(payload["message"])
    return " ".join(parts)[:400] or (raw or "")[:300]


class Report:
    def __init__(self):
        self.rows = []

    def add(self, area, name, status, ok, detail=""):
        self.rows.append((area, name, status, ok, detail))
        mark = "PASS" if ok else "FAIL"
        print(f"[{mark}] {area:<10} {name:<56} HTTP {status:<3} {detail}")

    @property
    def failures(self):
        return [row for row in self.rows if not row[3]]


def check_list(client, report, area, name, doctype, fields, expect_keys=(), **kwargs):
    status, payload, raw = client.get_list(doctype, fields, **kwargs)
    rows = payload.get("data") if isinstance(payload, dict) else None
    ok = status == 200 and isinstance(rows, list)
    detail = ""
    if ok:
        detail = f"{len(rows)} row(s)"
        if rows and expect_keys:
            missing = [key for key in expect_keys if key not in rows[0]]
            if missing:
                ok = False
                detail += f"; missing keys the app needs: {missing}"
    else:
        detail = server_message(payload, raw)
    report.add(area, name, status, ok, detail)
    return rows if ok else None


def check_doc(client, report, area, name, doctype, docname, expect_keys=()):
    status, payload, raw = client.get_doc(doctype, docname)
    doc = payload.get("data") if isinstance(payload, dict) else None
    ok = status == 200 and isinstance(doc, dict)
    detail = ""
    if ok:
        missing = [key for key in expect_keys if key not in doc]
        detail = f"{doctype} {docname}" + (f"; missing keys: {missing}" if missing else "")
        ok = not missing
    else:
        detail = server_message(payload, raw)
    report.add(area, name, status, ok, detail)
    return doc if ok else None


def check_sum(client, report, area, name, doctype, field, filters):
    """The dashboard's SUM. Frappe up to v15 takes `sum(field) as total`; v16 rejects SQL functions as
    strings and wants {"SUM": field, "as": "total"} - the app tries the first and falls back like this."""
    status, payload, raw = client.get_list(doctype, [f"sum({field}) as total"], filters=filters, limit=1)
    syntax = "string syntax"
    if status != 200 and "SQL functions are not allowed" in server_message(payload, raw):
        status, payload, raw = client.get_list(doctype, [{"SUM": field, "as": "total"}], filters=filters, limit=1)
        syntax = "dict syntax (Frappe v16)"
    rows = payload.get("data") if isinstance(payload, dict) else None
    ok = status == 200 and isinstance(rows, list)
    total = rows[0].get("total") if ok and rows else None
    report.add(area, name, status, ok, f"total={total} via {syntax}" if ok else server_message(payload, raw))


def check_count(client, report, area, name, doctype, filters):
    status, payload, raw = client.get_count(doctype, filters)
    ok = status == 200 and isinstance(payload, dict) and isinstance(payload.get("message"), (int, float))
    report.add(area, name, status, ok, f"count={payload.get('message')}" if ok else server_message(payload, raw))


def check_single_value(client, report, area, name, doctype, field):
    status, payload, raw = client.get_single_value(doctype, field)
    ok = status == 200 and isinstance(payload, dict) and "message" in payload
    value = payload.get("message") if ok else None
    report.add(area, name, status, ok, f"{field}={value!r}" if ok else server_message(payload, raw))
    return value


def check_method(client, report, area, name, method, params=None, body=None, expect=dict):
    """GET (params) or POST (body) a whitelisted method; the message must be of type `expect`."""
    status, payload, raw = client.post(method, body) if body is not None else client.call(method, params)
    message = payload.get("message") if isinstance(payload, dict) else None
    ok = status == 200 and isinstance(message, expect)
    report.add(area, name, status, ok, "" if ok else server_message(payload, raw))
    return message if ok else None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default=os.environ.get("ERP_URL"))
    parser.add_argument("--key", default=os.environ.get("ERP_KEY"))
    parser.add_argument("--secret", default=os.environ.get("ERP_SECRET"))
    parser.add_argument("--barcode")
    parser.add_argument("--pick-list", dest="pick_list")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    if not (args.url and args.key and args.secret):
        parser.error("--url, --key and --secret (or ERP_URL / ERP_KEY / ERP_SECRET) are required")

    client = Client(args.url, args.key, args.secret, verbose=args.json)
    report = Report()
    today = dt.date.today()
    month_start = today.replace(day=1)

    # ---- Login / session ---------------------------------------------------------------
    status, payload, raw = client.call("frappe.auth.get_logged_user")
    user = payload.get("message") if isinstance(payload, dict) else None
    ok = status == 200 and isinstance(user, str) and user not in ("", "Guest")
    report.add("auth", "frappe.auth.get_logged_user (token login)", status, ok, f"user={user}" if ok else server_message(payload, raw))
    if not ok:
        print("\nAuthentication failed; nothing else can be checked. Verify the API key/secret pair and that the user is enabled.")
        return 1

    status, payload, raw = client.call("frappe.utils.change_log.get_versions")
    if status == 200 and isinstance(payload, dict):
        versions = {k: v.get("version") for k, v in payload["message"].items()}
        installed_picking = "wmserp_picking" in versions
        report.add("auth", "installed apps", status, True, json.dumps(versions))
    else:
        installed_picking = False
        report.add("auth", "installed apps", status, False, server_message(payload, raw))

    # ---- Profile -----------------------------------------------------------------------
    check_doc(client, report, "profile", "GET User/<me>", "User", user, ("name", "first_name", "full_name", "roles"))

    # ---- Dashboard KPIs ------------------------------------------------------------------
    check_count(client, report, "dashboard", "count Item (disabled=0)", "Item", [["disabled", "=", 0]])
    check_count(client, report, "dashboard", "count Purchase Order (open)", "Purchase Order", [["docstatus", "=", 1], ["status", "in", PO_OPEN_STATUSES]])
    check_count(client, report, "dashboard", "count Sales Order (open)", "Sales Order", [["docstatus", "=", 1], ["status", "in", SO_OPEN_STATUSES]])
    check_sum(client, report, "dashboard", "Sales Invoice SUM(grand_total) this month", "Sales Invoice", "grand_total",
              [["docstatus", "=", 1], ["posting_date", ">=", str(month_start)], ["posting_date", "<=", str(today)]])
    check_count(client, report, "dashboard", "count Delivery Note this month", "Delivery Note", [["docstatus", "=", 1], ["posting_date", ">=", str(month_start)]])
    check_single_value(client, report, "dashboard", "Global Defaults.default_currency", "Global Defaults", "default_currency")
    check_list(client, report, "dashboard", "recent Stock Ledger Entry (activity)", "Stock Ledger Entry", SLE_FIELDS, ("item_code", "warehouse", "actual_qty"),
               filters=[["is_cancelled", "=", 0]], order_by="posting_date desc, posting_time desc, creation desc", limit=10)

    # ---- Inventory / analytics -----------------------------------------------------------
    check_count(client, report, "analytics", "count Purchase Receipt last 7 days", "Purchase Receipt", [["docstatus", "=", 1], ["posting_date", ">=", str(today - dt.timedelta(days=7))]])
    check_count(client, report, "analytics", "count Pick List (Draft/Open)", "Pick List", [["docstatus", "<", 2], ["status", "in", ["Draft", "Open"]]])
    check_list(client, report, "analytics", "overdue Sales Orders (delivery delays)", "Sales Order", SO_LIST_FIELDS, ("name", "delivery_date"),
               filters=[["docstatus", "=", 1], ["status", "in", SO_OPEN_STATUSES], ["delivery_date", "<", str(today)]], order_by="delivery_date asc", limit=50)
    check_list(client, report, "analytics", "Stock Ledger Entry last 7 days (heatmap)", "Stock Ledger Entry", SLE_FIELDS, ("posting_date",),
               filters=[["is_cancelled", "=", 0], ["posting_date", ">=", str(today - dt.timedelta(days=6))]], order_by="posting_date asc", limit=2000)
    company = check_single_value(client, report, "analytics", "Global Defaults.default_company", "Global Defaults", "default_company")
    if not company:
        rows = check_list(client, report, "analytics", "first Company (fallback)", "Company", ["name"], ("name",), limit=1)
        company = rows[0]["name"] if rows else None
    if company:
        status, payload, raw = client.run_report("Stock Ageing", {"company": company, "to_date": str(today), "range1": 30, "range2": 60, "range3": 90, "show_warehouse_wise_stock": 0})
        message = payload.get("message") if isinstance(payload, dict) else None
        ok = status == 200 and isinstance(message, dict) and "result" in message
        report.add("analytics", "query_report.run Stock Ageing", status, ok,
                   f"{len(message.get('result') or [])} row(s), {len(message.get('columns') or [])} column(s)" if ok else server_message(payload, raw))
    else:
        report.add("analytics", "query_report.run Stock Ageing", 0, False, "no company available")

    # ---- Items / scan ----------------------------------------------------------------------
    items = check_list(client, report, "inventory", "Item list (search)", "Item", ITEM_LIST_FIELDS, ("item_code", "item_name", "stock_uom"),
                       filters=[["disabled", "=", 0]], order_by="modified desc", limit=20)
    first_item = items[0]["name"] if items else None
    if first_item:
        check_doc(client, report, "inventory", "GET Item/<first> (barcodes child table)", "Item", first_item, ("item_code", "stock_uom", "barcodes"))
        check_list(client, report, "inventory", "Bin by item (stock levels)", "Bin", BIN_FIELDS, ("item_code", "warehouse", "actual_qty"),
                   filters=[["item_code", "=", first_item]], order_by="actual_qty desc", limit=50)
    if args.barcode:
        check_list(client, report, "inventory", f"Item by barcode {args.barcode} (child filter)", "Item", ITEM_LIST_FIELDS, ("item_code",),
                   filters=[["Item Barcode", "barcode", "=", args.barcode]], limit=1)
    warehouses = check_list(client, report, "inventory", "Warehouse list", "Warehouse", WAREHOUSE_FIELDS, ("name", "warehouse_name"),
                            filters=[["is_group", "=", 0], ["disabled", "=", 0]], order_by="name asc", limit=50)
    if warehouses:
        check_doc(client, report, "inventory", "GET Warehouse/<first>", "Warehouse", warehouses[0]["name"], ("name", "warehouse_name"))
        check_list(client, report, "inventory", "Bin by warehouse", "Bin", BIN_FIELDS, ("item_code",),
                   filters=[["warehouse", "=", warehouses[0]["name"]], ["actual_qty", ">", 0]], order_by="actual_qty desc", limit=50)

    # ---- Orders ----------------------------------------------------------------------------
    pos = check_list(client, report, "orders", "open Purchase Orders", "Purchase Order", PO_LIST_FIELDS, ("name", "supplier_name", "per_received"),
                     filters=[["docstatus", "=", 1], ["status", "in", PO_OPEN_STATUSES]], order_by="schedule_date asc, modified desc", limit=30)
    if pos:
        check_doc(client, report, "orders", "GET Purchase Order/<first> (items)", "Purchase Order", pos[0]["name"], ("items", "supplier"))
        check_mapper(client, report, "receive", "make_purchase_receipt (draft the app edits)", MAKE_PURCHASE_RECEIPT, pos[0]["name"], "purchase_order_item")
    sos = check_list(client, report, "orders", "open Sales Orders", "Sales Order", SO_LIST_FIELDS, ("name", "customer_name", "per_delivered"),
                     filters=[["docstatus", "=", 1], ["status", "in", SO_OPEN_STATUSES]], order_by="delivery_date asc, modified desc", limit=30)
    if sos:
        so = check_doc(client, report, "orders", "GET Sales Order/<first> (items)", "Sales Order", sos[0]["name"], ("items", "customer"))
        mapped = check_mapper(client, report, "dispatch", "make_delivery_note (draft the app edits)", MAKE_DELIVERY_NOTE, sos[0]["name"], "so_detail")
        if so and mapped:
            check_batches(client, report, so, mapped)
    check_list(client, report, "orders", "recent Delivery Notes", "Delivery Note", DN_LIST_FIELDS, ("name",),
               filters=[["docstatus", "=", 1]], order_by="posting_date desc, modified desc", limit=10)

    # ---- Picking (custom app) ----------------------------------------------------------------
    if not installed_picking:
        report.add("picking", "wmserp_picking app", 0, False, "not installed on this site (Pick tab and picking KPIs will fail). See erpnext/wmserp_picking/README.md")
    else:
        settings = check_method(client, report, "picking", "get_settings (QR keys)", PICKING_METHOD + "get_settings")
        if settings:
            print(f"           QR keys: item={settings.get('qr_item_key')!r} batch={settings.get('qr_batch_key')!r} app={settings.get('app_version')}")
        kpis = check_method(client, report, "picking", "get_picker_kpis (dashboard)", PICKING_METHOD + "get_picker_kpis")
        if kpis:
            print(f"           today: rows_picked={kpis.get('rows_picked')} avg={kpis.get('avg_seconds_per_row')} open_rows={kpis.get('open_rows')}")
        lists = check_method(client, report, "picking", "get_my_pick_lists (my tasks)", PICKING_METHOD + "get_my_pick_lists", expect=list)
        if lists is not None:
            print(f"           {len(lists)} open pick list(s) with rows assigned to {user}")
        target = args.pick_list or (lists[0]["name"] if lists else None)
        if target:
            doc = check_method(client, report, "picking", f"get_pick_list {target}", PICKING_METHOD + "get_pick_list", params={"name": target})
            if doc:
                mine = [r for r in doc.get("items", []) if r.get("is_mine")]
                print(f"           status={doc.get('picking_status')} rows={len(doc.get('items', []))} mine={len(mine)} all_rows_picked={doc.get('all_rows_picked')}")
                if args.pick_list:
                    run_picking_flow(client, report, target, doc, settings or {})
        else:
            report.add("picking", "picking workflow", 0, True, "skipped: no open pick list has rows assigned to this user (pass --pick-list NAME to test one)")

    failures = report.failures
    print("\n" + "=" * 100)
    print(f"{len(report.rows) - len(failures)} passed, {len(failures)} failed")
    for area, name, status, _ok, detail in failures:
        print(f"  - [{area}] {name}: HTTP {status} {detail}")
    if failures:
        print("\nTypical causes: 403 = the API user's roles lack read permission on that DocType (grant Stock User /"
              " Sales User / Purchase User / Accounts User, or Stock Manager); 417/500 on a report = filters changed"
              " in this ERPNext version; 404 on wmserp_picking.* = custom app not installed or bench not restarted.")
    return 1 if failures else 0


def check_mapper(client, report, area, name, method, source_name, link_field):
    """The Receive / Dispatch screens start from ERPNext's own mapped draft (nothing is saved here)."""
    doc = check_method(client, report, area, f"{name} for {source_name}", method, params={"source_name": source_name})
    if not doc:
        return None
    rows = doc.get("items") or []
    unlinked = [r.get("item_code") for r in rows if not r.get(link_field)]
    ok = bool(rows) and not unlinked
    detail = f"{len(rows)} pending row(s)" + (f"; rows without {link_field}: {unlinked}" if unlinked else "")
    if not rows:
        detail = "no pending rows (everything received/delivered); the app refuses to create an empty document"
    report.add(area, f"{name}: mapped rows carry {link_field}", 200, ok, detail)
    check_required_fields(client, report, area, doc)
    return doc


def check_required_fields(client, report, area, mapped):
    """Required fields the site added (Custom Fields / Property Setters) that the mapped draft leaves empty:
    the app fills them from the company's default accounting dimensions or asks the user once."""
    doctype = mapped.get("doctype")
    if not doctype:
        return
    status, payload, raw = client.call(GET_DOCTYPE, {"doctype": doctype})
    docs = payload.get("docs") if isinstance(payload, dict) else None
    if status != 200 or not isinstance(docs, list):
        report.add(area, f"getdoctype {doctype} (required fields)", status, False, server_message(payload, raw))
        return
    required = {}
    for meta in docs:
        for df in meta.get("fields", []):
            if str(df.get("reqd")) in ("1", "True", "true") and df.get("fieldtype") in ANSWERABLE_FIELDTYPES and not df.get("default"):
                required.setdefault(meta.get("name"), []).append(df)
    missing = []
    for dt, fields in required.items():
        targets = [mapped] if dt == doctype else [r for r in mapped.get("items", []) if r.get("doctype") == dt]
        for df in fields:
            if any(t.get(df["fieldname"]) in (None, "") for t in targets):
                missing.append(f"{dt}.{df['fieldname']} ({df.get('label')}, {df.get('fieldtype')}{' -> ' + df['options'] if df.get('fieldtype') == 'Link' else ''})")
    status, payload, raw = client.call(GET_DIMENSIONS, {"with_cost_center_and_project": "0"})
    defaults = {}
    if status == 200 and isinstance(payload, dict) and isinstance(payload.get("message"), list) and len(payload["message"]) > 1:
        defaults = (payload["message"][1] or {}).get(mapped.get("company"), {}) or {}
    ok = not missing
    detail = "nothing required beyond what the order provides" if ok else "empty required field(s): " + "; ".join(missing)
    if defaults:
        detail += f"; default accounting dimensions for {mapped.get('company')}: {defaults}"
    report.add(area, f"required fields on {doctype} (site customisations)", 200, True, detail)
    if missing and not all(m.split(" ")[0].split(".")[1] in defaults for m in missing):
        print("           the app will ask for the field(s) above once and remember the answer on the device")


def check_batches(client, report, sales_order, mapped_note):
    """Batch-tracked items on the first open Sales Order: the app allocates batches first-expiry-first-out
    from get_batch_qty + Batch.expiry_date before creating the Delivery Note."""
    codes = sorted({r.get("item_code") for r in sales_order.get("items", []) if r.get("item_code")})
    if not codes:
        return
    tracking = check_list(client, report, "dispatch", "Item has_batch_no / has_serial_no", "Item", ["name", "has_batch_no", "has_serial_no"],
                          ("name", "has_batch_no", "has_serial_no"), filters=[["name", "in", codes]], limit=len(codes))
    if not tracking:
        return
    serial = [t["name"] for t in tracking if t.get("has_serial_no")]
    if serial:
        report.add("dispatch", "serial-numbered items on the order", 200, True, f"{serial}: the app refuses these (deliver them from ERPNext)")
    batch_items = {t["name"] for t in tracking if t.get("has_batch_no")}
    for row in mapped_note.get("items", []):
        if row.get("item_code") not in batch_items:
            continue
        warehouse = row.get("warehouse") or sales_order.get("set_warehouse")
        if not warehouse:
            report.add("dispatch", f"batches of {row['item_code']}", 0, False, "row has no warehouse; the app asks the user to pick one")
            continue
        batches = check_method(client, report, "dispatch", f"get_batch_qty {row['item_code']} @ {warehouse}", GET_BATCH_QTY,
                               params={"item_code": row["item_code"], "warehouse": warehouse}, expect=list)
        if batches is None:
            continue
        names = [b.get("batch_no") for b in batches if b.get("batch_no") and float(b.get("qty") or 0) > 0]
        if not names:
            report.add("dispatch", f"stock in batches for {row['item_code']}", 200, False,
                       f"no batch has stock in {warehouse}; dispatching {row['item_code']} from the app will fail with 'insufficient batch stock'")
            continue
        details = check_list(client, report, "dispatch", f"Batch expiry for {len(names)} batch(es)", "Batch", ["name", "expiry_date", "disabled"], ("name",),
                             filters=[["name", "in", names]], limit=len(names))
        if details is not None:
            usable = [d for d in details if not d.get("disabled") and (not d.get("expiry_date") or d["expiry_date"] >= str(dt.date.today()))]
            usable.sort(key=lambda d: (d.get("expiry_date") is None, d.get("expiry_date") or ""))
            print(f"           FEFO order for {row['item_code']}: {[(d['name'], d.get('expiry_date')) for d in usable]}")
        break


def run_picking_flow(client, report, name, doc, settings):
    """start_row -> save_row_progress -> complete_row on the API user's rows, then generate_document
    when this call turned out to be the last picker (writes data)."""
    print(f"\n--- row-level picking workflow on {name} (writes data) ---")
    item_key = settings.get("qr_item_key") or "item_code"
    batch_key = settings.get("qr_batch_key") or "batch_no"
    mine = [r for r in doc.get("items", []) if r.get("is_mine") and r.get("row_status") != "Picked"]
    if not mine:
        report.add("picking", "rows assigned to me", 0, False, "no open row of this pick list is assigned to the API user (set Picker on the rows first)")
        return
    first = mine[0]
    label = {item_key: first["item_code"]}
    if first.get("batch_no"):
        label[batch_key] = first["batch_no"]
    print(f"           scanning label {json.dumps(label)} for row {first['idx']}")

    result = check_method(client, report, "picking", f"start_row (row {first['idx']})", PICKING_METHOD + "start_row", body={"name": name, "row": first["name"]})
    if not result:
        return
    half = {"name": name, "row": first["name"], "picked_qty": first["required_qty"] / 2, "item_code": label[item_key], "batch_no": label.get(batch_key), "elapsed_seconds": 5}
    result = check_method(client, report, "picking", "save_row_progress (half of the row, with label)", PICKING_METHOD + "save_row_progress", body=half)
    if result:
        print(f"           row_status={result['row'].get('row_status')} picked={result['row'].get('picked_qty')} card={result['pick_list'].get('picking_status')}")

    wrong = dict(half, batch_no="WRONG-BATCH-FOR-TEST")
    status, payload, raw = client.post(PICKING_METHOD + "save_row_progress", wrong)
    rejected = status != 200 and "batch" in server_message(payload, raw).lower()
    report.add("picking", "save_row_progress rejects a wrong batch", status, rejected or not first.get("batch_no"),
               server_message(payload, raw) if rejected else ("row has no allocated batch, check skipped" if not first.get("batch_no") else "accepted a wrong batch!"))

    last_picker = False
    for row in mine:
        body = {"name": name, "row": row["name"], "picked_qty": row["required_qty"], "batch_no": row.get("batch_no"), "elapsed_seconds": 30}
        result = check_method(client, report, "picking", f"complete_row (row {row['idx']}, {row['item_code']})", PICKING_METHOD + "complete_row", body=body)
        if not result:
            return
        print(f"           row_completed={result.get('row_completed')} card_completed={result.get('card_completed')} is_last_picker={result.get('is_last_picker')} duration={result['row'].get('duration_seconds')}")
        last_picker = last_picker or bool(result.get("is_last_picker"))

    if not last_picker:
        report.add("picking", "last picker rule", 200, True, "other pickers still have open rows: 'Task completed' path (no document CTA)")
        return
    result = check_method(client, report, "picking", "generate_document (last picker)", PICKING_METHOD + "generate_document", body={"name": name})
    if result:
        print(f"           {result.get('doctype')} {result.get('name')} already_generated={result.get('already_generated')}")
        again = check_method(client, report, "picking", "generate_document again (duplicate prevention)", PICKING_METHOD + "generate_document", body={"name": name})
        if again and not (again.get("already_generated") and again.get("name") == result.get("name")):
            report.add("picking", "duplicate prevention", 200, False, f"second call returned {again.get('name')} already_generated={again.get('already_generated')}")


if __name__ == "__main__":
    sys.exit(main())
