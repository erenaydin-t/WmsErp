# WMS ERP — ERPNext Warehouse Management for Android

A production-oriented Warehouse Management System (WMS) client for **ERPNext**, built for industrial
PDAs with built-in barcode scanners and for ordinary Android phones (camera fallback).

| | |
|---|---|
| **Language** | 100% Kotlin |
| **UI** | Jetpack Compose, Material 3, purple / white / blue palette, light + dark |
| **Architecture** | Clean Architecture (presentation / domain / data) + MVVM |
| **DI** | Hilt |
| **Networking** | Retrofit 2 + OkHttp + kotlinx.serialization against the ERPNext REST API |
| **Async** | Kotlin Coroutines & Flow |
| **Storage** | Preferences DataStore, values encrypted with an AES-256/GCM key in the Android Keystore |
| **Scanning** | Keyboard-wedge `KeyEvent` capture, vendor intent broadcasts, CameraX + ML Kit fallback |
| **Min / target SDK** | Android 10 (API 29) / API 36 |

## Project layout

```
app/src/main/java/com/wmserp/app
├── core/                # cross-cutting: scanner integration, Keystore crypto, formatters
│   ├── scanner/         # WedgeDecoder, HardwareScannerManager, vendor IntentScanParser, CameraX+ML Kit
│   └── security/        # AES/GCM StringCipher (Keystore-backed key on Android)
├── data/                # DATA layer
│   ├── remote/          # ErpNextApi (Retrofit), DTOs mapped to ERPNext DocTypes, interceptors, error parser
│   ├── local/           # SessionStore + encrypted SecureStorage (DataStore), file-backed stocktaking cache + count queue
│   ├── mapper/          # DTO <-> domain mappers
│   └── repository/      # Auth / Profile / Inventory / Order / Analytics / Settings / Stocktaking implementations
├── domain/              # DOMAIN layer (pure Kotlin, no Android imports)
│   ├── model/           # Item, Warehouse, PurchaseOrder, SalesOrder, StockEntry, DeliveryNote, Stocktaking, KPIs...
│   ├── repository/      # repository contracts
│   └── usecase/         # Login, LookupScan, ReceivePurchaseOrder, DispatchSalesOrder, analytics, stocktaking
│                        #   (count evaluator, scan resolver, offline count queue)...
├── presentation/        # PRESENTATION layer (Compose + ViewModels)
│   ├── login/ dashboard/ inventory/ scan/ orders/ stocktaking/ profile/
│   ├── components/      # KPI cards, custom bottom bar, charts, scanner listener
│   ├── navigation/      # routes
│   └── theme/           # Material 3 theme
└── di/                  # Hilt modules
```

## Screens

1. **Login** – ERPNext URL, username/email, password, *Remember me*. Advanced: API key / secret (token auth).
2. **Dashboard** – greeting, KPI cards (Total Items, Pending Orders, Revenue, Dispatched), the picker's
   *My picking today* KPIs (rows picked, average time per row, open rows, cards completed), quick actions
   (Scan, Receive, Dispatch, Report), the counter's *Stocktaking in progress* sessions, recent stock ledger activity.
3. **Inventory / Analytics** – KPI cards (Pending Deliveries, Receipts, Picklists) and tabs:
   Delivery Delays (bar chart), Activity Heatmap (7 days × 3h blocks), Stock Aging (ERPNext *Stock Ageing* report).
4. **Scan** – Item / Warehouse / Purchase Order targets, viewfinder with status text
   ("Ready to scan item barcode…"), manual entry, result cards, stock transfer (Material Transfer Stock Entry).
5. **Profile / Settings** – avatar, role, editable personal info, password change, scanner preferences,
   app language (System / English / فارسی), sign out.
6. **Orders → Receive / Dispatch** – count goods against Purchase Orders (creates *Purchase Receipt*) and pick
   against Sales Orders (creates *Delivery Note*); scanning an item barcode opens a quantity prompt for the
   matching line, prefilled with everything still open (see [Scan → quantity prompt](#scan--quantity-prompt)).
   Both documents start from ERPNext's own `make_purchase_receipt` / `make_delivery_note` mapping of the
   order, so rates, taxes, accounting dimensions and custom mandatory row fields (a *Department*, a
   *Project*…) are inherited; the app only overrides the counted quantity and the warehouse. Required fields
   that the order does not carry (read from the DocType meta, custom fields included) are filled from the
   company's default accounting dimensions or, failing that, asked for once in a dialog and remembered on the
   device, instead of ERPNext rejecting the document with *Value missing for: …*. Batch-tracked
   items on a Delivery Note are split over the warehouse's batches **first-expiry-first-out** (expired and
   disabled batches are skipped; not enough batch stock is reported before anything is created); items with
   serial numbers are refused and must be delivered from ERPNext.
7. **Orders → Pick** – the picker's tasks: open ERPNext *Pick Lists* with rows assigned to the signed-in user,
   picked row by row through strict JSON QR labels, with hidden timers and a context-aware "Create Delivery
   Note / Material Transfer / Material Issue" button for the last picker (see [Pick List workflow](#pick-list-workflow)).
8. **Orders → Count** – the counter's open *Stocktaking Sessions*: scan-to-count with batch and expiry, the ERP
   quantity (unless the session is blind), an immediate verdict (accepted / second count needed / manager review),
   counts saved locally first and synced in the background (see [Stocktaking workflow](#stocktaking-workflow)).

## ERPNext integration

All calls go through `ErpNextApi` (`app/src/main/java/com/wmserp/app/data/remote/ErpNextApi.kt`):

| Feature | Endpoint |
|---|---|
| Login | `POST /api/method/login` (`usr`, `pwd`, `device=mobile`) – `sid` cookie persisted encrypted |
| Token auth | `Authorization: token <key>:<secret>` |
| Who am I | `GET /api/method/frappe.auth.get_logged_user` |
| Documents | `GET/POST/PUT /api/resource/{doctype}[/{name}]` with `fields`, `filters`, `or_filters` |
| Counts | `frappe.client.get_count` |
| Sums | `fields=["sum(x) as total"]` on Frappe ≤ 15, `[{"SUM": "x", "as": "total"}]` on Frappe 16 (detected at runtime) |
| Receive / Dispatch drafts | `erpnext.buying.doctype.purchase_order.purchase_order.make_purchase_receipt`, `erpnext.selling.doctype.sales_order.sales_order.make_delivery_note` (`source_name`), then `POST /api/resource/...` |
| Batch stock | `erpnext.stock.doctype.batch.batch.get_batch_qty` (`item_code`, `warehouse`) + `Batch.expiry_date` |
| Required fields | `frappe.desk.form.load.getdoctype` (meta incl. custom fields) + `…accounting_dimension.get_dimensions` (company defaults) |
| Submit | `frappe.client.submit` |
| Reports | `frappe.desk.query_report.run` (Stock Ageing) |
| Picking | `wmserp_picking.api.pick_list.*` – `get_settings`, `get_my_pick_lists`, `get_pick_list`, `start_row`, `save_row_progress`, `complete_row`, `generate_document`, `get_picker_kpis` (see [Pick List workflow](#pick-list-workflow)) |
| Stocktaking | `wmserp_picking.api.stocktaking.*` – `get_my_sessions`, `get_session`, `get_items` (paged), `lookup`, `submit_count`, `sync_counts` (see [Stocktaking workflow](#stocktaking-workflow)) |
| Password | `frappe.core.doctype.user.user.update_password` |

The base URL is dynamic: `BaseUrlInterceptor` rewrites every request to the server entered at login.
CSRF: the app logs in with `device=mobile`; if a server still answers `CSRFTokenError`, `CsrfRetryInterceptor`
fetches a token and retries once. Session expiry triggers a silent re-login when *Remember me* is on,
otherwise the user is returned to the login screen.

## Pick List workflow

Physical picking runs on the standard ERPNext *Pick List* plus a small custom Frappe app,
[`erpnext/wmserp_picking`](erpnext/wmserp_picking/README.md), that adds a *WMS Settings* single, custom
fields, a QR label print format and whitelisted APIs **without modifying ERPNext core and without writing
stock ledger or GL entries**.

```
row:  Not Picked ──start_row──▶ Picking ──save_row_progress (each scan)──▶ Picked (picked == required)
card: Ready to Pick ──first row started──▶ Picking ──last row picked (card completion check)──▶ Picked
                                                                     └──generate_document──▶ draft Delivery Note / Stock Entry
```

* **Row-level assignment.** Every `Pick List Item` row has its own picker (`custom_picker`), so several
  pickers work on one card at the same time. Each row records started/completed timestamps and its
  duration; the card records its first start and last completion.
* **Strict JSON QR labels.** Picking only moves through scanned QR codes whose content is a JSON object
  with the keys configured in *WMS Settings* (default `{"item_code": …, "batch_no": …}`), printed with
  the *WMS Batch QR Label* print format. Plain barcodes, typed batches and dropdowns are not accepted.
* **The last-picker rule.** When a row reaches its required quantity the backend marks it *Picked* and,
  under a row lock, checks whether every row of the card is picked. Exactly one request observes that
  transition and is answered with `is_last_picker: true`; that picker gets the document button, everyone
  else gets *Task completed*.

**Backend (install once per site):** the app folder is published as the `wmserp_picking` branch of this
repository by CI (a subtree split of `erpnext/wmserp_picking`), because bench needs a Frappe app at the
root of what it clones.

```bash
bench get-app https://github.com/erenaydin-t/WmsErp --branch wmserp_picking
bench --site <site> install-app wmserp_picking
```

**App:**

* **Dashboard → My picking today** shows the picker's KPIs from `get_picker_kpis`: rows picked (and
  units), average time per row (and rows per hour), open rows, cards completed.
* **Orders → Pick** is the task list: submitted, *Open* pick lists with at least one row assigned to the
  signed-in user (`get_my_pick_lists`), with "my rows" progress. Drafts never appear.
* **Active picking** lists only the rows assigned to the picker, each with the **expected batch in bold**,
  source → target warehouse, expiry and picked / required. Tapping a row makes it active and starts its
  hidden timer (`start_row`). Scanning a label (PDA wedge, intent, or the camera toggle) parses it strictly
  as JSON with the cached keys and validates item **and** batch against the row: a match adds one and syncs
  immediately (`save_row_progress` with the label and the elapsed time); a wrong batch shows a large red
  *Wrong batch. Expected: X, scanned: Y*; unknown items, other pickers' rows and rows already at their
  required quantity are refused. Batches are never typed or chosen by hand; the quantity of a matching scan
  is confirmed in the prompt below.
* **Next to scan** stays visible above the list: the active row's item, its expected batch, how much is still
  open and which scanner to use (*Hardware scanner ready — pull the trigger* on a PDA, the camera button
  otherwise). The list scrolls to the row that becomes active after a completed one.
* **Complete picking** becomes enabled when every row of the picker has its required quantity. It sends
  any row the server has not confirmed yet (`complete_row`) and applies the last-picker rule: the picker who
  closed the card sees the **Create Delivery Note / Create Material Transfer / Create Material Issue**
  button (one draft, generated once), the others see *Task completed* and return to the dashboard.

## Stocktaking workflow

Physical inventory counts run on the same custom Frappe app (`wmserp_picking` 0.3.0 or later, see
[erpnext/wmserp_picking/README.md](erpnext/wmserp_picking/README.md#stocktaking)). A manager opens a
**Stocktaking Session** in ERPNext for one warehouse (or a warehouse group), the app counts, ERPNext reviews the
differences and posts the approved result as a standard *Stock Reconciliation*.

**Session flow (ERPNext):** Draft → **Start counting** (freezes the warehouse: every Stock Ledger Entry for it is
refused until the session is done, except the session's own reconciliation; snapshots every item / batch with its
ERP quantity and valuation rate into *Stocktaking Item* rows) → *Counting* → *Manager Review* (differences to
review) / *Recount* (recounts requested) → *Final Approval* → **Create Stock Reconciliation** → *Reconciled* →
submit the reconciliation → *Completed* (unfrozen). *Cancelled* unfreezes without posting anything.
Options per session: counting mode (**Assigned**: counters only see the rows assigned to them, in bulk by item
group / brand / batch / location; **Open**: anyone with the app counts anything), *require second count*, duplicate
policy (*Lock after count* → "Already counted by Ali" / *Allow additional counts*), *blind count* (ERP quantities
hidden from counters), a quantity tolerance, an item group / brand filter and *include zero stock*.

**Counting in the app (Orders → Count, or *Stocktaking in progress* on the dashboard):**

* Opening a session downloads its rows in pages of 1000 and caches them on the device, so scanning, matching and
  counting never wait for the network.
* **Scan → identify → count → submit → ready for the next scan.** A JSON QR label (`{"item_code", "batch_no"}`)
  resolves to exactly one row; a plain item barcode resolves to the item and, when it has several batches in the
  warehouse, shows a batch chooser. The count card shows item, batch, expiry, the ERP quantity (unless the session
  is blind) and an empty quantity field with a *Same as ERP (N)* chip. The search panel finds rows by item, name or
  batch, and an item that is not in the snapshot can be added to the session when the server allows it.
* **Rules mirrored on the device** (`CountEvaluator`, the same logic as the server's `rules.py`): a count that
  matches the ERP quantity (within the tolerance) is accepted at once; a mismatch asks the same counter for a
  **second count**; a second count that still differs sends the row to **Manager Review**. A row already counted by
  someone else, a finalized row, a closed session or a row not assigned to the counter is refused with the reason
  before anything is sent.
* **Offline-safe.** Every count is written to a file-backed queue first (*Saved locally · Pending sync*) and
  synchronised with `sync_counts`, which replays the queue in order with one savepoint per entry; a `client_ref`
  per count makes replays idempotent. The server's verdict replaces the local one when it arrives (for example a
  second count requested for a row whose ERP quantity the device did not know); refusals are shown with the error
  tone; the sync retries every 15 s while the session is open and the badge on the sync icon shows the queue size.
* The progress card shows the session totals (counted / total, matches, variances, pending review) and the
  counter's own open rows; the last count stays visible for confirmation. Every count is kept in the *Stocktaking
  Count* log with counter, server time, device time and outcome; nothing is ever overwritten.

**Manager tools in ERPNext:** the session form's dashboard (total / counted / not counted / matched / variance /
recount required / pending review, quantity and value variance), *Assign / Unassign* with a preview of the matching
rows, *Request recount* and *Accept count* per row or in bulk from the *Stocktaking Item* list (each row shows its
full count history), *Accept all*, *Approve*, *Create Stock Reconciliation*, and the script reports **Stocktaking
Variance** (sorted by absolute value difference) and **Stocktaking Uncounted Items**.

## Hardware scanners

* **Keyboard wedge** (default on PDAs): `MainActivity.dispatchKeyEvent` forwards key events to
  `HardwareScannerManager`, which reassembles bursts of characters (Enter/Tab terminated, or idle-flushed)
  without needing a focused text field or the soft keyboard. Text fields on scanning screens use
  `Modifier.scannerAwareFocus()` so wedge input goes to the field while it is focused.
* **Intent output**: broadcasts from Zebra DataWedge, Honeywell, Urovo, Newland, Sunmi, Chainway, Datalogic,
  Point Mobile and others are parsed by `IntentScanParser`. For DataWedge you can also use the generic action
  `com.wmserp.app.SCAN`.
* **Camera fallback**: CameraX + ML Kit barcode scanning (all 1D/2D formats) with graceful runtime permission
  handling. On the Receive, Dispatch and Pick screens the camera is a modal sheet (`CameraScannerSheet`) with a
  large viewfinder, the frame and scan line, a close button and, while picking, the row to scan next; it opens
  from the camera button (offered when no hardware scanner is detected) or by itself in *Camera* mode, never
  behind the list.
* Mode is selectable in *Profile → Scanner* (Auto / Hardware / Camera) with beep & vibration feedback; a rejected
  scan (wrong batch, unknown item) plays the error tone and a double vibration instead.

### Scan → quantity prompt

Scanning one label per unit does not scale to a pallet of 200, so on Receive, Dispatch and Pick a **matching
scan opens a quantity prompt** (`ScanQuantityDialog`) prefilled with everything still open on that line
(required − picked, or ordered − counted). One scan and one tap on **Take N** count the whole quantity; the
picker lowers the number with the large −/+ buttons, by typing (the field selects itself when tapped) or
takes it all with the **All** chip. Values above what is open are refused before anything reaches ERPNext,
scans are ignored while the prompt is waiting, and on the Pick screen the row's hidden timer starts with the
scan so the time spent in the prompt counts. *Profile → Scanner → Ask quantity after each scan* switches back
to the classic "every scan adds one unit" behaviour for pickers who prefer scan-to-count.

### Zebra DataWedge profile (optional)

Keyboard wedge works out of the box. If you prefer intent output, create a DataWedge profile for
`com.wmserp.app` with **Intent output** enabled, *Intent action* `com.wmserp.app.SCAN`, *Intent delivery*
"Broadcast intent", and keep the default `com.symbol.datawedge.data_string` extra. Other vendors: enable
"Intent/Broadcast" output in the scanner settings app; the default actions listed in `IntentScanParser` are
recognised automatically.

## Building

```bash
./gradlew assembleDebug            # debug APK  -> app/build/outputs/apk/debug/
./gradlew assembleRelease          # release APK (signed with your keystore, see below)
./gradlew testDebugUnitTest        # unit tests (JUnit 4, MockK, Turbine, MockWebServer)
./gradlew connectedDebugAndroidTest  # Compose UI tests on a device/emulator
```

Requirements: JDK 17+, Android SDK with platform 36.

### Release signing

Create `keystore.properties` in the project root (git-ignored):

```
storeFile=/absolute/path/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

or provide the same values through environment variables `WMSERP_KEYSTORE_PATH`, `WMSERP_KEYSTORE_PASSWORD`,
`WMSERP_KEY_ALIAS`, `WMSERP_KEY_PASSWORD`. Without either, **both** build types are signed with the committed
internal-distribution key `app/keystore/internal-testing.jks` (alias `wmserp`, password `wmserp-internal`).
That key is what makes the in-app updater work: Android only installs an update over an existing app when
both APKs carry the same signature, and the per-machine debug keystore would differ on every CI runner.
Anyone with the repository can sign with it, so switch to your own keystore (secrets above) before handing
the app to people outside your warehouse, and never upload such an APK to Google Play.

### Versioning

CI builds are versioned `<base>.<workflow run number>` (`versionName`) with the run number as `versionCode`;
the base (`1.1`) lives in `app/build.gradle.kts` (`baseVersion`) and `.github/workflows/android.yml`
(`BASE_VERSION`). Local builds are `1.1.0-dev` / `versionCode 1` and therefore always accept an update.

## CI / CD (GitHub Actions)

`.github/workflows/android.yml` runs on every push to `main` (and `claude/**` branches), on pull requests and
manually:

1. Backend rules tests, lint + unit tests (`lintDebug testDebugUnitTest`)
2. `assembleDebug`, `assembleRelease`, `bundleRelease`
3. Uploads artifacts **wmserp-debug-apk**, **wmserp-release-apk**, **wmserp-release-aab** and test reports
4. On `main` only: publishes a **GitHub Release** `v<version>` with the release APK attached (the feed of
   the in-app updater) and refreshes the `wmserp_picking` backend branch

For Play-ready signed builds add the repository secrets `KEYSTORE_BASE64` (base64 of the `.jks`),
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

A second job runs the Compose UI tests on an emulator when the workflow is dispatched manually with
*Run Compose UI tests on an emulator* enabled. The manual run also offers *publish_release* and
*publish_backend* to publish the dispatched branch (a GitHub Release for the updater, the
`wmserp_picking` branch and its version tag) before it is merged, for testing; a push whose commit
message contains `[publish]` does the same. `[publish-backend]` publishes only the backend branch and
tag (a server-side fix that must reach benches without offering PDAs a new APK) and `[publish-release]`
only the GitHub Release.

## In-app updates

The app updates itself from the repository's GitHub Releases, so a PDA never needs a computer or a store:

1. On start the dashboard asks `GET https://api.github.com/repos/erenaydin-t/WmsErp/releases/latest`
   (unauthenticated, at most once every 6 hours; *Profile → App updates → Check for updates* always
   checks). The repository is set at build time (`BuildConfig.UPDATE_GITHUB_REPO`).
2. When the release tag (`v1.1.57`) is newer than the installed `versionName`, a banner on the dashboard
   and the card in Profile offer **Update now**; *Later* hides the banner until the next check.
3. The APK asset is streamed to `filesDir/updates/` with a progress bar (a finished download is reused,
   partial files are deleted). Downloads keep running while you move between screens
   (`AppUpdateManager` is a process-wide singleton).
4. When the download completes the system installer opens through a `FileProvider`; **Install** in the
   banner or card opens it again. On the first update Android asks you to allow WMS ERP to *install
   unknown apps* (`REQUEST_INSTALL_PACKAGES`); the card explains this and takes you to the setting.
5. The app reopens with the new version; the release notes are the commit message of the `main` build.

Requirements for the update to install: same signature as the installed build (see *Release signing*)
and a higher `versionCode` (see *Versioning*). A device that still runs a build from before the stable key
was introduced must be updated by hand once (uninstall, then install any newer APK).

## Localization (English / فارسی)

The app ships in English and Persian (Farsi) with full right-to-left support.

* Every user-facing string lives in `app/src/main/res/values/strings.xml`; the Persian translation is
  `values-fa/strings.xml` (same keys, same `%1$s` placeholders). A missing key in either file fails the
  resource check, so the two stay in sync.
* Language switching is instant and does not restart the app: **Profile → Settings → Language** offers
  *System*, *English* and *فارسی*. The choice is stored in DataStore (`app_language`) and applied by
  `LocalizedContent`, which provides a locale-specific `Context`, `Configuration` and `LayoutDirection`
  to the whole Compose tree. `LocaleDefaults` keeps `java.util.Locale` in step so day/month names in the
  analytics charts follow the app language.
* *System* follows the device locale, and `android:localeConfig` (`res/xml/locales_config.xml`) lets
  Android 13+ users pick the app language from **Settings → Apps → WMS ERP → Language** as well.
* ViewModels and use cases never build display text: app-generated messages carry an `ErrorCode` /
  string-resource id (`UiText.Res`) and are resolved in the UI, so they translate automatically.
  Messages that come from the ERPNext server (`_server_messages`, `exc_type`) are shown verbatim
  (`UiText.Plain`) because they arrive in the server’s own language.
* `android:supportsRtl="true"` mirrors layouts, and directional icons (back arrow, orders icon) use the
  `AutoMirrored` Material icon variants. Numbers keep ASCII digits so barcodes, quantities and document
  names match ERPNext exactly.

To add another language, copy `values/strings.xml` to `values-<lang>/strings.xml`, translate the values,
add the locale to `locales_config.xml` and a case to `AppLanguage` (with its label in `UiText.kt`).

## Google Play compliance & security

* Permissions: only `INTERNET` and `CAMERA` (camera declared as not required, requested at runtime with rationale).
* No hard-coded secrets; credentials are AES-256/GCM encrypted with a hardware-backed Keystore key and excluded
  from backups (`backup_rules.xml`, `data_extraction_rules.xml`).
* Cleartext HTTP is permitted for on-premise LAN servers but the login screen warns when the URL is not HTTPS.
* Target SDK 36, adaptive + monochrome launcher icon, edge-to-edge UI, no orientation lock.

## Checking a server before testing on a device

`tools/erpnext_smoke_test.py` replays every request the app makes (login, dashboard KPIs, analytics,
items, bins, warehouses, orders, the Stock Ageing report, the picking API and the stocktaking API) against a real site with
an API key and prints which ones fail and why (permission, missing field, missing app, report filters):

```bash
python3 tools/erpnext_smoke_test.py --url https://erp.example.com --key API_KEY --secret API_SECRET \
    --barcode 8690000000017            # optional: exercise the barcode lookup
    # --pick-list STO-PICK-00001       # optional: run start/save/complete/generate on a test pick list (writes!)
```

## Testing

* **Unit tests** (`app/src/test`): use cases, URL normaliser, ERPNext error parser, query builder, session store,
  repositories against a real Retrofit/OkHttp stack with MockWebServer, keyboard-wedge decoder, intent parser,
  AES/GCM cipher, formatters, the strict QR label parser and scan validation, the picking use cases and
  repository, the stocktaking use cases (paged download with cache fallback, scan resolution, the device-side
  count evaluator, the offline count queue), the file-backed stocktaking store and repository, and ViewModels
  (Login, Dashboard, Scan, Receive, Pick List, Counting, Main) including the resource-id based (`UiText`)
  messages they emit.
* **Compose UI tests** (`app/src/androidTest`): login form validation and submission, dashboard KPIs, quick
  actions and bottom navigation, hardware scanner input delivered to the scan screen, manual code entry, and
  the picking screen (only the picker's rows, expected batch, no manual inputs, wrong-batch alert,
  task-completed vs. create-document states) and the counting screen (scan → count card with batch, expiry and
  ERP quantity, match accepted, second count on a mismatch, batch chooser, *already counted by* refusal, pending
  sync badge).
* **Backend rules** (`erpnext/wmserp_picking`): `python -m unittest discover -p "test_*.py"` covers row/card
  completion, quantity validation, JSON QR parsing and matching, purpose mapping, permissions and KPI
  aggregation, and the stocktaking rules (count evaluation, second counts, duplicate policies, assignment,
  session status transitions, progress totals, reconciliation rows) without a bench.
