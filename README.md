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
2. **Dashboard** – greeting, KPI cards (Total Items, Pending Orders, Revenue, Dispatched), quick actions
   (Scan, Receive, Dispatch, Report), recent stock ledger activity.
3. **Inventory / Analytics** – KPI cards (Pending Deliveries, Receipts, Picklists) and tabs:
   Delivery Delays (bar chart), Activity Heatmap (7 days × 3h blocks), Stock Aging (ERPNext *Stock Ageing* report).
4. **Scan** – Item / Warehouse / Purchase Order targets, viewfinder with status text
   ("Ready to scan item barcode..."), manual entry, result cards, stock transfer (Material Transfer Stock Entry).
5. **Profile / Settings** – avatar, role, editable personal info, password change, scanner preferences, sign out.
6. **Orders → Receive / Dispatch** – count goods against Purchase Orders (creates *Purchase Receipt*) and pick
   against Sales Orders (creates *Delivery Note*); scanning an item barcode increments the matching line.

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

## Google Play compliance & security

* Permissions: only `INTERNET` and `CAMERA` (camera declared as not required, requested at runtime with rationale).
* No hard-coded secrets; credentials are AES-256/GCM encrypted with a hardware-backed Keystore key and excluded
  from backups (`backup_rules.xml`, `data_extraction_rules.xml`).
* Cleartext HTTP is permitted for on-premise LAN servers but the login screen warns when the URL is not HTTPS.
* Target SDK 36, adaptive + monochrome launcher icon, edge-to-edge UI, no orientation lock.

## Testing

* **Unit tests** (`app/src/test`): use cases, URL normaliser, ERPNext error parser, query builder, session store,
  repositories against a real Retrofit/OkHttp stack with MockWebServer, keyboard-wedge decoder, intent parser,
  AES/GCM cipher, formatters, and ViewModels (Login, Dashboard, Scan, Receive, Main).
* **Compose UI tests** (`app/src/androidTest`): login form validation and submission, dashboard KPIs, quick
  actions and bottom navigation, hardware scanner input delivered to the scan screen, manual code entry.
