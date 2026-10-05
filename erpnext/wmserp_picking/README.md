# wmserp_picking — row-level picking, JSON QR labels, label sheets, receiving, picker KPIs and stocktaking for ERPNext

A small Frappe app that turns the standard ERPNext *Pick List* into a multi-picker workflow driven
by the WMS ERP Android app:

* **Row-level assignment.** Each `Pick List Item` row has its own picker, so several pickers work
  on one card at the same time. Rows go **Not Picked → Picking → Picked**; the card (Pick List) goes
  **Ready to Pick → Picking → Picked** and flips to *Picked* the moment its last row is picked.
* **Strict JSON QR execution.** Pickers scan labels whose content is a JSON object with configurable
  keys (default `{"item_code": ..., "batch_no": ...}`). The app rejects anything else and shows
  *Wrong batch. Expected …, scanned …* when the batch differs. A Batch print format prints the labels.
* **Time tracking & KPIs.** Every row records started/completed timestamps and its duration; the card
  records its first start and last completion; `get_picker_kpis` aggregates today's stats per picker.
* **Stocktaking (physical inventory count), 0.3.0.** A *Stocktaking Session* freezes a warehouse,
  snapshots every item / batch with its ERP quantity, lets counters count from the app (first count,
  forced second count on a mismatch, manager review, recounts, duplicate-count policies, offline
  queues), keeps every count in an append-only log and posts the approved result as a standard
  *Stock Reconciliation* (see [Stocktaking](#stocktaking)).
* **QR label sheets, 0.4.0.** Besides the single *WMS Batch QR Label* and *WMS Item QR Label* print
  formats, the *Batch* and *Item* lists get a **Print QR label sheet** action that renders many labels on
  A4 pages (20 batches → 12 + 8 labels on two pages) as one PDF. The layout (columns, rows, label size,
  QR size, margins, page size) lives in the sheet Print Format, editable per site; nothing is hard-coded
  in the app (see [Label sheets](#label-sheets)).
* **Receiving against draft Purchase Receipts, 0.4.0.** Purchasing creates the Purchase Receipt and runs
  it through the site's workflow; at the warehouse stage the app counts the goods against its rows and
  `receive` writes the counts, drops unreceived rows and submits through the workflow (see
  [Receiving API](#receiving-api)).
* **Required fields, 0.4.0.** Mandatory fields a site added to Purchase Receipt / Delivery Note / Stock
  Entry (a *Department*, a *Cost Center*…) are filled from the user's defaults and the company's default
  accounting dimensions; what remains is reported as `missing_fields` so the app asks once and retries
  with `values` (see [Required fields](#required-fields)).
* **No core modifications, no direct stock writes.** Everything is Custom Fields, one Property Setter,
  a Single DocType, the stocktaking DocTypes and reports, Print Formats, list-view scripts and
  `doc_events`. Stock and GL entries are produced by ERPNext only when a Purchase Receipt / Delivery Note /
  Stock Entry / Stock Reconciliation is submitted (by a user, or by `receive` on the user's behalf through
  the site's own workflow).

## Installation

Supported: ERPNext / Frappe **v14, v15 and v16** (the API only uses plain field lists and aliases, which
all three accept; the Delivery Note mapper is called with the signature of the installed version).

The Android project and this Frappe app live in one repository, but bench needs a Frappe app at the
root of what it clones. CI therefore publishes this folder as its own branch, **`wmserp_picking`**
(a `git subtree split` of `erpnext/wmserp_picking`, refreshed on every push to `main`). Every
published version is also tagged **`wmserp_picking-v<version>`** (the version is `__version__` in
`wmserp_picking/__init__.py`, shown by `bench version` and returned by `get_settings` as `app_version`).

```bash
cd frappe-bench
bench get-app https://github.com/erenaydin-t/WmsErp --branch wmserp_picking            # latest
bench get-app https://github.com/erenaydin-t/WmsErp --branch wmserp_picking-v0.4.0     # a fixed version
bench --site <site> install-app wmserp_picking
bench --site <site> migrate
```

To update an existing bench to the latest published version (or to a tag):

```bash
cd frappe-bench/apps/wmserp_picking
git fetch origin --tags
git checkout wmserp_picking && git pull origin wmserp_picking     # latest
# git checkout wmserp_picking-v0.4.0                              # or pin a version
cd ../..
bench --site <site> migrate      # re-applies the custom fields / print format, creates the stocktaking DocTypes
bench restart                    # no `bench build` needed: the form / list / report scripts are served from the app folder
bench version                    # should list wmserp_picking 0.4.0
```

### Versions

| Version | Tag | Notes |
| --- | --- | --- |
| 0.4.0 | `wmserp_picking-v0.4.0` | QR label **sheets** for Batch and Item (list action *Print QR label sheet*, PDF through `wmserp_picking.api.labels.download_label_sheet`, layout in the editable *WMS Batch QR Label Sheet* / *WMS Item QR Label Sheet* print formats, `reset_label_print_formats` to restore the shipped templates), the single *WMS Item QR Label* format and the `wms_item_qr_payload` / `wms_item_qr_svg` Jinja helpers; **receiving** against draft Purchase Receipts (`wmserp_picking.api.purchase_receipt`: `get_receivable`, `get_receipt`, `receive` with workflow-aware submit, *WMS Settings > Receipt Workflow States*); **required fields** filled server-side (`wmserp_picking.documents`) and reported as `missing_fields` by `receive` and `generate_document` (`values` parameter); `get_settings` lists `features`. Picking and stocktaking unchanged. |
| 0.3.0 | `wmserp_picking-v0.3.0` | Stocktaking sessions: warehouse freeze through `Stock Ledger Entry.before_insert`, snapshot of item / batch quantities and valuation rates, scan-to-count API with forced second counts, duplicate-count policies, blind counts and tolerances, bulk assignment by filters or *Open* mode, manager review and recounts, append-only count log, offline `sync_counts`, Stock Reconciliation posting with `custom_stocktaking_session`, the *Stocktaking Variance* and *Stocktaking Uncounted Items* reports. Picking unchanged. |
| 0.2.1 | `wmserp_picking-v0.2.1` | Fixes the *WMS Batch QR Label* print format: `wms_qr_svg` handed pyqrcode a text buffer although `QRCode.svg()` writes UTF-8 bytes, so every label failed with `string argument expected, got 'bytes'`. The QR helpers no longer import frappe at module level and are covered by `test_qr.py`. Payload format unchanged. |
| 0.2.0 | `wmserp_picking-v0.2.0` | Row-level multi-picker workflow, WMS Settings, strict JSON QR labels, batch QR print format, timers and picker KPIs; Frappe v14–v16. Migrating from 0.1.0 removes the card-level fields (see below). |
| 0.1.0 | – | First card-level workflow (`start_picking` / `save_progress` / `complete_picking`), superseded. |

> `bench get-app https://github.com/erenaydin-t/WmsErp` **without** `--branch` fails with
> `No such file or directory: .../apps/WmsErp/setup.py` because the repository root is the Android
> project, not a Frappe app.

`after_install` and `after_migrate` run `wmserp_picking.setup.custom_fields.setup_customizations`
(custom fields, the purpose option, the print format, the *Stock Reconciliation* link field). To re-apply by hand:

```bash
bench --site <site> execute wmserp_picking.setup.custom_fields.setup_customizations
```

Upgrading from the first (card-level) version removes the old header fields `custom_picker`,
`custom_picking_started_at/by`, `custom_picking_completed_at/by` and the row flag `custom_optional`
(the database columns are left in place).

## What gets added

**WMS Settings** (Single DocType, *Stock Manager* can edit):

| Field | Default | Purpose |
| --- | --- | --- |
| `qr_item_key` | `item_code` | JSON key holding the Item Code in QR labels |
| `qr_batch_key` | `batch_no` | JSON key holding the Batch No in QR labels |
| `receipt_workflow_states` | empty | Workflow states (one per line) in which the app may receive a draft Purchase Receipt. Empty: every *Draft* state of the active Purchase Receipt workflow whose *Allow Edit* role the user has (all drafts without a workflow). |

**Pick List** (section *WMS Picking*, all `allow_on_submit`):

| Field | Type | Notes |
| --- | --- | --- |
| `custom_picking_status` | Select: Ready to Pick / Picking / Picked | Card state, independent of the standard `status`. |
| `custom_card_started_at` | Datetime | First row started. |
| `custom_card_completed_at` | Datetime | Last row picked. |
| `custom_target_warehouse` | Link → Warehouse | Destination for *Material Transfer* Stock Entries. |
| `custom_generated_doctype` / `custom_generated_docname` | Link → DocType / Dynamic Link | Duplicate prevention for `generate_document`. |

**Pick List Item:**

| Field | Type | Notes |
| --- | --- | --- |
| `custom_picker` | Link → User | Picker of this row. Mirrored into the Pick List's standard *Assign To*. |
| `custom_row_status` | Select: Not Picked / Picking / Picked | |
| `custom_wms_picked_qty` | Float (stock UOM) | Physically picked quantity. Copied into `picked_qty` when the row completes (ERPNext pre-fills `picked_qty` on submit, so it cannot serve as a progress counter). |
| `custom_row_started_at` / `custom_row_completed_at` | Datetime | Stamped by `start_row` / row completion. |
| `custom_picking_duration_seconds` | Float | Completed minus started. |

**Property Setter:** *Material Issue* is appended to `Pick List.purpose`.

**Print formats** (DocType *Batch* and *Item*, Jinja, module *WMS ERP Picking*): *WMS Batch QR Label* and
*WMS Item QR Label* print one label per document (name, item, expiry and a QR code whose content is
`{"<qr_item_key>": <item>, "<qr_batch_key>": <batch>}` for a batch and `{"<qr_item_key>": <item>}` for an
item); *WMS Batch QR Label Sheet* and *WMS Item QR Label Sheet* lay many labels out on A4 pages for the list
action (see [Label sheets](#label-sheets)). The QR is rendered by the Jinja helpers `wms_batch_qr_svg(doc)`,
`wms_item_qr_svg(doc)`, `wms_qr_svg(text, scale, omit_size)` and the payload helpers `wms_batch_qr_payload(doc)` /
`wms_item_qr_payload(doc)` (pyqrcode, shipped with Frappe), which you can reuse in your own formats. The
formats are created when missing and **never overwritten by a migrate**, so a site can edit them;
`bench --site <site> execute wmserp_picking.setup.custom_fields.reset_label_print_formats` restores the shipped
templates (`--kwargs '{"names": ["WMS Batch QR Label Sheet"]}'` for one of them).

**List views:** `doctype_list_js` adds **Print QR label sheet** to the *Batch* and *Item* lists
(`public/js/wms_labels.js`): select the documents, pick the sheet format and the page size, get one PDF.

**Stocktaking** (0.3.0): the DocTypes *Stocktaking Session*, *Stocktaking Item*, *Stocktaking Count* and
*Stocktaking Counter*, the read-only link `custom_stocktaking_session` on *Stock Reconciliation* and the
reports *Stocktaking Variance* / *Stocktaking Uncounted Items* — described in [Stocktaking](#stocktaking).

## API

The picking methods live in `wmserp_picking.api.pick_list` and are called as
`POST /api/method/wmserp_picking.api.pick_list.<method>` with a JSON body (session cookie or
`Authorization: token key:secret`). Row operations require the caller to be the row's picker; users
with *Stock Manager* or *System Manager* may act on any row. The stocktaking methods
(`wmserp_picking.api.stocktaking`) are listed in [Stocktaking API](#stocktaking-api).

| Method | Parameters | Effect |
| --- | --- | --- |
| `get_settings` | – | `{"qr_item_key", "qr_batch_key", "app_version", "features"}`; the app caches the keys. `features` lists `stocktaking`, `purchase_receipt_receiving`, `label_sheets`, `required_field_values`. |
| `get_my_pick_lists` | `limit?=50` | Pick Lists with `docstatus == 1`, `status == "Open"` and at least one row whose `custom_picker` is `frappe.session.user`. Drafts are never returned. Each entry carries card totals plus `my_row_count`, `my_picked_rows`, `my_open_rows`, `all_rows_picked`. |
| `get_pick_list` | `name` | Full card with all rows (`is_mine` marks the caller's rows; batch, expiry, required/picked qty, row status and timestamps). |
| `start_row` | `name`, `row` | Row → *Picking*, stamps `custom_row_started_at`; stamps `custom_card_started_at` and card → *Picking* when empty. Idempotent. |
| `save_row_progress` | `name`, `row`, `picked_qty`, `item_code?`, `batch_no?`, `elapsed_seconds?` | Partial save (`0 ≤ picked ≤ required`). `item_code` / `batch_no` (from the scanned label) must match the row: *Wrong item* / *Wrong batch. Expected: X, scanned: Y*. When `picked == required` the row completes (see below). |
| `complete_row` | `name`, `row`, `picked_qty?`, `item_code?`, `batch_no?`, `elapsed_seconds?` | Same as above but requires the row to reach its required quantity. |
| `generate_document` | `name`, `values?` | Creates **one** draft document by purpose: Delivery → *Delivery Note* (one per customer, linked to the Sales Orders), Material Transfer → *Stock Entry (Material Transfer)*, Material Issue → *Stock Entry (Material Issue)*, Material Transfer for Manufacture → *Stock Entry* with the Work Order. Requires the card to be *Picked*. Returns `{"doctype", "name", "docstatus", "already_generated", "documents", "created": true}`; a second call returns the existing document with `already_generated: true`. Mandatory fields the site added are filled from `values` (`{"Delivery Note.department": "..."}`), the user's defaults and the company's default accounting dimensions; what is still missing is answered with HTTP 200 `{"doctype", "missing_fields": [{doctype, fieldname, label, fieldtype, options}], "created": false}` and nothing is inserted (see [Required fields](#required-fields)). |
| `get_picker_kpis` | `date?`, `user?` (supervisors) | `{"date", "user", "rows_picked", "qty_picked", "pick_lists_touched", "pick_lists_completed", "open_rows", "open_pick_lists", "total_seconds", "avg_seconds_per_row", "fastest_seconds", "slowest_seconds", "rows_per_hour"}` for the day. |
| `assign_rows` | `name`, `user`, `rows?` | Supervisor action: assigns the given rows (all unpicked rows when omitted) to a picker and updates the standard assignment. |

**Row completion and the last-picker rule.** When a row reaches its required quantity the backend sets
`custom_row_status = Picked`, `custom_row_completed_at`, `custom_picking_duration_seconds` and the
standard `picked_qty`, then runs the *card completion check* under a row lock on the Pick List: if every
row is now *Picked* and the card is not yet *Picked*, it sets `custom_picking_status = Picked` and
`custom_card_completed_at`. Exactly one request observes that transition; its response carries
`card_completed: true` and `is_last_picker: true`, and the app shows the document CTA to that picker.
Everyone else gets `is_last_picker: false` and a "task completed" screen. `start_row`, `save_row_progress`
and `complete_row` all return:

```json
{
  "pick_list": { "name": "STO-PICK-2026-00012", "picking_status": "Picking", "my_open_rows": 1, "all_rows_picked": false, "items": [ ... ] },
  "row": { "name": "a1b2c3", "item_code": "ITEM-001", "batch_no": "B-2026-01", "required_qty": 10, "picked_qty": 10,
           "row_status": "Picked", "row_started_at": "2026-09-28 09:12:00", "row_completed_at": "2026-09-28 09:15:40",
           "duration_seconds": 220.0, "is_mine": true },
  "row_completed": true,
  "card_completed": false,
  "is_last_picker": false
}
```

Errors are raised with `frappe.throw` (HTTP 417 / 403 with `_server_messages`), which the Android
app shows verbatim.

### Receiving API

`wmserp_picking.api.purchase_receipt`. The warehouse never creates receipts: purchasing does, from the
Purchase Order, and the site's *Purchase Receipt* workflow (if any) carries the draft to the warehouse stage.

| Method | Parameters | Effect |
| --- | --- | --- |
| `get_receivable` | `query?`, `limit?=50` | Draft, non-return Purchase Receipts the caller may receive: `workflow_state` in *WMS Settings > Receipt Workflow States*, else in the *Draft* states of the active workflow whose *Allow Edit* role the caller has (every draft without a workflow); `query` matches name / supplier / supplier name. Headers only: supplier, posting date, stage, `item_count`, `total_qty`, `can_receive`, `has_workflow`. |
| `get_receipt` | `name` | The receipt with its rows (`qty`, `received_qty`, UOM, conversion factor, warehouse, `batch_no`, `has_batch_no`, `has_serial_no`, `needs_batch`, the item's barcodes) plus `can_receive`. |
| `receive` | `name`, `rows`, `values?`, `submit?=1`, `remove_unreceived?=1` | `rows`: `[{"row", "qty", "warehouse"?, "batch_no"?}]` in the row UOM. Rows missing or counted 0 are removed when `remove_unreceived` (the Purchase Order stays open for them) and left untouched otherwise (saving the progress of a count). Writes `received_qty`, `qty`, `stock_qty`, `rejected_qty = 0`, the warehouse and the batch (`use_serial_batch_fields = 1` on v15+), fills required fields, saves, and with `submit` applies the caller's workflow transition that leads to a submitted state (an action named *Receive / Submit / Approve / Confirm / Complete / Accept* first) or `doc.submit()` without a workflow. Returns the receipt plus `created`, `submitted`, `differences` (`[{row, item_code, expected, counted}]`) and `removed_rows`, or the `missing_fields` answer. |

Batch tracked rows without a batch that ERPNext would not create on submit (`needs_batch`) are refused
until a batch is sent. Over-receipt is passed on to ERPNext, which applies its own over-receipt allowance.

### Label sheets

`wmserp_picking.api.labels`, used by the list action and callable directly:

| Method | Parameters | Effect |
| --- | --- | --- |
| `get_label_sheet_formats` | `doctype` | Enabled sheet print formats for *Batch* or *Item* (the shipped one plus any copy a site made; a sheet is recognised by the `<!-- wms-label-sheet -->` marker in its HTML). |
| `label_sheet_html` | `doctype`, `names`, `print_format?` | The HTML of the sheet for the given documents (JSON list or comma separated, at most 500). |
| `download_label_sheet` | `doctype`, `names`, `print_format?`, `page_size?` | The same as a PDF (`GET /api/method/...download_label_sheet?doctype=Batch&names=[...]`), rendered with Frappe's own wkhtmltopdf pipeline. Page margins and size come from the `.print-format {}` rule of the format. |

The sheet template starts with the layout parameters (`columns`, `rows`, `label_width`, `label_height`,
`gap`, `qr_size`, `show_payload`): edit them in *Print Format > WMS Batch QR Label Sheet* for a different
label stock; labels are paged `columns × rows` per sheet with a page break between sheets.

### Required fields

`wmserp_picking.documents` and `wmserp_picking.required_fields` give every document the app creates or
submits the same treatment: the required fields of the DocType and its child tables (custom fields
included; Link, Select, Data, Date, numeric…) that are still empty are filled, in order, from the
`values` sent by the app (`"Doctype.fieldname"` or `fieldname`), from the source documents (the Pick List,
the Material Request), from the user's defaults (`frappe.defaults`) and from the company's default
accounting dimensions (cost center and project included). What is still empty is returned as
`missing_fields` with HTTP 200, nothing is saved, and the app asks the user once. A
`frappe.MandatoryError` raised by ERPNext itself on save or submit is translated the same way.

## Stocktaking

Physical inventory counts (0.3.0). Four DocTypes in the module *WMS ERP Picking*:

| DocType | Purpose |
| --- | --- |
| **Stocktaking Session** (`ST-.YYYY.-`) | One count of one warehouse (a warehouse group includes its leaf warehouses). Options: *counting mode* (Assigned / Open), *freeze warehouse*, *require second count*, *duplicate count policy* (Lock after count / Allow additional counts), *blind count*, *qty tolerance*, item group / brand filter, *include zero stock*; a *counters* table (user + area); live totals (total, counted, uncounted, matched, variance, recount required, pending review, approved, qty and value variance); timeline stamps and the link to the generated Stock Reconciliation. |
| **Stocktaking Item** | One row per item + warehouse + batch of the snapshot (thousands of rows, hence not a child table): ERP quantity and valuation rate at start, status, counter, `count_1` / `count_2` / `recount_*` with user and time, `final_qty`, quantity and value difference, recount / review notes. Rows added during the count carry `added_during_count`. |
| **Stocktaking Count** | Append-only log of every count: session, row, count type, qty, ERP qty, difference, outcome, user, server time, device time, source and a unique `client_ref`. Never edited or overwritten. |
| **Stocktaking Counter** | Child table of the session: user, full name, optional area. |

Plus the read-only link `custom_stocktaking_session` on *Stock Reconciliation* and the script reports
**Stocktaking Variance** (rows with a difference, sorted by absolute value difference, with quantity and value
totals) and **Stocktaking Uncounted Items**.

**Session flow.** Draft → *Start counting* → **Counting** → **Manager Review** (every row counted, differences
left to review) or **Recount** (recounts requested) → **Final Approval** → *Create Stock Reconciliation* →
**Reconciled** → the reconciliation is submitted → **Completed**. **Cancelled** is possible until a reconciliation
is submitted. Row statuses: Not Counted → Assigned → Counting → Counted / Recount Required → Recounted →
Manager Review → Approved → Finalized.

* **Start** checks that no other session freezes the warehouse, snapshots the *Bin* rows (item group / brand
  filter and the zero-stock option applied) and, for batch items, one row per batch with stock in the warehouse
  (batch balances come from the Stock Ledger on v14 and from *Serial and Batch Entry* on v15 / v16) plus a
  remainder row without batch, inserted with `frappe.db.bulk_insert`. In *Assigned* mode rows stay
  *Not Counted* until assigned; in *Open* mode every row is countable by anyone with the app and counters are
  registered on their first count.
* **Freeze.** While the session is Counting … Reconciled, `Stock Ledger Entry.before_insert` refuses every entry
  for the warehouse (or any warehouse below the frozen group) with *Warehouse X is frozen for stocktaking ST-…*,
  except the session's own Stock Reconciliation. `freeze_warehouse` can be switched off per session.
* **Counting rules** (`stocktaking/rules.py`, pure Python, mirrored in the app): a count within `qty_tolerance` of
  the ERP quantity is *Counted*; otherwise, with *require second count*, the same counter must count again
  (`next_count_type = Second Count`); a second count that matches is accepted, one that still differs goes to
  *Manager Review* with `final_qty` = the second count. A manager-requested recount that matches ERP closes the
  row, otherwise the row becomes *Recounted* for review. *Lock after count* refuses another user's count with
  `already_counted` (and the counter's name); *Allow additional counts* records it as a further count. Rows not
  assigned to the caller are refused with `not_assigned` (supervisors and *Open* mode excepted). Rejections come
  back as `{"outcome": "rejected", "reason": ...}` with HTTP 200 so the app can show them and keep its offline
  queue in order.
* **Assignment** (`assign_items`): bulk, by explicit rows or by filters (item group including children, brand,
  batch, location, status); `count_assignable` previews the number of matching rows; `unassign=1` clears them.
* **Review.** `request_recount` (row → *Recount Required*, session → *Recount*), `accept_item` / `accept_items` /
  `accept_all` (rows → *Approved*), `approve_session` (→ *Final Approval*; requires every row counted and every
  difference reviewed), `create_reconciliation` (a Stock Reconciliation with one line per row whose final quantity
  differs from ERP, batch rows through `use_serial_batch_fields`; when nothing differs the session completes
  directly), `complete_session`, `cancel_session`. Submitting the Stock Reconciliation marks the session
  *Completed* and unfreezes the warehouse; cancelling it returns the session to *Final Approval*.

### Stocktaking API

`POST /api/method/wmserp_picking.api.stocktaking.<method>`:

| Method | Parameters | Effect |
| --- | --- | --- |
| `get_my_sessions` | – | Sessions in *Counting* / *Recount* the caller may count in (assigned counter, *Open* mode or supervisor), with totals and the caller's own stats. |
| `get_session` | `name` | Session header: options, totals, `my` stats, `can_count`, `is_supervisor`, counters, QR keys and warehouses. |
| `get_items` | `name`, `start?=0`, `limit?=1000`, `mine?`, `status?`, `query?` | Paged rows (`items`, `total`, `start`, `limit`) plus a `barcodes` map (Item Barcode → item code) for offline scanning. ERP quantities are omitted for counters of a blind session. |
| `lookup` | `name`, `code`, `warehouse?` | Resolves a JSON label or barcode against the session (`found`, `kind`, `item_code`, `batch_no`, `expiry_date`, `rows`, `can_add`, `in_session`). |
| `start_item` | `item` | Row → *Counting*. |
| `submit_count` | `name`, `qty`, `item?` or `item_code` + `batch_no?` + `warehouse?`, `client_ref?`, `device_time?`, `note?`, `source?` | Records one count and applies the rules. Returns `{"outcome": accepted / second_count_required / manager_review / duplicate / rejected, "reason"?, "item", "count", "totals", "session_status"}`. A repeated `client_ref` returns the original result as `duplicate`. |
| `sync_counts` | `name`, `counts` (list of `submit_count` bodies) | Replays an offline queue in order, one savepoint per entry; a failing entry yields `{"client_ref", "outcome": "error", "reason": "validation" / "permission", "message"}` and the others continue. |
| `get_item_history` | `item` | Every count of a row, oldest first. |
| `start_session`, `assign_items`, `count_assignable`, `complete_counting`, `request_recount`, `request_recounts`, `accept_item`, `accept_items`, `accept_all`, `approve_session`, `create_reconciliation`, `complete_session`, `cancel_session`, `get_progress` | see the session form | Manager actions (write permission on the session); the form and list buttons call these. |

## Permissions

* Pickers need *read* on Pick List (Stock User is enough). Progress is stored with `db_set`, so no
  *write* permission on submitted documents is required.
* `generate_document` inserts a Delivery Note or Stock Entry as the calling user, so that user (or a
  supervisor) needs *create* permission on the target DocType.
* Row assignment (`custom_picker`) is done in the desk on the submitted Pick List (the fields allow
  editing after submit) or through `assign_rows`.
* Receiving: the warehouse user needs *write* on Purchase Receipt (and *submit* when the site has no
  workflow); with a workflow the transition to the submitted state must be allowed to one of the user's
  roles, otherwise `receive` saves the counts and reports `submitted: false`.
* Stocktaking: counters need *Stock User* (read on the stocktaking DocTypes; counts are written by the
  whitelisted methods). Managers need *Stock Manager* (write on Stocktaking Session / Item), *create* on
  Stock Reconciliation for `create_reconciliation` and *submit* on it to complete the session.

## Tests

The pure rules (row/card completion, quantity validation, JSON QR parsing and matching, purpose
mapping, permissions, KPI aggregation), the stocktaking rules (`test_stocktaking_rules.py`: count
evaluation, second counts, recounts, duplicate policies, assignment, session status transitions, progress
totals, reconciliation rows, scan resolution) and the QR label helpers (`test_qr.py`: payload format, the
bytes-vs-text SVG regression, a well-formed SVG for a batch such as `DPL-20100067-00443`), the label sheet
layout (`labels/test_labels.py`, rendered with Jinja: 20 batches → 12 + 8 labels on two pages), the receiving
rules (`receiving/test_receiving_rules.py`) and the required-field helpers (`test_required_fields.py`) are
covered without a bench:

```bash
cd erpnext/wmserp_picking
pip install pyqrcode==1.2.1   # optional: also exercises the real renderer (skipped when missing)
python -m unittest discover -p "test_*.py"
```
