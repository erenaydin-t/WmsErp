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
│   ├── local/           # SessionStore + encrypted SecureStorage (DataStore)
│   ├── mapper/          # DTO <-> domain mappers
│   └── repository/      # Auth / Profile / Inventory / Order / Analytics / Settings implementations
├── domain/              # DOMAIN layer (pure Kotlin, no Android imports)
│   ├── model/           # Item, Warehouse, PurchaseOrder, SalesOrder, StockEntry, DeliveryNote, KPIs...
│   ├── repository/      # repository contracts
│   └── usecase/         # Login, LookupScan, ReceivePurchaseOrder, DispatchSalesOrder, analytics...
├── presentation/        # PRESENTATION layer (Compose + ViewModels)
│   ├── login/ dashboard/ inventory/ scan/ orders/ profile/
│   ├── components/      # KPI cards, custom bottom bar, charts, scanner listener
│   ├── navigation/      # routes
│   └── theme/           # Material 3 theme
└── di/                  # Hilt modules
```

## Screens

1. **Login** – ERPNext URL, username/email, password, *Remember me*. Advanced: API key / secret (token auth).
2. **Dashboard** – greeting, KPI cards (Total Items, Pending Orders, Revenue, Dispatched), the picker's
   *My picking today* KPIs (rows picked, average time per row, open rows, cards completed), quick actions
   (Scan, Receive, Dispatch, Report), recent stock ledger activity.
3. **Inventory / Analytics** – KPI cards (Pending Deliveries, Receipts, Picklists) and tabs:
   Delivery Delays (bar chart), Activity Heatmap (7 days × 3h blocks), Stock Aging (ERPNext *Stock Ageing* report).
4. **Scan** – Item / Warehouse / Purchase Order targets, viewfinder with status text
   ("Ready to scan item barcode…"), manual entry, result cards, stock transfer (Material Transfer Stock Entry).
5. **Profile / Settings** – avatar, role, editable personal info, password change, scanner preferences,
   app language (System / English / فارسی), sign out.
6. **Orders → Receive / Dispatch** – count goods against Purchase Orders (creates *Purchase Receipt*) and pick
   against Sales Orders (creates *Delivery Note*); scanning an item barcode increments the matching line.
7. **Orders → Pick** – the picker's tasks: open ERPNext *Pick Lists* with rows assigned to the signed-in user,
   picked row by row through strict JSON QR labels, with hidden timers and a context-aware "Create Delivery
   Note / Material Transfer / Material Issue" button for the last picker (see [Pick List workflow](#pick-list-workflow)).

## ERPNext integration

All calls go through `ErpNextApi` (`app/src/main/java/com/wmserp/app/data/remote/ErpNextApi.kt`):

| Feature | Endpoint |
|---|---|
| Login | `POST /api/method/login` (`usr`, `pwd`, `device=mobile`) – `sid` cookie persisted encrypted |
| Token auth | `Authorization: token <key>:<secret>` |
| Who am I | `GET /api/method/frappe.auth.get_logged_user` |
| Documents | `GET/POST/PUT /api/resource/{doctype}[/{name}]` with `fields`, `filters`, `or_filters` |
| Counts | `frappe.client.get_count` |
| Submit | `frappe.client.submit` |
| Reports | `frappe.desk.query_report.run` (Stock Ageing) |
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
  required quantity are refused. There are no manual quantity or batch inputs.
* **Complete picking** becomes enabled when every row of the picker has its required quantity. It sends
  any row the server has not confirmed yet (`complete_row`) and applies the last-picker rule: the picker who
  closed the card sees the **Create Delivery Note / Create Material Transfer / Create Material Issue**
  button (one draft, generated once), the others see *Task completed* and return to the dashboard.

## Hardware scanners

* **Keyboard wedge** (default on PDAs): `MainActivity.dispatchKeyEvent` forwards key events to
  `HardwareScannerManager`, which reassembles bursts of characters (Enter/Tab terminated, or idle-flushed)
  without needing a focused text field or the soft keyboard. Text fields on scanning screens use
  `Modifier.scannerAwareFocus()` so wedge input goes to the field while it is focused.
* **Intent output**: broadcasts from Zebra DataWedge, Honeywell, Urovo, Newland, Sunmi, Chainway, Datalogic,
  Point Mobile and others are parsed by `IntentScanParser`. For DataWedge you can also use the generic action
  `com.wmserp.app.SCAN`.
* **Camera fallback**: CameraX + ML Kit barcode scanning (all 1D/2D formats) with graceful runtime permission handling.
* Mode is selectable in *Profile → Scanner* (Auto / Hardware / Camera) with beep & vibration feedback.

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
`WMSERP_KEY_ALIAS`, `WMSERP_KEY_PASSWORD`. Without either, release builds are signed with the debug keystore so
CI always produces an installable APK (never upload such an APK to Google Play).

## CI / CD (GitHub Actions)

`.github/workflows/android.yml` runs on every push to `main` (and `claude/**` branches), on pull requests, on
`v*` tags and manually:

1. Lint + unit tests (`lintDebug testDebugUnitTest`)
2. `assembleDebug`, `assembleRelease`, `bundleRelease`
3. Uploads artifacts **wmserp-debug-apk**, **wmserp-release-apk**, **wmserp-release-aab** and test reports
4. On `v*` tags the APKs/AAB are attached to a GitHub Release

For Play-ready signed builds add the repository secrets `KEYSTORE_BASE64` (base64 of the `.jks`),
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

A second job runs the Compose UI tests on an emulator when the workflow is dispatched manually with
*Run Compose UI tests on an emulator* enabled.

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
items, bins, warehouses, orders, the Stock Ageing report and the picking API) against a real site with
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
  repository, and ViewModels (Login, Dashboard, Scan, Receive, Pick List, Main) including the resource-id
  based (`UiText`) messages they emit.
* **Compose UI tests** (`app/src/androidTest`): login form validation and submission, dashboard KPIs, quick
  actions and bottom navigation, hardware scanner input delivered to the scan screen, manual code entry, and
  the picking screen (only the picker's rows, expected batch, no manual inputs, wrong-batch alert,
  task-completed vs. create-document states).
* **Backend rules** (`erpnext/wmserp_picking`): `python -m unittest discover -p "test_*.py"` covers row/card
  completion, quantity validation, JSON QR parsing and matching, purpose mapping, permissions and KPI
  aggregation without a bench.
