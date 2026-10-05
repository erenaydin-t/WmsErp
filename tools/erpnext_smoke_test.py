#!/usr/bin/env python3
"""Replays every ERPNext request the WMS ERP Android app makes against a real site and reports
what fails, so server-side problems (permissions, missing fields, missing custom app, report
filters, workflow stages) can be found without a device.

Only the Python standard library is used. Run it from any machine that can reach the site, with the
API key of the *regular warehouse user* the app will be used with (not an Administrator), so the
permission and workflow checks mean something:

    python3 tools/erpnext_smoke_test.py --url https://erp.example.com --key API_KEY --secret API_SECRET

    # or with environment variables
    ERP_URL=https://erp.example.com ERP_KEY=... ERP_SECRET=... python3 tools/erpnext_smoke_test.py

Options:
    --barcode 8690000000017     also test the barcode lookup used by the Scan screen
    --batch B-2026-001          also test the batch lookup (Scan > Item / Batch) and the label sheet of that batch
    --sales-order SAL-ORD-...   diagnose Pick List creation from this Sales Order (default: the first open one)
    --insert-pick-list          actually insert the mapped Pick List as a draft and delete it again (WRITES)
    --receive MAT-PRE-...       save the expected quantities as the progress of a count on this draft Purchase
                                Receipt (submit=0, nothing removed, nothing submitted). WRITES the counts.
    --pick-list STO-PICK-00001  run the row-level picking workflow (start_row / save_row_progress /
                                complete_row / generate_document) on the rows of this submitted Pick List
                                that are assigned to the API user. THIS WRITES DATA; use a test site.
    --json                      print the raw JSON of every response (verbose)

Without the write flags nothing is modified: the Pick List diagnostic only calls ERPNext's own mapper
(`create_pick_list`, what the desk's *Create > Pick List* button runs) and inspects the DocType meta.
Exit code is 1 when at least one check fails.
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
SO_LIST_FIELDS = ["name", "customer", "customer_name", "status", "transaction_date", "delivery_date", "per_delivered", "company", "docstatus"]
ITEM_LIST_FIELDS = ["name", "item_code", "item_name", "item_group", "stock_uom", "description", "image", "disabled", "is_stock_item", "has_batch_no", "has_serial_no", "brand"]
BATCH_FIELDS = ["name", "batch_id", "item", "item_name", "expiry_date", "manufacturing_date", "disabled", "stock_uom", "supplier", "description"]
BIN_FIELDS = ["name", "item_code", "warehouse", "actual_qty", "reserved_qty", "ordered_qty", "projected_qty", "stock_uom"]
WAREHOUSE_FIELDS = ["name", "warehouse_name", "company", "is_group", "parent_warehouse", "disabled", "warehouse_type", "city"]
SLE_FIELDS = ["name", "item_code", "warehouse", "actual_qty", "voucher_type", "voucher_no", "posting_date", "posting_time"]
AGEING_RANGES = [30, 60, 90]
PICKING_METHOD = "wmserp_picking.api.pick_list."
STOCKTAKING_METHOD = "wmserp_picking.api.stocktaking."
RECEIPT_METHOD = "wmserp_picking.api.purchase_receipt."
LABELS_METHOD = "wmserp_picking.api.labels."
GET_DOCTYPE = "frappe.desk.form.load.getdoctype"
GET_DIMENSIONS = "erpnext.accounts.doctype.accounting_dimension.accounting_dimension.get_dimensions"
ANSWERABLE_FIELDTYPES = {"Link", "Dynamic Link", "Select", "Data", "Small Text", "Text", "Long Text", "Int", "Float", "Currency", "Percent", "Date", "Datetime", "Time"}
CREATE_PICK_LIST = "erpnext.selling.doctype.sales_order.sales_order.create_pick_list"
GET_BATCH_QTY = "erpnext.stock.doctype.batch.batch.get_batch_qty"


class Client:
    def __init__(self, url: str, key: str, secret: str, verbose: bool = False):
        self.base = url.rstrip("/")
        self.headers = {
            "Authorization": f"token {key}:{secret}",
            "Accept": "application/json",
            "User-Agent": "WmsErp-smoke-test/1.2",
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
    parser.add_argument("--batch")
    parser.add_argument("--sales-order", dest="sales_order")
    parser.add_argument("--insert-pick-list", dest="insert_pick_list", action="store_true")
    parser.add_argument("--receive")
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
    if user == "Administrator":
        report.add("auth", "regular user", 200, True, "WARNING: running as Administrator; permission and workflow checks will not reflect a warehouse user. Use the warehouse user's API key.")

    status, payload, raw = client.call("frappe.utils.change_log.get_versions")
    if status == 200 and isinstance(payload, dict):
        versions = {k: v.get("version") for k, v in payload["message"].items()}
        installed_picking = "wmserp_picking" in versions
        report.add("auth", "installed apps", status, True, json.dumps(versions))
    else:
        installed_picking = False
        report.add("auth", "installed apps", status, False, server_message(payload, raw))

    # ---- Profile (read-only in the app) ---------------------------------------------------
    check_doc(client, report, "profile", "GET User/<me> (read-only account card)", "User", user, ("name", "first_name", "full_name", "roles"))

    # ---- Dashboard KPIs: counts only, never an amount -------------------------------------
    check_count(client, report, "dashboard", "count Item (disabled=0)", "Item", [["disabled", "=", 0]])
    check_count(client, report, "dashboard", "count Purchase Order (open)", "Purchase Order", [["docstatus", "=", 1], ["status", "in", PO_OPEN_STATUSES]])
    check_count(client, report, "dashboard", "count Sales Order (open)", "Sales Order", [["docstatus", "=", 1], ["status", "in", SO_OPEN_STATUSES]])
    check_count(client, report, "dashboard", "count Purchase Receipt this month", "Purchase Receipt", [["docstatus", "=", 1], ["posting_date", ">=", str(month_start)]])
    check_count(client, report, "dashboard", "count Delivery Note this month", "Delivery Note", [["docstatus", "=", 1], ["posting_date", ">=", str(month_start)]])
    check_list(client, report, "dashboard", "recent Stock Ledger Entry (activity)", "Stock Ledger Entry", SLE_FIELDS, ("item_code", "warehouse", "actual_qty"),
               filters=[["is_cancelled", "=", 0]], order_by="posting_date desc, posting_time desc, creation desc", limit=10)

    # ---- Inventory / analytics -----------------------------------------------------------
    check_count(client, report, "analytics", "count Purchase Receipt last 7 days", "Purchase Receipt", [["docstatus", "=", 1], ["posting_date", ">=", str(today - dt.timedelta(days=7))]])
    check_count(client, report, "analytics", "count Pick List (Draft/Open)", "Pick List", [["docstatus", "<", 2], ["status", "in", ["Draft", "Open"]]])
    sos = check_list(client, report, "analytics", "overdue Sales Orders (delivery delays, no amounts)", "Sales Order", SO_LIST_FIELDS, ("name", "delivery_date"),
                     filters=[["docstatus", "=", 1], ["status", "in", SO_OPEN_STATUSES], ["delivery_date", "<", str(today)]], order_by="delivery_date asc", limit=50)
    check_list(client, report, "analytics", "Stock Ledger Entry last 7 days (heatmap)", "Stock Ledger Entry", SLE_FIELDS, ("posting_date",),
               filters=[["is_cancelled", "=", 0], ["posting_date", ">=", str(today - dt.timedelta(days=6))]], order_by="posting_date asc", limit=2000)
    company = check_single_value(client, report, "analytics", "Global Defaults.default_company", "Global Defaults", "default_company")
    if not company:
        rows = check_list(client, report, "analytics", "first Company (fallback)", "Company", ["name"], ("name",), limit=1)
        company = rows[0]["name"] if rows else None
    if company:
        check_stock_ageing(client, report, company, today)
    else:
        report.add("analytics", "query_report.run Stock Ageing", 0, False, "no company available")

    # ---- Items / batches / scan -------------------------------------------------------------
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
    batches = check_list(client, report, "inventory", "Batch list (batch scan)", "Batch", BATCH_FIELDS, ("name", "item"),
                         filters=[["disabled", "=", 0]], order_by="modified desc", limit=5)
    batch_name = args.batch or (batches[0]["name"] if batches else None)
    if batch_name:
        check_doc(client, report, "inventory", f"GET Batch/{batch_name} (scan a batch label)", "Batch", batch_name, ("name", "item"))
        check_method(client, report, "inventory", f"get_batch_qty batch_no={batch_name} (stock per warehouse)", GET_BATCH_QTY, params={"batch_no": batch_name}, expect=list)
    elif args.batch:
        report.add("inventory", "batch scan", 0, False, "no batch found")
    warehouses = check_list(client, report, "inventory", "Warehouse list", "Warehouse", WAREHOUSE_FIELDS, ("name", "warehouse_name"),
                            filters=[["is_group", "=", 0], ["disabled", "=", 0]], order_by="name asc", limit=50)
    if warehouses:
        check_doc(client, report, "inventory", "GET Warehouse/<first>", "Warehouse", warehouses[0]["name"], ("name", "warehouse_name"))
        check_list(client, report, "inventory", "Bin by warehouse", "Bin", BIN_FIELDS, ("item_code",),
                   filters=[["warehouse", "=", warehouses[0]["name"]], ["actual_qty", ">", 0]], order_by="actual_qty desc", limit=50)

    # ---- Pick List creation diagnostic (the desk's Create > Pick List) ----------------------
    so_name = args.sales_order
    if not so_name:
        open_sos = check_list(client, report, "orders", "open Sales Orders (pick list source)", "Sales Order", SO_LIST_FIELDS, ("name",),
                              filters=[["docstatus", "=", 1], ["status", "in", SO_OPEN_STATUSES]], order_by="modified desc", limit=5)
        so_name = open_sos[0]["name"] if open_sos else None
    if so_name:
        diagnose_pick_list_creation(client, report, so_name, insert=args.insert_pick_list)
    else:
        report.add("picklist-create", "Pick List creation diagnostic", 0, True, "skipped: no open Sales Order (pass --sales-order NAME)")

    # ---- Receiving: draft Purchase Receipts at the warehouse stage ---------------------------
    drafts = check_list(client, report, "receive", "draft Purchase Receipts (read permission)", "Purchase Receipt",
                        ["name", "supplier", "supplier_name", "posting_date", "workflow_state", "docstatus"], ("name",),
                        filters=[["docstatus", "=", 0], ["is_return", "=", 0]], order_by="modified desc", limit=10)
    if not installed_picking:
        report.add("receive", "wmserp_picking app", 0, False, "not installed on this site: the Receive tab needs wmserp_picking 0.4.0+ (get_receivable / get_receipt / receive)")
    else:
        receivable = check_method(client, report, "receive", "get_receivable (Orders > Receive)", RECEIPT_METHOD + "get_receivable", params={"limit": 50}, expect=list)
        if receivable is not None:
            print(f"           {len(receivable)} draft receipt(s) at this user's stage; {len(drafts or [])} draft receipt(s) readable in total")
            if drafts and not receivable:
                print("           drafts exist but none is receivable: check the Purchase Receipt workflow stage of the user's role, or set"
                      " WMS Settings > Receipt Workflow States")
        target = args.receive or (receivable[0]["name"] if receivable else None)
        if target:
            receipt = check_method(client, report, "receive", f"get_receipt {target}", RECEIPT_METHOD + "get_receipt", params={"name": target})
            if receipt:
                rows = receipt.get("items") or []
                needs_batch = [r["item_code"] for r in rows if r.get("needs_batch")]
                print(f"           stage={receipt.get('workflow_state') or 'Draft'} can_receive={receipt.get('can_receive')} rows={len(rows)}"
                      f" expected={sum(float(r.get('qty') or 0) for r in rows)} batch needed for {needs_batch or 'no row'}")
                check_required_fields(client, report, "receive", receipt, "Purchase Receipt")
                if args.receive:
                    run_receive_progress(client, report, receipt)
        else:
            report.add("receive", "receiving workflow", 0, True, "skipped: no receivable draft (pass --receive NAME to count one; writes)")

    # ---- Label sheets (desk list action; printed from ERPNext, not the app) ------------------
    if installed_picking:
        for doctype, names in (("Batch", [b["name"] for b in (batches or [])[:3]]), ("Item", [first_item] if first_item else [])):
            formats = check_method(client, report, "labels", f"get_label_sheet_formats {doctype}", LABELS_METHOD + "get_label_sheet_formats", params={"doctype": doctype}, expect=list)
            if formats is None:
                print("           (label API missing: update wmserp_picking to 0.4.0 or later and run bench migrate)")
                break
            print(f"           {doctype} sheet formats: {[f.get('name') for f in formats]}")
            if names:
                status, payload, raw = client.call(LABELS_METHOD + "label_sheet_html", {"doctype": doctype, "names": json.dumps(names)})
                html = payload.get("message") if isinstance(payload, dict) else None
                ok = status == 200 and isinstance(html, str) and "wms-page" in html and "<svg" in html
                report.add("labels", f"label_sheet_html {doctype} x{len(names)}", status, ok,
                           f"{html.count('wms-label')} label cell(s), {html.count('wms-page')} page(s)" if ok else server_message(payload, raw))

    # ---- Picking (custom app) ----------------------------------------------------------------
    if not installed_picking:
        report.add("picking", "wmserp_picking app", 0, False, "not installed on this site (Pick tab and picking KPIs will fail). See erpnext/wmserp_picking/README.md")
    else:
        settings = check_method(client, report, "picking", "get_settings (QR keys)", PICKING_METHOD + "get_settings")
        if settings:
            print(f"           QR keys: item={settings.get('qr_item_key')!r} batch={settings.get('qr_batch_key')!r} app={settings.get('app_version')} features={settings.get('features')}")
            missing_features = {"purchase_receipt_receiving", "label_sheets", "required_field_values"} - set(settings.get("features") or [])
            report.add("picking", "backend features for this app version", 200, not missing_features,
                       "all present" if not missing_features else f"missing {sorted(missing_features)}: update wmserp_picking to 0.4.0+")
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

        # ---- Stocktaking (custom app 0.3+), read-only checks ----------------------------------
        sessions = check_method(client, report, "stocktaking", "get_my_sessions (Orders > Count)", STOCKTAKING_METHOD + "get_my_sessions", expect=list)
        if sessions is None:
            print("           (stocktaking API missing: update wmserp_picking to 0.3.0 or later and run bench migrate)")
        elif not sessions:
            report.add("stocktaking", "counting sessions", 200, True, "no session in Counting/Recount for this user (start one in ERPNext to test the app)")
        else:
            session = sessions[0]
            totals = session.get("totals") or {}
            print(f"           {len(sessions)} session(s); {session['name']}: {session.get('counting_mode')} / {session.get('status')} "
                  f"counted={totals.get('counted_items')}/{totals.get('total_items')} can_count={session.get('can_count')}")
            header = check_method(client, report, "stocktaking", f"get_session {session['name']}", STOCKTAKING_METHOD + "get_session", params={"name": session["name"]})
            page = check_method(client, report, "stocktaking", "get_items (first page, mine)", STOCKTAKING_METHOD + "get_items",
                                params={"name": session["name"], "start": 0, "limit": 50, "mine": 1 if (header or {}).get("counting_mode") == "Assigned" else 0})
            if page:
                rows = page.get("items") or []
                print(f"           {page.get('total')} row(s) for this user; barcodes for {len(page.get('barcodes') or {})} item(s)")
                if rows:
                    row = rows[0]
                    label = {(header or {}).get("qr_item_key") or "item_code": row["item_code"]}
                    if row.get("batch_no"):
                        label[(header or {}).get("qr_batch_key") or "batch_no"] = row["batch_no"]
                    found = check_method(client, report, "stocktaking", "lookup (JSON label of the first row)", STOCKTAKING_METHOD + "lookup",
                                         params={"name": session["name"], "code": json.dumps(label)})
                    if found is not None and not found.get("in_session"):
                        report.add("stocktaking", "lookup finds the row", 200, False, f"{label} was not matched to a row of the session")

    failures = report.failures
    print("\n" + "=" * 100)
    print(f"{len(report.rows) - len(failures)} passed, {len(failures)} failed")
    for area, name, status, _ok, detail in failures:
        print(f"  - [{area}] {name}: HTTP {status} {detail}")
    if failures:
        print("\nTypical causes: 403 = the API user's roles lack read permission on that DocType (grant Stock User /"
              " Sales User / Purchase User, or Stock Manager); 417/500 on a report = filters changed"
              " in this ERPNext version; 404 on wmserp_picking.* = custom app not installed, too old, or bench not restarted;"
              " 'Value missing for' = a mandatory field the site added (see the required-field rows above).")
    return 1 if failures else 0


def check_stock_ageing(client, report, company, today):
    """The app sends both filter shapes: `range` ("30, 60, 90", ERPNext v15/v16) and `range1..3` (v14)."""
    filters = {"company": company, "to_date": str(today), "range": ", ".join(str(d) for d in AGEING_RANGES), "show_warehouse_wise_stock": 0}
    for index, days in enumerate(AGEING_RANGES, start=1):
        filters[f"range{index}"] = days
    status, payload, raw = client.run_report("Stock Ageing", filters)
    message = payload.get("message") if isinstance(payload, dict) else None
    ok = status == 200 and isinstance(message, dict) and "result" in message
    if ok:
        columns = [c.get("fieldname") for c in message.get("columns") or [] if isinstance(c, dict)]
        qty_columns = [c for c in columns if c and c.startswith("range") and not c.endswith("value")]
        detail = f"{len(message.get('result') or [])} row(s); quantity columns {qty_columns} (value columns are ignored by the app)"
        if not qty_columns:
            ok = False
            detail = f"no range columns in {columns}: the report filters of this ERPNext version are not understood"
    else:
        detail = server_message(payload, raw)
    report.add("analytics", "query_report.run Stock Ageing", status, ok, detail)


def diagnose_pick_list_creation(client, report, sales_order, insert=False):
    """Reproduces 'Create > Pick List' on a Sales Order: ERPNext maps the Pick List (`create_pick_list`,
    nothing saved), then the required fields of Pick List / Pick List Item (custom ones included) are
    compared with the mapped document. A mandatory field the mapper leaves empty is what makes the desk
    fail with 'Value missing for' / 'required field' errors; `--insert-pick-list` inserts the draft to
    see the exact server message and deletes it again."""
    area = "picklist-create"
    doc = check_method(client, report, area, f"create_pick_list (mapper) for {sales_order}", CREATE_PICK_LIST, params={"source_name": sales_order})
    if not doc:
        print("           the mapper itself fails: this is the error the desk shows when creating the Pick List")
        return
    rows = doc.get("locations") or doc.get("items") or []
    print(f"           mapped Pick List: purpose={doc.get('purpose')} rows={len(rows)} parent_warehouse={doc.get('parent_warehouse')}")
    check_required_fields(client, report, area, doc, "Pick List", child_table="locations")
    if not insert:
        return
    status, payload, raw = client.request("POST", "api/resource/Pick%20List", body=doc)
    created = (payload or {}).get("data", {}).get("name") if status == 200 and isinstance(payload, dict) else None
    report.add(area, "insert mapped Pick List as a draft (writes)", status, bool(created), f"created {created}" if created else server_message(payload, raw))
    if created:
        status, payload, raw = client.request("DELETE", f"api/resource/Pick%20List/{urllib.parse.quote(created, safe='')}")
        report.add(area, f"delete draft {created}", status, status in (200, 202), "" if status in (200, 202) else server_message(payload, raw))


def check_required_fields(client, report, area, doc, doctype, child_table="items"):
    """Required fields of the DocType and its child table (Custom Fields / Property Setters included) that
    the document leaves empty: ERPNext refuses to save it with 'Value missing for'. The app fills them from
    the company's default accounting dimensions or asks the user once (wmserp_picking.documents)."""
    status, payload, raw = client.call(GET_DOCTYPE, {"doctype": doctype})
    metas = payload.get("docs") if isinstance(payload, dict) else None
    if status != 200 or not isinstance(metas, list):
        report.add(area, f"getdoctype {doctype} (required fields)", status, False, server_message(payload, raw))
        return
    required = {}
    for meta in metas:
        for df in meta.get("fields", []):
            if str(df.get("reqd")) in ("1", "True", "true") and df.get("fieldtype") in ANSWERABLE_FIELDTYPES and not df.get("default"):
                required.setdefault(meta.get("name"), []).append(df)
    children = [r for r in doc.get(child_table, []) if isinstance(r, dict)]
    missing = []
    for dt, fields in required.items():
        targets = [doc] if dt == doctype else [r for r in children if r.get("doctype") in (dt, None)]
        if dt != doctype and not any(r.get("doctype") == dt for r in children):
            continue
        for df in fields:
            if any(t.get(df["fieldname"]) in (None, "") for t in targets):
                missing.append(f"{dt}.{df['fieldname']} ({df.get('label')}, {df.get('fieldtype')}{' -> ' + df['options'] if df.get('fieldtype') == 'Link' else ''})")
    status, payload, raw = client.call(GET_DIMENSIONS, {"with_cost_center_and_project": "1"})
    defaults = {}
    if status == 200 and isinstance(payload, dict) and isinstance(payload.get("message"), list) and len(payload["message"]) > 1:
        defaults = (payload["message"][1] or {}).get(doc.get("company"), {}) or {}
    detail = "nothing required beyond what the document carries" if not missing else "empty required field(s): " + "; ".join(missing)
    if defaults:
        detail += f"; default accounting dimensions for {doc.get('company')}: {defaults}"
    report.add(area, f"required fields on {doctype} (site customisations)", 200, True, detail)
    if missing:
        covered = all(m.split(" ")[0].split(".")[1] in defaults for m in missing)
        print("           " + ("the company defaults cover them; wmserp_picking fills them server-side" if covered
                               else "the desk fails on these; the app asks for them once and remembers the answer on the device"))


def run_receive_progress(client, report, receipt):
    """Saves the expected quantity of every row as the progress of a count (submit=0, remove_unreceived=0):
    the draft keeps all its rows and stays at its stage; only the counted quantities are written."""
    rows = receipt.get("items") or []
    counts = [{"row": r["name"], "qty": r.get("qty")} for r in rows if not r.get("needs_batch")]
    skipped = [r["item_code"] for r in rows if r.get("needs_batch")]
    if not counts:
        report.add("receive", "receive (save progress)", 0, True, f"skipped: every row needs a batch ({skipped})")
        return
    body = {"name": receipt["name"], "rows": counts, "submit": 0, "remove_unreceived": 0, "values": {}}
    result = check_method(client, report, "receive", f"receive {receipt['name']} submit=0 (save progress, writes)", RECEIPT_METHOD + "receive", body=body)
    if result:
        if result.get("missing_fields"):
            report.add("receive", "required fields answered by the app", 200, True,
                       "the site requires " + ", ".join(f"{f.get('doctype')}.{f.get('fieldname')}" for f in result["missing_fields"]) + " (the app asks once)")
        else:
            print(f"           saved; submitted={result.get('submitted')} differences={len(result.get('differences') or [])} removed={result.get('removed_rows')}"
                  + (f"; rows needing a batch were skipped: {skipped}" if skipped else ""))


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
    result = check_method(client, report, "picking", "generate_document (last picker)", PICKING_METHOD + "generate_document", body={"name": name, "values": {}})
    if result:
        if result.get("missing_fields"):
            report.add("picking", "generate_document needs required fields", 200, True,
                       "the site requires " + ", ".join(f"{f.get('doctype')}.{f.get('fieldname')}" for f in result["missing_fields"]) + " (the app asks once and retries with `values`)")
            return
        print(f"           {result.get('doctype')} {result.get('name')} already_generated={result.get('already_generated')}")
        again = check_method(client, report, "picking", "generate_document again (duplicate prevention)", PICKING_METHOD + "generate_document", body={"name": name, "values": {}})
        if again and not (again.get("already_generated") and again.get("name") == result.get("name")):
            report.add("picking", "duplicate prevention", 200, False, f"second call returned {again.get('name')} already_generated={again.get('already_generated')}")


if __name__ == "__main__":
    sys.exit(main())
