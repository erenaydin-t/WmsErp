# wmserp_picking — row-level picking, JSON QR labels and picker KPIs for ERPNext

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
* **No core modifications, no direct stock writes.** Everything is Custom Fields, one Property Setter,
  a Single DocType, a Print Format and `doc_events`. Stock and GL entries are produced by ERPNext only
  when the generated *draft* Delivery Note / Stock Entry is submitted by a user.

## Installation

The Android project and this Frappe app live in one repository, but bench needs a Frappe app at the
root of what it clones. CI therefore publishes this folder as its own branch, **`wmserp_picking`**
(a `git subtree split` of `erpnext/wmserp_picking`, refreshed on every push to `main`):

```bash
cd frappe-bench
bench get-app https://github.com/erenaydin-t/WmsErp --branch wmserp_picking
bench --site <site> install-app wmserp_picking
bench --site <site> migrate
```

> `bench get-app https://github.com/erenaydin-t/WmsErp` **without** `--branch` fails with
> `No such file or directory: .../apps/WmsErp/setup.py` because the repository root is the Android
> project, not a Frappe app.

`after_install` and `after_migrate` run `wmserp_picking.setup.custom_fields.setup_customizations`
(custom fields, the purpose option, the print format). To re-apply by hand:

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

**Print Format "WMS Batch QR Label"** (DocType *Batch*, Jinja): item, name, expiry and a QR code whose
content is `{"<qr_item_key>": <item>, "<qr_batch_key>": <batch>}`. The QR is rendered by the Jinja
helpers `wms_batch_qr_svg(doc)` / `wms_qr_svg(text)` (pyqrcode, shipped with Frappe), which you can
reuse in your own label formats.

## API

All methods live in `wmserp_picking.api.pick_list` and are called as
`POST /api/method/wmserp_picking.api.pick_list.<method>` with a JSON body (session cookie or
`Authorization: token key:secret`). Row operations require the caller to be the row's picker; users
with *Stock Manager* or *System Manager* may act on any row.

| Method | Parameters | Effect |
| --- | --- | --- |
| `get_settings` | – | `{"qr_item_key", "qr_batch_key", "app_version"}`; the app caches the keys. |
| `get_my_pick_lists` | `limit?=50` | Pick Lists with `docstatus == 1`, `status == "Open"` and at least one row whose `custom_picker` is `frappe.session.user`. Drafts are never returned. Each entry carries card totals plus `my_row_count`, `my_picked_rows`, `my_open_rows`, `all_rows_picked`. |
| `get_pick_list` | `name` | Full card with all rows (`is_mine` marks the caller's rows; batch, expiry, required/picked qty, row status and timestamps). |
| `start_row` | `name`, `row` | Row → *Picking*, stamps `custom_row_started_at`; stamps `custom_card_started_at` and card → *Picking* when empty. Idempotent. |
| `save_row_progress` | `name`, `row`, `picked_qty`, `item_code?`, `batch_no?`, `elapsed_seconds?` | Partial save (`0 ≤ picked ≤ required`). `item_code` / `batch_no` (from the scanned label) must match the row: *Wrong item* / *Wrong batch. Expected: X, scanned: Y*. When `picked == required` the row completes (see below). |
| `complete_row` | `name`, `row`, `picked_qty?`, `item_code?`, `batch_no?`, `elapsed_seconds?` | Same as above but requires the row to reach its required quantity. |
| `generate_document` | `name` | Creates **one** draft document by purpose: Delivery → *Delivery Note* (one per customer, linked to the Sales Orders), Material Transfer → *Stock Entry (Material Transfer)*, Material Issue → *Stock Entry (Material Issue)*, Material Transfer for Manufacture → *Stock Entry* with the Work Order. Requires the card to be *Picked*. Returns `{"doctype", "name", "docstatus", "already_generated", "documents"}`; a second call returns the existing document with `already_generated: true`. |
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

## Permissions

* Pickers need *read* on Pick List (Stock User is enough). Progress is stored with `db_set`, so no
  *write* permission on submitted documents is required.
* `generate_document` inserts a Delivery Note or Stock Entry as the calling user, so that user (or a
  supervisor) needs *create* permission on the target DocType.
* Row assignment (`custom_picker`) is done in the desk on the submitted Pick List (the fields allow
  editing after submit) or through `assign_rows`.

## Tests

The pure rules (row/card completion, quantity validation, JSON QR parsing and matching, purpose
mapping, permissions, KPI aggregation) are covered without a bench:

```bash
cd erpnext/wmserp_picking
python -m unittest discover -p "test_*.py"
```
