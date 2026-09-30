"""Stocktaking service: snapshot, counting, assignments, manager review and reconciliation.

Everything the whitelisted API (`wmserp_picking.api.stocktaking`) and the desk forms do lives here.
Pure decisions are delegated to `rules.py`; this module only talks to the database.
"""

import datetime as dt

import frappe
from frappe import _
from frappe.utils import cint, flt, get_datetime, now_datetime, nowdate, nowtime

from wmserp_picking.stocktaking import freeze, rules
from wmserp_picking.wms_erp_picking.doctype.wms_settings.wms_settings import get_qr_keys

SESSION = "Stocktaking Session"
ITEM = "Stocktaking Item"
COUNT = "Stocktaking Count"

ITEM_FIELDS = [
    "name",
    "session",
    "item_code",
    "item_name",
    "warehouse",
    "batch_no",
    "expiry_date",
    "stock_uom",
    "erp_qty",
    "valuation_rate",
    "item_group",
    "brand",
    "has_batch_no",
    "location",
    "added_during_count",
    "status",
    "counter",
    "next_count_type",
    "count_1",
    "count_1_by",
    "count_1_at",
    "count_2",
    "count_2_by",
    "count_2_at",
    "recount_qty",
    "recount_by",
    "recount_at",
    "recount_count",
    "final_qty",
    "qty_difference",
    "value_difference",
    "counted_by",
    "counted_at",
    "recount_requested_by",
    "recount_requested_at",
    "recount_note",
    "reviewed_by",
    "reviewed_at",
    "review_note",
    "modified",
]

ASSIGNABLE_STATUSES = rules.UNCOUNTED_STATUSES + (rules.ITEM_RECOUNT_REQUIRED,)
MAX_PAGE = 2000


# --------------------------------------------------------------------------------------------
# Loading / permissions
# --------------------------------------------------------------------------------------------


def get_session_doc(name):
    doc = frappe.get_doc(SESSION, name)
    doc.check_permission("read")
    return doc


def manager_session(name):
    """A session the current user may manage (write permission: Stock Manager / System Manager)."""
    doc = frappe.get_doc(SESSION, name)
    doc.check_permission("write")
    return doc


def get_row(name):
    return frappe.db.get_value(ITEM, name, ITEM_FIELDS, as_dict=True)


def require_row(name):
    row = get_row(name)
    if not row:
        frappe.throw(_("Stocktaking item {0} does not exist.").format(name))
    return row


def current_roles():
    return frappe.get_roles(frappe.session.user)


def is_blind_for(doc, roles=None) -> bool:
    """Counters do not see ERP quantities in a blind count; managers always do."""
    return bool(cint(doc.blind_count)) and not rules.is_supervisor(roles or current_roles())


def full_names(users) -> dict:
    users = [u for u in set(users or []) if u]
    if not users:
        return {}
    return {u.name: (u.full_name or u.name) for u in frappe.get_all("User", filters={"name": ["in", users]}, fields=["name", "full_name"])}


# --------------------------------------------------------------------------------------------
# Warehouses & snapshot
# --------------------------------------------------------------------------------------------


def leaf_warehouses(warehouse) -> list:
    """The warehouse itself, or every enabled leaf warehouse under a group."""
    info = frappe.db.get_value("Warehouse", warehouse, ["lft", "rgt", "is_group"], as_dict=True)
    if not info:
        frappe.throw(_("Warehouse {0} does not exist.").format(warehouse))
    if not cint(info.is_group):
        return [warehouse]
    return frappe.get_all(
        "Warehouse",
        filters={"lft": [">", info.lft], "rgt": ["<", info.rgt], "is_group": 0, "disabled": 0},
        pluck="name",
        order_by="lft asc",
    )


def item_groups_with_children(group) -> list:
    info = frappe.db.get_value("Item Group", group, ["lft", "rgt"], as_dict=True)
    if not info:
        return [group]
    return frappe.get_all("Item Group", filters={"lft": [">=", info.lft], "rgt": ["<=", info.rgt]}, pluck="name")


def batch_balances(warehouses) -> dict:
    """{(item_code, warehouse, batch_no): qty} for the batches held in `warehouses`.

    Works on v14 (batch_no on the Stock Ledger Entry) and v15/v16 (Serial and Batch Bundle) by
    summing legacy entries and bundle entries separately, so no entry is counted twice.
    """
    balances = {}
    if not warehouses:
        return balances
    has_bundle_column = frappe.db.has_column("Stock Ledger Entry", "serial_and_batch_bundle")
    legacy_condition = "and ifnull(sle.serial_and_batch_bundle, '') = ''" if has_bundle_column else ""
    legacy = frappe.db.sql(
        f"""
        select sle.item_code, sle.warehouse, sle.batch_no, sum(sle.actual_qty) as qty
        from `tabStock Ledger Entry` sle
        where sle.warehouse in %(warehouses)s and sle.is_cancelled = 0 and ifnull(sle.batch_no, '') != '' {legacy_condition}
        group by sle.item_code, sle.warehouse, sle.batch_no
        """,
        {"warehouses": warehouses},
        as_dict=True,
    )
    for row in legacy:
        balances[(row.item_code, row.warehouse, row.batch_no)] = balances.get((row.item_code, row.warehouse, row.batch_no), 0.0) + flt(row.qty)
    if has_bundle_column and frappe.db.table_exists("Serial and Batch Entry"):
        bundled = frappe.db.sql(
            """
            select sle.item_code, sle.warehouse, sbe.batch_no, sum(sbe.qty) as qty
            from `tabStock Ledger Entry` sle
            inner join `tabSerial and Batch Entry` sbe on sbe.parent = sle.serial_and_batch_bundle
            where sle.warehouse in %(warehouses)s and sle.is_cancelled = 0
              and ifnull(sle.serial_and_batch_bundle, '') != '' and ifnull(sbe.batch_no, '') != ''
            group by sle.item_code, sle.warehouse, sbe.batch_no
            """,
            {"warehouses": warehouses},
            as_dict=True,
        )
        for row in bundled:
            key = (row.item_code, row.warehouse, row.batch_no)
            balances[key] = balances.get(key, 0.0) + flt(row.qty)
    return balances


def batch_expiries(batch_nos) -> dict:
    batch_nos = [b for b in set(batch_nos or []) if b]
    if not batch_nos:
        return {}
    return {b.name: b.expiry_date for b in frappe.get_all("Batch", filters={"name": ["in", batch_nos]}, fields=["name", "expiry_date"])}


def build_snapshot(doc) -> list:
    """ERP quantities of every item / batch in the session's warehouse(s), as row dicts."""
    warehouses = leaf_warehouses(doc.warehouse)
    if not warehouses:
        frappe.throw(_("Warehouse {0} has no enabled leaf warehouses.").format(doc.warehouse))
    filters = {"warehouse": ["in", warehouses]}
    if not cint(doc.include_zero_stock):
        filters["actual_qty"] = ["!=", 0]
    bins = frappe.get_all("Bin", filters=filters, fields=["item_code", "warehouse", "actual_qty", "valuation_rate", "stock_uom"], limit_page_length=0)
    codes = sorted({b.item_code for b in bins if b.item_code})
    if not codes:
        return []
    item_filters = {"name": ["in", codes], "is_stock_item": 1}
    if doc.item_group:
        item_filters["item_group"] = ["in", item_groups_with_children(doc.item_group)]
    if doc.brand:
        item_filters["brand"] = doc.brand
    items = {
        i.name: i
        for i in frappe.get_all("Item", filters=item_filters, fields=["name", "item_name", "item_group", "brand", "has_batch_no", "stock_uom"], limit_page_length=0)
    }
    batch_items = {code for code, info in items.items() if cint(info.has_batch_no)}
    balances = batch_balances(warehouses) if batch_items else {}
    by_item_wh = {}
    for (item_code, warehouse, batch_no), qty in balances.items():
        by_item_wh.setdefault((item_code, warehouse), {})[batch_no] = qty
    expiries = batch_expiries([key[2] for key in balances])

    rows = []
    for b in bins:
        info = items.get(b.item_code)
        if not info:
            continue
        base = {
            "item_code": b.item_code,
            "item_name": info.item_name or b.item_code,
            "warehouse": b.warehouse,
            "stock_uom": b.stock_uom or info.stock_uom,
            "valuation_rate": flt(b.valuation_rate),
            "item_group": info.item_group,
            "brand": info.brand,
            "has_batch_no": cint(info.has_batch_no),
        }
        if b.item_code in batch_items:
            batches = by_item_wh.get((b.item_code, b.warehouse), {})
            total = 0.0
            for batch_no, qty in sorted(batches.items()):
                total += flt(qty)
                if not cint(doc.include_zero_stock) and abs(flt(qty)) <= rules.QTY_TOLERANCE:
                    continue
                rows.append(dict(base, batch_no=batch_no, expiry_date=expiries.get(batch_no), erp_qty=flt(qty)))
            remainder = flt(b.actual_qty) - total
            if abs(remainder) > rules.QTY_TOLERANCE or (not batches and cint(doc.include_zero_stock)):
                # Stock that is not attributed to any batch (legacy entries): counted as a "no batch" row.
                rows.append(dict(base, batch_no=None, expiry_date=None, erp_qty=remainder))
        else:
            rows.append(dict(base, batch_no=None, expiry_date=None, erp_qty=flt(b.actual_qty)))
    return rows


def insert_rows(doc, rows) -> int:
    """Bulk-inserts snapshot rows (thousands of rows must not take minutes)."""
    if not rows:
        return 0
    now = now_datetime()
    user = frappe.session.user
    existing = {
        rules.snapshot_key(r.item_code, r.warehouse, r.batch_no)
        for r in frappe.get_all(ITEM, filters={"session": doc.name}, fields=["item_code", "warehouse", "batch_no"], limit_page_length=0)
    }
    fields = [
        "name", "creation", "modified", "owner", "modified_by", "docstatus", "idx",
        "session", "item_code", "item_name", "warehouse", "batch_no", "expiry_date", "stock_uom", "erp_qty", "valuation_rate",
        "item_group", "brand", "has_batch_no", "status", "next_count_type", "counter", "qty_difference", "value_difference",
        "recount_count", "added_during_count",
    ]
    values = []
    for row in rows:
        if rules.snapshot_key(row["item_code"], row["warehouse"], row.get("batch_no")) in existing:
            continue
        values.append(
            [
                frappe.generate_hash(length=10), now, now, user, user, 0, 0,
                doc.name, row["item_code"], row.get("item_name"), row["warehouse"], row.get("batch_no"), row.get("expiry_date"),
                row.get("stock_uom"), flt(row.get("erp_qty")), flt(row.get("valuation_rate")),
                row.get("item_group"), row.get("brand"), cint(row.get("has_batch_no")), rules.ITEM_NOT_COUNTED, rules.COUNT_1, None, 0.0, 0.0,
                0, 0,
            ]
        )
    if not values:
        return 0
    try:
        frappe.db.bulk_insert(ITEM, fields=fields, values=values, chunk_size=1000)
    except Exception:
        # Older Frappe without bulk_insert keyword support: insert one by one.
        for value in values:
            record = dict(zip(fields, value))
            record.pop("name", None)
            record["doctype"] = ITEM
            frappe.get_doc(record).insert(ignore_permissions=True)
    return len(values)


def current_erp_qty(item_code, warehouse, batch_no=None) -> float:
    """ERP quantity right now (the warehouse is frozen, so it equals the snapshot moment)."""
    if batch_no:
        try:
            from erpnext.stock.doctype.batch.batch import get_batch_qty

            qty = get_batch_qty(batch_no=batch_no, warehouse=warehouse)
            if isinstance(qty, (int, float)):
                return flt(qty)
        except Exception:
            pass
        balances = batch_balances([warehouse])
        return flt(balances.get((item_code, warehouse, batch_no), 0.0))
    return flt(frappe.db.get_value("Bin", {"item_code": item_code, "warehouse": warehouse}, "actual_qty"))


# --------------------------------------------------------------------------------------------
# Session lifecycle (manager)
# --------------------------------------------------------------------------------------------


def conflicting_session(doc):
    """Another frozen session covering (part of) the same warehouses."""
    mine = set(leaf_warehouses(doc.warehouse))
    for other in frappe.get_all(
        SESSION,
        filters={"name": ["!=", doc.name], "frozen": 1, "status": ["in", list(rules.FROZEN_SESSION_STATUSES)]},
        fields=["name", "warehouse"],
    ):
        if mine & set(leaf_warehouses(other.warehouse)):
            return other
    return None


def start_session(name):
    """Draft -> Counting: snapshot the ERP quantities and freeze the warehouse."""
    doc = manager_session(name)
    if doc.status != rules.SESSION_DRAFT:
        frappe.throw(_("Session {0} is already {1}.").format(name, _(doc.status)))
    if cint(doc.freeze_warehouse):
        other = conflicting_session(doc)
        if other:
            frappe.throw(_("Warehouse {0} is already frozen by session {1}.").format(other.warehouse, other.name))
    inserted = insert_rows(doc, build_snapshot(doc))
    if frappe.db.count(ITEM, {"session": name}) == 0:
        frappe.throw(_("Warehouse {0} holds no stock to count (enable Include Zero Stock to count empty bins).").format(doc.warehouse))
    now = now_datetime()
    doc.db_set(
        {
            "status": rules.SESSION_COUNTING,
            "frozen": 1 if cint(doc.freeze_warehouse) else 0,
            "started_at": now,
            "started_by": frappe.session.user,
        }
    )
    freeze.invalidate()
    _assign_open_rows_status(doc)
    totals = doc.refresh_totals()
    doc.add_comment("Info", _("Stocktaking started: {0} item/batch rows snapshotted, warehouse {1}.").format(inserted, _("frozen") if doc.frozen else _("not frozen")))
    return serialize_session(doc, totals=totals)


def _assign_open_rows_status(doc):
    """Rows that already have a counter (pre-assigned in Draft) are marked Assigned."""
    frappe.db.sql(
        "update `tabStocktaking Item` set status=%s where session=%s and status=%s and ifnull(counter, '') != ''",
        (rules.ITEM_ASSIGNED, doc.name, rules.ITEM_NOT_COUNTED),
    )


def complete_counting(name):
    doc = manager_session(name)
    if doc.status not in rules.COUNTING_SESSION_STATUSES:
        frappe.throw(_("Session {0} is {1}; counting cannot be closed.").format(name, _(doc.status)))
    totals = doc.totals()
    try:
        new_status = rules.complete_counting_status(totals)
    except rules.StocktakingRuleError as exc:
        frappe.throw(_("Counting cannot be closed: {0}.").format(_(str(exc))))
    doc.db_set({"status": new_status, "counting_completed_at": now_datetime()})
    freeze.invalidate()
    doc.add_comment("Info", _("Counting closed by {0}: {1} counted, {2} with variance, {3} pending review.").format(
        frappe.utils.get_fullname(frappe.session.user), totals["counted_items"], totals["variance_items"], totals["pending_review"]
    ))
    return serialize_session(doc, totals=doc.refresh_totals())


def approve_session(name):
    doc = manager_session(name)
    totals = doc.totals()
    try:
        rules.ready_for_approval(doc.status, totals)
    except rules.StocktakingRuleError as exc:
        frappe.throw(_(str(exc)))
    doc.db_set({"status": rules.SESSION_FINAL_APPROVAL, "approved_at": now_datetime(), "approved_by": frappe.session.user})
    if not doc.counting_completed_at:
        doc.db_set("counting_completed_at", now_datetime())
    freeze.invalidate()
    doc.add_comment("Info", _("Final approval by {0}.").format(frappe.utils.get_fullname(frappe.session.user)))
    return serialize_session(doc, totals=doc.refresh_totals())


def create_reconciliation(name, submit=False):
    """Final Approval -> Reconciled: one draft Stock Reconciliation with every approved difference."""
    doc = manager_session(name)
    if doc.stock_reconciliation and cint(frappe.db.get_value("Stock Reconciliation", doc.stock_reconciliation, "docstatus")) != 2:
        return {
            "stock_reconciliation": doc.stock_reconciliation,
            "docstatus": cint(frappe.db.get_value("Stock Reconciliation", doc.stock_reconciliation, "docstatus")),
            "already_created": True,
            "completed": doc.status == rules.SESSION_COMPLETED,
            "session": serialize_session(doc),
        }
    if not rules.can_reconcile(doc.status):
        frappe.throw(_("Approve the session (Final Approval) before creating the Stock Reconciliation."))
    rows = frappe.get_all(ITEM, filters={"session": name}, fields=ITEM_FIELDS, limit_page_length=0)
    try:
        diff_rows = rules.reconciliation_rows(rows, doc.qty_tolerance)
    except rules.StocktakingRuleError as exc:
        frappe.throw(_(str(exc)))
    if not diff_rows:
        finalize_rows(name)
        mark_completed(doc, by=frappe.session.user, note=_("No differences: no Stock Reconciliation needed."))
        return {"stock_reconciliation": None, "docstatus": None, "already_created": False, "completed": True, "session": serialize_session(doc)}

    reconciliation = frappe.new_doc("Stock Reconciliation")
    reconciliation.purpose = "Stock Reconciliation"
    reconciliation.company = doc.company
    reconciliation.posting_date = nowdate()
    reconciliation.posting_time = nowtime()
    set_if_field(reconciliation, "set_posting_time", 1)
    if not cint(frappe.db.get_value("Warehouse", doc.warehouse, "is_group")):
        set_if_field(reconciliation, "set_warehouse", doc.warehouse)
    set_if_field(reconciliation, "custom_stocktaking_session", name)
    for row in diff_rows:
        item = reconciliation.append("items", {"item_code": row.item_code, "warehouse": row.warehouse, "qty": flt(row.final_qty)})
        if flt(row.valuation_rate) > 0:
            item.valuation_rate = flt(row.valuation_rate)
        if row.batch_no:
            item.batch_no = row.batch_no
            set_if_field(item, "use_serial_batch_fields", 1)
    reconciliation.insert()
    doc.db_set({"stock_reconciliation": reconciliation.name, "status": rules.SESSION_RECONCILED, "reconciled_at": now_datetime()})
    finalize_rows(name)
    freeze.invalidate()
    doc.add_comment("Info", _("Stock Reconciliation {0} created with {1} row(s).").format(reconciliation.name, len(diff_rows)))
    if cint(submit):
        reconciliation.submit()
        doc = frappe.get_doc(SESSION, name)
        if doc.status != rules.SESSION_COMPLETED:
            mark_completed(doc, by=frappe.session.user)
    return {
        "stock_reconciliation": reconciliation.name,
        "docstatus": reconciliation.docstatus,
        "already_created": False,
        "completed": doc.status == rules.SESSION_COMPLETED,
        "session": serialize_session(frappe.get_doc(SESSION, name)),
    }


def finalize_rows(name):
    frappe.db.sql(
        "update `tabStocktaking Item` set status=%s where session=%s and status in %s",
        (rules.ITEM_FINALIZED, name, (rules.ITEM_COUNTED, rules.ITEM_APPROVED)),
    )


def complete_session(name):
    """Reconciled -> Completed once the Stock Reconciliation is submitted."""
    doc = manager_session(name)
    if doc.status == rules.SESSION_COMPLETED:
        return serialize_session(doc)
    if doc.status != rules.SESSION_RECONCILED:
        frappe.throw(_("Create the Stock Reconciliation before completing the session."))
    if cint(frappe.db.get_value("Stock Reconciliation", doc.stock_reconciliation, "docstatus")) != 1:
        frappe.throw(_("Submit Stock Reconciliation {0} first; the warehouse stays frozen until then.").format(doc.stock_reconciliation))
    mark_completed(doc, by=frappe.session.user)
    return serialize_session(doc)


def mark_completed(doc, by=None, note=None):
    doc.db_set({"status": rules.SESSION_COMPLETED, "frozen": 0, "completed_at": now_datetime(), "completed_by": by or frappe.session.user})
    freeze.invalidate()
    doc.add_comment("Info", note or _("Stocktaking completed; warehouse {0} unfrozen.").format(doc.warehouse))
    doc.refresh_totals()


def cancel_session(name, reason=None):
    doc = manager_session(name)
    docstatus = cint(frappe.db.get_value("Stock Reconciliation", doc.stock_reconciliation, "docstatus")) if doc.stock_reconciliation else None
    try:
        rules.can_cancel(doc.status, docstatus)
    except rules.StocktakingRuleError as exc:
        frappe.throw(_(str(exc)))
    updates = {"status": rules.SESSION_CANCELLED, "frozen": 0, "cancelled_at": now_datetime()}
    if reason:
        updates["remarks"] = ((doc.remarks or "") + "\n" + reason).strip()
    doc.db_set(updates)
    freeze.invalidate()
    doc.add_comment("Info", _("Stocktaking cancelled by {0}.").format(frappe.utils.get_fullname(frappe.session.user)))
    return serialize_session(doc)


# --------------------------------------------------------------------------------------------
# Assignments (manager)
# --------------------------------------------------------------------------------------------


def assignment_filters(name, filters=None) -> dict:
    """Turns the Assign Items dialog filters into `frappe.get_all` filters on assignable rows."""
    filters = frappe.parse_json(filters) if isinstance(filters, str) else (filters or {})
    out = {"session": name, "status": ["in", list(ASSIGNABLE_STATUSES)]}
    if filters.get("item_group"):
        out["item_group"] = ["in", item_groups_with_children(filters["item_group"])]
    for key in ("brand", "batch_no", "item_code", "warehouse"):
        if filters.get(key):
            out[key] = filters[key]
    if filters.get("location"):
        out["location"] = ["like", f"%{filters['location']}%"]
    if filters.get("item_name"):
        out["item_name"] = ["like", f"%{filters['item_name']}%"]
    if filters.get("counter") == "":
        out["counter"] = ["is", "not set"]
    elif filters.get("counter"):
        out["counter"] = filters["counter"]
    if filters.get("status"):
        wanted = filters["status"] if isinstance(filters["status"], list) else [filters["status"]]
        out["status"] = ["in", [s for s in wanted if s in ASSIGNABLE_STATUSES] or list(ASSIGNABLE_STATUSES)]
    return out


def assign_items(name, user=None, items=None, filters=None, unassign=False, area=None):
    """Assigns (or unassigns) many rows at once: an explicit list of row names, or every
    assignable row that matches the dialog filters (item group, brand, batch, location...)."""
    doc = manager_session(name)
    if doc.status in rules.CLOSED_SESSION_STATUSES or doc.status in (rules.SESSION_RECONCILED,):
        frappe.throw(_("Session {0} is {1}; assignments are closed.").format(name, _(doc.status)))
    unassign = cint(unassign)
    if not unassign:
        if not user:
            frappe.throw(_("Choose the counter to assign the items to."))
        if not frappe.db.exists("User", user):
            frappe.throw(_("User {0} does not exist.").format(user))
        if not cint(frappe.db.get_value("User", user, "enabled")):
            frappe.throw(_("User {0} is disabled.").format(user))
    items = frappe.parse_json(items) if isinstance(items, str) else items
    if items:
        names = [n for n in items if n]
        rows = frappe.get_all(ITEM, filters={"name": ["in", names], "session": name, "status": ["in", list(ASSIGNABLE_STATUSES)]}, pluck="name")
    else:
        rows = frappe.get_all(ITEM, filters=assignment_filters(name, filters), pluck="name", limit_page_length=0)
    if not rows:
        return {"assigned": 0, "user": None if unassign else user}
    now = now_datetime()
    modified_by = frappe.session.user
    for start in range(0, len(rows), 500):
        chunk = rows[start : start + 500]
        if unassign:
            frappe.db.sql(
                """update `tabStocktaking Item`
                   set counter = NULL, status = case when status = %s then %s else status end, modified = %s, modified_by = %s
                   where name in %s""",
                (rules.ITEM_ASSIGNED, rules.ITEM_NOT_COUNTED, now, modified_by, tuple(chunk)),
            )
        else:
            frappe.db.sql(
                """update `tabStocktaking Item`
                   set counter = %s, status = case when status in %s then %s else status end, modified = %s, modified_by = %s
                   where name in %s""",
                (user, (rules.ITEM_NOT_COUNTED, rules.ITEM_ASSIGNED), rules.ITEM_ASSIGNED, now, modified_by, tuple(chunk)),
            )
    if not unassign:
        doc.add_counter(user, area=area)
    doc.refresh_totals()
    doc.add_comment("Info", _("{0} item(s) {1} {2}.").format(len(rows), _("unassigned from") if unassign else _("assigned to"), user or ""))
    return {"assigned": len(rows), "user": None if unassign else user}


# --------------------------------------------------------------------------------------------
# Counting (app)
# --------------------------------------------------------------------------------------------


def resolve_row(doc, item=None, item_code=None, batch_no=None, warehouse=None, create=False, user=None):
    if item:
        row = get_row(item)
        if not row or row.session != doc.name:
            frappe.throw(_("Row {0} does not belong to session {1}.").format(item, doc.name))
        return row
    item_code = (item_code or "").strip()
    if not item_code:
        frappe.throw(_("Item is required."))
    batch_no = (batch_no or "").strip() or None
    filters = {"session": doc.name, "item_code": item_code, "batch_no": batch_no or ["is", "not set"]}
    if warehouse:
        filters["warehouse"] = warehouse
    rows = frappe.get_all(ITEM, filters=filters, fields=ITEM_FIELDS)
    if len(rows) == 1:
        return rows[0]
    if len(rows) > 1:
        frappe.throw(_("{0} is held in several warehouses of this session; the warehouse is required.").format(item_code))
    if not create:
        return None
    return add_row(doc, item_code, batch_no, warehouse, user)


def add_row(doc, item_code, batch_no, warehouse, user):
    """A counter found stock that the snapshot did not list: add the row with the current ERP quantity."""
    info = frappe.db.get_value("Item", item_code, ["name", "item_name", "item_group", "brand", "has_batch_no", "stock_uom", "is_stock_item", "disabled"], as_dict=True)
    if not info:
        frappe.throw(_("Item {0} does not exist in ERPNext.").format(item_code))
    if not cint(info.is_stock_item):
        frappe.throw(_("{0} is not a stock item.").format(item_code))
    expiry = None
    if batch_no:
        batch = frappe.db.get_value("Batch", batch_no, ["item", "expiry_date"], as_dict=True)
        if not batch:
            frappe.throw(_("Batch {0} does not exist in ERPNext.").format(batch_no))
        if batch.item != info.name:
            frappe.throw(_("Batch {0} belongs to item {1}, not {2}.").format(batch_no, batch.item, info.name))
        expiry = batch.expiry_date
    elif cint(info.has_batch_no):
        frappe.throw(_("{0} is batch-tracked: scan the batch label (QR) of the units you found.").format(info.name))
    warehouses = leaf_warehouses(doc.warehouse)
    if warehouse:
        if warehouse not in warehouses:
            frappe.throw(_("Warehouse {0} is not part of session {1}.").format(warehouse, doc.name))
    elif len(warehouses) == 1:
        warehouse = warehouses[0]
    else:
        frappe.throw(_("Session {0} covers several warehouses; the warehouse of the item is required.").format(doc.name))
    rate = frappe.db.get_value("Bin", {"item_code": item_code, "warehouse": warehouse}, "valuation_rate") or frappe.db.get_value("Item", item_code, "valuation_rate") or 0
    assigned = doc.counting_mode == rules.MODE_ASSIGNED
    row = frappe.get_doc(
        {
            "doctype": ITEM,
            "session": doc.name,
            "item_code": info.name,
            "item_name": info.item_name,
            "warehouse": warehouse,
            "batch_no": batch_no,
            "expiry_date": expiry,
            "stock_uom": info.stock_uom,
            "erp_qty": current_erp_qty(info.name, warehouse, batch_no),
            "valuation_rate": flt(rate),
            "item_group": info.item_group,
            "brand": info.brand,
            "has_batch_no": cint(info.has_batch_no),
            "added_during_count": 1,
            "status": rules.ITEM_ASSIGNED if assigned else rules.ITEM_NOT_COUNTED,
            "counter": user if assigned else None,
        }
    )
    row.flags.ignore_permissions = True
    row.insert()
    return get_row(row.name)


def parse_client_time(value):
    if value in (None, ""):
        return None
    try:
        return get_datetime(value)
    except Exception:
        return None


def rejection(doc, row, reason, message, user, totals=None, **extra):
    out = {
        "outcome": "rejected",
        "reason": reason,
        "message": message,
        "item": serialize_item(row, user, is_blind_for(doc)) if row else None,
        "totals": totals or doc.totals(),
        "session_status": doc.status,
    }
    out.update(extra)
    return out


def submit_count(name, qty, item=None, item_code=None, batch_no=None, warehouse=None, client_ref=None, device_time=None, note=None, source="App"):
    """Records one physical count and applies the count state machine (see rules.evaluate_count).

    Returns `{"outcome": ..., "item": ..., "count": ..., "totals": ..., "session_status": ...}`.
    Rejections that the app must show (already counted, assigned to someone else, counting closed)
    come back with `outcome: "rejected"` and a `reason` instead of an HTTP error, so that an
    offline queue can be replayed entry by entry. `client_ref` makes a replay idempotent.
    """
    doc = get_session_doc(name)
    user = frappe.session.user
    roles = current_roles()
    client_ref = (client_ref or "").strip() or None

    if client_ref:
        existing = frappe.db.get_value(COUNT, {"client_ref": client_ref}, ["name", "stocktaking_item", "outcome"], as_dict=True)
        if existing:
            row = get_row(existing.stocktaking_item)
            return count_response(doc, row, rules.OUTCOME_DUPLICATE, existing.name, user, replayed_outcome=existing.outcome)

    try:
        qty = rules.validate_count_qty(qty)
    except rules.StocktakingRuleError as exc:
        frappe.throw(_(str(exc)))

    if doc.status not in rules.COUNTING_SESSION_STATUSES:
        return rejection(doc, None, "session_closed", _("Counting is closed: session {0} is {1}.").format(name, _(doc.status)), user)

    supervisor = rules.is_supervisor(roles)
    if doc.counting_mode == rules.MODE_OPEN and not supervisor and user not in doc.counter_users:
        doc.add_counter(user)

    row = resolve_row(doc, item, item_code, batch_no, warehouse, create=True, user=user)
    frappe.db.get_value(ITEM, row.name, "name", for_update=True)
    row = get_row(row.name)

    policy = doc.duplicate_count_policy or rules.POLICY_LOCK
    try:
        rules.can_count(doc.status, doc.counting_mode, row.status, row.counter, user, roles, doc.counter_users, policy=policy, counted_by=row.counted_by)
    except rules.AlreadyCountedError as exc:
        names = full_names([exc.counted_by])
        who = names.get(exc.counted_by, exc.counted_by) if exc.counted_by else _("someone else")
        return rejection(doc, row, "already_counted", _("{0} has already been counted by {1}.").format(row.item_code, who), user, counted_by=exc.counted_by, counted_by_name=names.get(exc.counted_by))
    except rules.NotAssignedError as exc:
        names = full_names([exc.counter])
        message = (
            _("{0} is assigned to {1}.").format(row.item_code, names.get(exc.counter, exc.counter))
            if exc.counter
            else _("{0} is not assigned to you.").format(row.item_code)
        )
        return rejection(doc, row, "not_assigned", message, user, counter=exc.counter, counter_name=names.get(exc.counter))
    except rules.StocktakingRuleError as exc:
        return rejection(doc, row, "finalized" if row.status == rules.ITEM_FINALIZED else "session_closed", _(str(exc)), user)

    try:
        result = rules.evaluate_count(
            row.status,
            row.next_count_type,
            qty,
            row.erp_qty,
            tolerance=doc.qty_tolerance,
            require_second_count=bool(cint(doc.require_second_count)),
            policy=policy,
            counted_by=row.counted_by,
        )
    except rules.AlreadyCountedError as exc:
        names = full_names([exc.counted_by])
        who = names.get(exc.counted_by, exc.counted_by) if exc.counted_by else _("someone else")
        return rejection(doc, row, "already_counted", _("{0} has already been counted by {1}.").format(row.item_code, who), user, counted_by=exc.counted_by, counted_by_name=names.get(exc.counted_by))
    except rules.StocktakingRuleError as exc:
        frappe.throw(_(str(exc)))

    now = now_datetime()
    count = frappe.get_doc(
        {
            "doctype": COUNT,
            "session": name,
            "stocktaking_item": row.name,
            "item_code": row.item_code,
            "item_name": row.item_name,
            "warehouse": row.warehouse,
            "batch_no": row.batch_no,
            "count_type": result["count_type"],
            "qty": qty,
            "erp_qty": flt(row.erp_qty),
            "outcome": result["outcome"],
            "counted_by": user,
            "counted_at": now,
            "device_time": parse_client_time(device_time),
            "source": source if source in ("App", "Desk") else "App",
            "client_ref": client_ref,
            "note": note,
        }
    )
    count.flags.ignore_permissions = True
    count.insert()

    updates = {
        "status": result["status"],
        "next_count_type": result["next_count_type"],
        "counted_by": user,
        "counted_at": now,
    }
    if result["count_type"] == rules.COUNT_1:
        updates.update({"count_1": qty, "count_1_by": user, "count_1_at": now})
    elif result["count_type"] == rules.COUNT_2:
        updates.update({"count_2": qty, "count_2_by": user, "count_2_at": now})
    else:
        updates.update({"recount_qty": qty, "recount_by": user, "recount_at": now, "recount_count": cint(row.recount_count) + 1})
    if result["final_qty"] is None:
        updates.update({"final_qty": None, "qty_difference": 0.0, "value_difference": 0.0})
    else:
        diff, value = rules.differences(result["final_qty"], row.erp_qty, row.valuation_rate)
        updates.update({"final_qty": result["final_qty"], "qty_difference": diff, "value_difference": value})
    if doc.counting_mode == rules.MODE_ASSIGNED and not row.counter:
        updates["counter"] = user
    frappe.db.set_value(ITEM, row.name, updates)

    totals = doc.refresh_totals()
    new_status = rules.session_status_after_count(doc.status, totals["recount_required"])
    if new_status != doc.status:
        doc.db_set("status", new_status)
        freeze.invalidate()
    return count_response(doc, get_row(row.name), result["outcome"], count.name, user, totals=totals)


def count_response(doc, row, outcome, count_name, user, totals=None, **extra):
    out = {
        "outcome": outcome,
        "item": serialize_item(row, user, is_blind_for(doc)),
        "count": count_name,
        "totals": totals or doc.totals(),
        "session_status": doc.status,
    }
    out.update(extra)
    return out


def sync_counts(name, counts):
    """Replays a queue of counts recorded offline, one savepoint per entry, in the given order."""
    counts = frappe.parse_json(counts) if isinstance(counts, str) else (counts or [])
    results = []
    for entry in counts:
        entry = entry or {}
        client_ref = entry.get("client_ref")
        savepoint = "wms_stocktaking_count"
        frappe.db.savepoint(savepoint)
        try:
            result = submit_count(
                name,
                entry.get("qty"),
                item=entry.get("item"),
                item_code=entry.get("item_code"),
                batch_no=entry.get("batch_no"),
                warehouse=entry.get("warehouse"),
                client_ref=client_ref,
                device_time=entry.get("device_time"),
                note=entry.get("note"),
                source=entry.get("source") or "App",
            )
            result["client_ref"] = client_ref
            results.append(result)
        except frappe.ValidationError as exc:
            frappe.db.rollback(save_point=savepoint)
            frappe.clear_last_message()
            results.append({"client_ref": client_ref, "outcome": "error", "reason": "validation", "message": str(exc)})
        except frappe.PermissionError as exc:
            frappe.db.rollback(save_point=savepoint)
            frappe.clear_last_message()
            results.append({"client_ref": client_ref, "outcome": "error", "reason": "permission", "message": str(exc)})
    doc = get_session_doc(name)
    return {"results": results, "totals": doc.totals(), "session_status": doc.status}


def lookup(name, code, warehouse=None):
    """Identifies a scan (JSON QR label, item barcode, item code or batch number) inside a session."""
    doc = get_session_doc(name)
    keys = get_qr_keys()
    try:
        scan = rules.resolve_scan(code, keys["qr_item_key"], keys["qr_batch_key"])
    except rules.StocktakingRuleError as exc:
        frappe.throw(_(str(exc)))
    item_code, batch_no = scan["item_code"], scan["batch_no"]
    if scan["kind"] == "barcode":
        raw = scan["raw"]
        item_code = frappe.db.get_value("Item Barcode", {"barcode": raw}, "parent")
        if not item_code and frappe.db.exists("Item", raw):
            item_code = raw
        if not item_code:
            batch = frappe.db.get_value("Batch", raw, ["name", "item"], as_dict=True)
            if batch:
                item_code, batch_no = batch.item, batch.name
    if not item_code:
        return {"found": False, "raw": scan["raw"], "item_code": None, "batch_no": None, "rows": [], "in_session": False}
    info = frappe.db.get_value("Item", item_code, ["name", "item_name", "has_batch_no", "is_stock_item", "stock_uom"], as_dict=True)
    if not info:
        return {"found": False, "raw": scan["raw"], "item_code": item_code, "batch_no": batch_no, "rows": [], "in_session": False}
    filters = {"session": name, "item_code": info.name}
    if batch_no:
        filters["batch_no"] = batch_no
    if warehouse:
        filters["warehouse"] = warehouse
    rows = frappe.get_all(ITEM, filters=filters, fields=ITEM_FIELDS, order_by="batch_no asc")
    blind = is_blind_for(doc)
    names = full_names([r.counter for r in rows] + [r.counted_by for r in rows])
    expiry = frappe.db.get_value("Batch", batch_no, "expiry_date") if batch_no else None
    return {
        "found": True,
        "raw": scan["raw"],
        "kind": scan["kind"],
        "item_code": info.name,
        "item_name": info.item_name,
        "batch_no": batch_no,
        "expiry_date": str(expiry) if expiry else None,
        "has_batch_no": bool(cint(info.has_batch_no)),
        "stock_uom": info.stock_uom,
        "can_add": bool(cint(info.is_stock_item)) and (bool(batch_no) or not cint(info.has_batch_no)),
        "in_session": bool(rows),
        "rows": [serialize_item(r, frappe.session.user, blind, names) for r in rows],
    }


def start_counting_item(item):
    """Marks a row as Counting (the counter opened it); purely informational for the manager."""
    row = require_row(item)
    doc = get_session_doc(row.session)
    if row.status in (rules.ITEM_NOT_COUNTED, rules.ITEM_ASSIGNED) and doc.status in rules.COUNTING_SESSION_STATUSES:
        frappe.db.set_value(ITEM, item, {"status": rules.ITEM_COUNTING}, update_modified=False)
    return serialize_item(get_row(item), frappe.session.user, is_blind_for(doc))


# --------------------------------------------------------------------------------------------
# Manager review
# --------------------------------------------------------------------------------------------


def request_recount(item, note=None):
    row = require_row(item)
    doc = manager_session(row.session)
    if doc.status in rules.CLOSED_SESSION_STATUSES or doc.status == rules.SESSION_RECONCILED:
        frappe.throw(_("Session {0} is {1}; recounts are closed.").format(doc.name, _(doc.status)))
    if not rules.can_request_recount(row.status):
        frappe.throw(_("A recount cannot be requested for a row that is {0}.").format(_(row.status)))
    frappe.db.set_value(
        ITEM,
        item,
        {
            "status": rules.ITEM_RECOUNT_REQUIRED,
            "next_count_type": rules.RECOUNT,
            "recount_requested_by": frappe.session.user,
            "recount_requested_at": now_datetime(),
            "recount_note": note,
        },
    )
    totals = doc.refresh_totals()
    new_status = rules.session_status_after_recount_request(doc.status)
    if new_status != doc.status:
        doc.db_set("status", new_status)
        freeze.invalidate()
    return serialize_item(get_row(item), frappe.session.user, False)


def accept_item(item, note=None):
    row = require_row(item)
    doc = manager_session(row.session)
    if not rules.can_accept(row.status):
        frappe.throw(_("Row {0} is {1} and has no count to accept.").format(row.item_code, _(row.status)))
    if row.final_qty in (None, ""):
        frappe.throw(_("Row {0} has no final quantity yet.").format(row.item_code))
    frappe.db.set_value(ITEM, item, {"status": rules.ITEM_APPROVED, "reviewed_by": frappe.session.user, "reviewed_at": now_datetime(), "review_note": note})
    doc.refresh_totals()
    return serialize_item(get_row(item), frappe.session.user, False)


def accept_all(name, note=None):
    """Approves every row that waits for manager review (their latest count becomes final)."""
    doc = manager_session(name)
    rows = frappe.get_all(ITEM, filters={"session": name, "status": ["in", list(rules.PENDING_REVIEW_STATUSES)]}, pluck="name", limit_page_length=0)
    now = now_datetime()
    for start in range(0, len(rows), 500):
        chunk = tuple(rows[start : start + 500])
        frappe.db.sql(
            "update `tabStocktaking Item` set status=%s, reviewed_by=%s, reviewed_at=%s, review_note=%s, modified=%s, modified_by=%s where name in %s",
            (rules.ITEM_APPROVED, frappe.session.user, now, note, now, frappe.session.user, chunk),
        )
    totals = doc.refresh_totals()
    doc.add_comment("Info", _("{0} variance(s) accepted by {1}.").format(len(rows), frappe.utils.get_fullname(frappe.session.user)))
    return {"accepted": len(rows), "totals": totals}


def item_history(item):
    row = require_row(item)
    doc = get_session_doc(row.session)
    counts = frappe.get_all(
        COUNT,
        filters={"stocktaking_item": item},
        fields=["name", "count_type", "qty", "erp_qty", "difference", "outcome", "counted_by", "counted_at", "device_time", "source", "note"],
        order_by="counted_at asc, creation asc",
    )
    names = full_names([c.counted_by for c in counts])
    blind = is_blind_for(doc)
    return {
        "item": serialize_item(row, frappe.session.user, blind),
        "counts": [
            {
                "name": c.name,
                "count_type": c.count_type,
                "qty": flt(c.qty),
                "erp_qty": None if blind else flt(c.erp_qty),
                "difference": None if blind else flt(c.difference),
                "outcome": c.outcome,
                "counted_by": c.counted_by,
                "counted_by_name": names.get(c.counted_by, c.counted_by),
                "counted_at": str_or_none(c.counted_at),
                "device_time": str_or_none(c.device_time),
                "source": c.source,
                "note": c.note,
            }
            for c in counts
        ],
    }


# --------------------------------------------------------------------------------------------
# Reads (app)
# --------------------------------------------------------------------------------------------


def my_sessions():
    """Sessions the user can count in: Counting / Recount, where they are a counter, the session
    is Open, or they supervise."""
    user = frappe.session.user
    supervisor = rules.is_supervisor(current_roles())
    sessions = frappe.get_list(
        SESSION,
        filters={"status": ["in", list(rules.COUNTING_SESSION_STATUSES)]},
        fields=["name"],
        order_by="modified desc",
        limit_page_length=50,
    )
    if not sessions:
        return []
    names = [s.name for s in sessions]
    mine = set(frappe.get_all("Stocktaking Counter", filters={"parent": ["in", names], "parenttype": SESSION, "user": user}, pluck="parent"))
    out = []
    for name in names:
        doc = frappe.get_doc(SESSION, name)
        if not (supervisor or name in mine or doc.counting_mode == rules.MODE_OPEN):
            continue
        out.append(serialize_session(doc, user=user))
    return out


def my_stats(name, user) -> dict:
    return {
        "assigned": frappe.db.count(ITEM, {"session": name, "counter": user}),
        "open": frappe.db.count(ITEM, {"session": name, "counter": user, "status": ["in", list(ASSIGNABLE_STATUSES)]}),
        "items_counted": frappe.db.count(ITEM, {"session": name, "counted_by": user}),
        "counts": frappe.db.count(COUNT, {"session": name, "counted_by": user}),
    }


def get_items(name, start=0, limit=1000, mine=False, status=None, query=None):
    doc = get_session_doc(name)
    user = frappe.session.user
    filters = {"session": name}
    if cint(mine):
        filters["counter"] = user
    if status:
        wanted = frappe.parse_json(status) if isinstance(status, str) and status.startswith("[") else status
        wanted = wanted if isinstance(wanted, list) else [wanted]
        filters["status"] = ["in", wanted]
    or_filters = None
    if query:
        like = f"%{query.strip()}%"
        or_filters = [["item_code", "like", like], ["item_name", "like", like], ["batch_no", "like", like]]
    limit = min(cint(limit) or 1000, MAX_PAGE)
    rows = frappe.get_all(
        ITEM,
        filters=filters,
        or_filters=or_filters,
        fields=ITEM_FIELDS,
        order_by="item_name asc, item_code asc, batch_no asc",
        limit_start=cint(start),
        limit_page_length=limit,
    )
    total = frappe.db.count(ITEM, filters) if not or_filters else len(rows)
    blind = is_blind_for(doc)
    names = full_names([r.counter for r in rows] + [r.counted_by for r in rows])
    return {
        "items": [serialize_item(r, user, blind, names) for r in rows],
        "barcodes": item_barcodes({r.item_code for r in rows}),
        "start": cint(start),
        "limit": limit,
        "total": total,
        "session_status": doc.status,
    }


def item_barcodes(codes) -> dict:
    codes = [c for c in set(codes or []) if c]
    if not codes:
        return {}
    out = {}
    for row in frappe.get_all("Item Barcode", filters={"parent": ["in", codes], "parenttype": "Item"}, fields=["parent", "barcode"], limit_page_length=0):
        if row.barcode:
            out.setdefault(row.parent, []).append(row.barcode)
    return out


# --------------------------------------------------------------------------------------------
# Serialization
# --------------------------------------------------------------------------------------------


def str_or_none(value):
    return None if value in (None, "") else str(value)


def num_or_none(value):
    return None if value in (None, "") else flt(value)


def serialize_item(row, user=None, blind=False, names=None) -> dict:
    if row is None:
        return None
    if names is None:
        names = full_names([row.get("counter"), row.get("counted_by")])
    return {
        "name": row.get("name"),
        "session": row.get("session"),
        "item_code": row.get("item_code"),
        "item_name": row.get("item_name") or row.get("item_code"),
        "warehouse": row.get("warehouse"),
        "batch_no": row.get("batch_no") or None,
        "expiry_date": str_or_none(row.get("expiry_date")),
        "stock_uom": row.get("stock_uom"),
        "erp_qty": None if blind else flt(row.get("erp_qty")),
        "valuation_rate": None if blind else flt(row.get("valuation_rate")),
        "item_group": row.get("item_group"),
        "brand": row.get("brand"),
        "has_batch_no": bool(cint(row.get("has_batch_no"))),
        "location": row.get("location"),
        "added_during_count": bool(cint(row.get("added_during_count"))),
        "status": row.get("status") or rules.ITEM_NOT_COUNTED,
        "next_count_type": row.get("next_count_type") or rules.COUNT_1,
        "counter": row.get("counter") or None,
        "counter_name": names.get(row.get("counter")) if row.get("counter") else None,
        "is_mine": bool(user) and row.get("counter") == user,
        "count_1": num_or_none(row.get("count_1")),
        "count_2": num_or_none(row.get("count_2")),
        "recount_qty": num_or_none(row.get("recount_qty")),
        "recount_count": cint(row.get("recount_count")),
        "final_qty": num_or_none(row.get("final_qty")),
        "qty_difference": None if blind else flt(row.get("qty_difference")),
        "value_difference": None if blind else flt(row.get("value_difference")),
        "counted_by": row.get("counted_by") or None,
        "counted_by_name": names.get(row.get("counted_by")) if row.get("counted_by") else None,
        "counted_at": str_or_none(row.get("counted_at")),
        "recount_note": row.get("recount_note"),
        "modified": str_or_none(row.get("modified")),
    }


def serialize_session(doc, user=None, totals=None) -> dict:
    user = user or frappe.session.user
    roles = frappe.get_roles(user)
    supervisor = rules.is_supervisor(roles)
    counters = doc.counter_users
    keys = get_qr_keys()
    totals = totals or doc.totals()
    blind = bool(cint(doc.blind_count)) and not supervisor
    can_count = doc.status in rules.COUNTING_SESSION_STATUSES and (supervisor or user in counters or doc.counting_mode == rules.MODE_OPEN)
    return {
        "name": doc.name,
        "warehouse": doc.warehouse,
        "warehouse_name": frappe.db.get_value("Warehouse", doc.warehouse, "warehouse_name") or doc.warehouse,
        "warehouses": leaf_warehouses(doc.warehouse) if doc.warehouse else [],
        "company": doc.company,
        "posting_date": str_or_none(doc.posting_date),
        "status": doc.status,
        "counting_mode": doc.counting_mode,
        "blind_count": blind,
        "duplicate_count_policy": doc.duplicate_count_policy or rules.POLICY_LOCK,
        "require_second_count": bool(cint(doc.require_second_count)),
        "qty_tolerance": flt(doc.qty_tolerance),
        "freeze_warehouse": bool(cint(doc.freeze_warehouse)),
        "frozen": bool(cint(doc.frozen)),
        "started_at": str_or_none(doc.started_at),
        "counting_completed_at": str_or_none(doc.counting_completed_at),
        "approved_at": str_or_none(doc.approved_at),
        "stock_reconciliation": doc.stock_reconciliation,
        "completed_at": str_or_none(doc.completed_at),
        "remarks": doc.remarks,
        "totals": {k: (None if blind and k in ("qty_variance", "value_variance") else v) for k, v in totals.items()},
        "my": my_stats(doc.name, user),
        "can_count": bool(can_count),
        "is_supervisor": supervisor,
        "counters": counters,
        "qr_item_key": keys["qr_item_key"],
        "qr_batch_key": keys["qr_batch_key"],
        "modified": str_or_none(doc.modified),
    }


def set_if_field(document, fieldname, value):
    if document.meta.has_field(fieldname):
        document.set(fieldname, value)


def as_datetime(value):
    if isinstance(value, dt.datetime):
        return value
    return get_datetime(value) if value else None
