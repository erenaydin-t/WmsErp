# wmserp_picking — physical picking workflow for ERPNext Pick Lists

A small Frappe app that adds a **Ready to Pick → Picking → Picked** workflow on top of the
standard ERPNext *Pick List* and exposes the whitelisted API used by the WMS ERP Android app.

* **No core modifications.** Everything is added through Custom Fields, one Property Setter and
  `doc_events` hooks; `frappe` and `erpnext` files are never touched.
* **No direct SLE / GL manipulation.** The app only writes its own custom fields and creates
  *draft* Delivery Notes / Stock Entries through the regular Document API. Stock and accounting
  entries are produced by ERPNext when a user submits those drafts.
* Tested against ERPNext v14 and v15 conventions (`use_serial_batch_fields` on v15 is set when a
  row carries a batch or serial number).

## Installation

```bash
cd frappe-bench
bench get-app wmserp_picking /path/to/WmsErp/erpnext/wmserp_picking   # or a git URL
bench --site <site> install-app wmserp_picking
bench --site <site> migrate
```

`after_install` and `after_migrate` both run `wmserp_picking.setup.custom_fields.setup_customizations`,
so the customizations survive ERPNext upgrades. To apply them without installing the app:

```bash
bench --site <site> execute wmserp_picking.setup.custom_fields.setup_customizations
```

## What gets added

**Pick List** (section *WMS Picking*, all `allow_on_submit`):

| Field | Type | Notes |
| --- | --- | --- |
| `custom_picker` | Link → User | Picker. Mirrored into the standard *Assign To* (ToDo) by a hook. |
| `custom_picking_status` | Select: Ready to Pick / Picking / Picked | Independent of the standard `status`. Defaults to *Ready to Pick* on submit. |
| `custom_target_warehouse` | Link → Warehouse | Destination for *Material Transfer* Stock Entries. |
| `custom_picking_started_at` / `custom_picking_started_by` | Datetime / Link → User | Read-only, stamped by `start_picking`. |
| `custom_picking_completed_at` / `custom_picking_completed_by` | Datetime / Link → User | Read-only, stamped by `complete_picking`. |
| `custom_generated_doctype` / `custom_generated_docname` | Link → DocType / Dynamic Link | Duplicate prevention for `generate_document`. |

**Pick List Item:**

| Field | Type | Notes |
| --- | --- | --- |
| `custom_wms_picked_qty` | Float (stock UOM) | Physically picked quantity saved by the app. Copied into the standard `picked_qty` when picking completes (ERPNext pre-fills `picked_qty` with the full quantity on submit, so it cannot be used as a progress counter). |
| `custom_optional` | Check | Row may be completed with less than the required quantity. |

**Property Setter:** *Material Issue* is appended to `Pick List.purpose` so that issue pick lists can
be created without touching core (a Material Issue Stock Entry is generated for them).

## API

All methods live in `wmserp_picking.api.pick_list` and are called as
`POST /api/method/wmserp_picking.api.pick_list.<method>` with a JSON body (session cookie or
`Authorization: token key:secret`). The caller must be the pick list's picker or be assigned to it;
users with the *Stock Manager* or *System Manager* role may act on any pick list.

| Method | Parameters | Effect |
| --- | --- | --- |
| `get_my_pick_lists` | `status?`, `include_generated?=0`, `limit?=50` | Submitted pick lists assigned to `frappe.session.user` (picker **or** standard assignment) whose picking status is actionable. Picked lists that already have a generated document are hidden unless `include_generated=1`. |
| `get_pick_list` | `name` | Full document with rows (`required_qty`/`picked_qty` in stock UOM, batch, expiry, target warehouse, barcodes, `row_status`). |
| `resolve_scan` | `name`, `code` | Maps a scanned code to a row by item code, *Item Barcode* or *Batch* (rejects expired/disabled batches). `match` is `item_code`, `barcode`, `batch`, `not_on_list` or `none`. |
| `assign_picker` | `name`, `user` | Supervisor action: sets the picker and the standard assignment. |
| `start_picking` | `name` | Ready to Pick → Picking; stamps started at/by; self-claims the picker when the user is only assigned. Idempotent when already Picking. |
| `save_progress` | `name`, `items` | Saves partial quantities: `items = [{"name": <row>, "picked_qty": 3, "batch_no"?, "serial_no"?, "warehouse"?, "item_code"?}]`. Validates item, warehouse (must match the row / exist / not be a group / same company), batch (exists, belongs to the item, not disabled, not expired, enough stock in the row warehouse) and quantity (`0 ≤ picked ≤ required`). |
| `complete_picking` | `name`, `items?` | Optionally saves `items` first, then requires every mandatory row to be picked in full (optional rows may be short). Sets *Picked*, stamps completed at/by and copies `custom_wms_picked_qty` into `picked_qty`. |
| `generate_document` | `name` | Creates **one** draft document based on `purpose`: Delivery → *Delivery Note* (one per customer, linked to the Sales Orders), Material Transfer → *Stock Entry (Material Transfer)*, Material Issue → *Stock Entry (Material Issue)*, Material Transfer for Manufacture → *Stock Entry* with the Work Order. Never submits. Returns `{"doctype", "name", "docstatus", "already_generated", "documents": [...]}`; a second call (or a document created from the desk with the same links) returns the existing one with `already_generated: true`. |

Every transition returns the serialized pick list, e.g.

```json
{
  "name": "STO-PICK-2026-00012", "purpose": "Delivery", "customer": "CUST-0001", "customer_name": "Globex",
  "picking_status": "Picking", "picker": "picker@example.com", "picking_started_at": "2026-09-28 09:12:00",
  "generated_doctype": null, "generated_docname": null, "item_count": 2, "required_qty": 15, "picked_qty": 4,
  "items": [
    {"name": "a1b2c3", "idx": 1, "item_code": "ITEM-001", "item_name": "Steel Bolt M8", "warehouse": "Stores - WM",
     "target_warehouse": null, "batch_no": "B-2026-01", "expiry_date": "2027-01-31", "required_qty": 10,
     "picked_qty": 4, "uom": "Nos", "optional": 0, "barcodes": ["8690000000017"], "row_status": "Partial"}
  ]
}
```

Errors are raised with `frappe.throw` (HTTP 417 / 403 with `_server_messages`), which the Android
app shows verbatim.

## Permissions

* Pickers need *read* on Pick List (Stock User is enough). Progress is stored with `db_set`, so no
  *write* permission on submitted documents is required.
* `generate_document` inserts a Delivery Note or Stock Entry as the calling user, so that user (or a
  supervisor calling on their behalf) needs *create* permission on the target DocType.

## Tests

The pure rules (row status, completion, purpose mapping, payload validation, assignment checks)
are covered without a bench:

```bash
cd erpnext/wmserp_picking
python -m unittest discover -p "test_*.py"
```
