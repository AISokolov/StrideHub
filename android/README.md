# StrideHub Android

A Kotlin companion for **OnePlus Watch → OHealth → Health Connect → StrideHub**.
Reads steps and sleep only. The app and Glance widget work from a private DataStore
cache without an Express server. No changes are required in `../client` or `../server`.

## Open, build, install

1. Use Android Studio **Otter 2025.2.1 or newer** with support for AGP 8.13.2.
   The installed Android Studio 2026.2.2 can open this project.
2. Open this `android` directory (the one containing `settings.gradle.kts`).
3. In SDK Manager install **Android SDK Platform 36**, **Build Tools 36.0.0**, and
   Android SDK Platform-Tools. Accept Gradle sync/downloads.
4. Set **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK**
   to **JDK 21** (17–23 supported). A newer Studio may default to JDK 25, which this
   Gradle wrapper does not support. On this machine, the installed
   `C:\Users\Alex\.jdks\openjdk-23.0.2` also works.
5. Select your phone in the device menu and press **Run**.

From PowerShell in this directory, with a compatible Java on PATH:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The installable APK is `app/build/outputs/apk/debug/app-debug.apk`.
On macOS/Linux use `sh ./gradlew` instead. The Gradle 8.13 wrapper is included and
its distribution download is checksum-verified. Dependencies are pinned; the first
build needs internet. `local.properties` is machine-specific and ignored by Git;
Android Studio creates it when you open the project.

### Physical OnePlus phone

- Requires Android 9/API 28 or later and a supported Health Connect installation.
- Enable Developer options (tap the build/version number seven times in About
  device), then enable USB debugging. Connect a data-capable USB cable and accept
  the computer's debugging prompt on the phone.
- Run from Android Studio, or use the SDK's `platform-tools/adb`:

```powershell
adb devices
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Alternatively copy the APK to the phone, open it in Files, and allow installation
from that source when Android asks. This is a development APK; publishing requires
your own release signing key and the appropriate Health Connect Play declarations.

## Connect OHealth and Health Connect

1. Sync the watch in OHealth and enable its Health Connect sharing for **steps** and
   **sleep**. In Health Connect, inspect those categories and confirm OHealth records
   appear for today/the latest night.
2. Open StrideHub and tap **Grant Permissions**. Allow both steps and sleep reads.
   No write permission or additional health metric is requested.
3. Tap **Refresh Data**. Compare steps with Health Connect's daily aggregate, and
   sleep start/end with the OHealth session. Health Connect may combine/prioritize
   other step sources, so its aggregate need not equal OHealth's raw count.
4. Optionally choose **Allow background reads**. This appears only when the device
   supports it. Denial does not prevent foreground use. If permissions were repeatedly
   denied, use **Manage Health Connect** to grant them in Settings.

Android 14+ includes Health Connect. On older supported Android versions, install
or update the separate Health Connect app using the app's install/update link.
Unavailable devices display a status and keep cached data visible.
See Google's [Health Connect setup guide](https://developer.android.com/health-and-fitness/health-connect/get-started).

## Data and date behavior

- Tap the date above the cards to select one of the last 30 calendar dates. No
  extended history permission is requested; Health Connect may limit available history.
- Steps use `StepsRecord.COUNT_TOTAL` aggregation over local midnight to the next
  local midnight. DST days may have 23 or 25 hours. Missing aggregates show **—**, not
  a fabricated zero.
- Sleep is paginated and assigned to the phone-local **wake-up date**, including a
  session ending exactly at midnight. Original timestamp offsets and source package
  are preserved. After travel, refreshing reinterprets the selected/recent dates in
  the current phone timezone. Old step values from a different zone are hidden.
- Known sleeping stages (sleeping/light/deep/REM) are summed without overlapping
  intervals. **Recorded sleep** means only supplied sleep stages; missing stage gaps
  are not inferred. Without known stages, the value is explicitly **session duration**.
- Today's screen/widget shows the latest cached session; a historical date shows
  its latest session. All sessions for refreshed dates are cached and uploaded.
- Every refresh re-reads today, the previous two days, and the selected date. This
  catches delayed OHealth writes and replaces removed/changed records for those days.
  The latest accessible sleep date is included even when it is older than that window.
  Select older dates to reconcile older corrections/deletions. This is a small daily
  cache, not a full-history Health Connect mirror.
- Day keys and Health Connect record IDs prevent duplicates. Successful uploads
  acknowledge a particular revision, so an in-flight upload cannot clear a newer edit.
  Pending records are kept across process death, reboots, and network failures.

## Home-screen widget

Long-press an empty area of the phone's home screen → **Widgets → StrideHub** → drag
the widget onto the home screen. Resize it to roughly 2×2 or 4×2 cells. It displays
today's cached steps, latest sleep (or labeled session duration), and today's last
refresh time. Tap anywhere to open the app, which refreshes automatically; the app
also has a manual refresh button. This works even when background reads are unsupported.

The widget updates after local refresh and receives periodic launcher redraws.
After midnight it shows a placeholder until today's cache exists; a launcher may
delay that redraw. It never fetches data from the backend.

## Automatic synchronization

One unique WorkManager job requests a refresh about every **30 minutes**, above the
15-minute minimum. There is no permanent foreground service. Reads run only after
checking the background-read feature **and** its separate permission. Otherwise the
job preserves the cache and the app explains that opening it is required to refresh.

Uploads use separate unique work with a connected-network constraint. Local health
reads therefore still work offline. Temporary network errors and HTTP 408/429/5xx
retry with exponential backoff. Authentication, certificate, redirect, and missing
endpoint errors preserve pending data and show a useful message; a later periodic
run or manual sync can try again. At most ten days are uploaded per run.

WorkManager execution is **not exact**. Doze, battery saver, force-stop, and OnePlus
battery policies may defer work. Reopen after force-stop. If needed, allow background
activity for StrideHub in the phone's battery settings; this still cannot grant
Health Connect background-read access or guarantee a fixed schedule.
See [Android's WorkManager scheduling guidance](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).

## Optional Express connection

Open **Settings**, enter the server's HTTPS origin (for example
`https://stridehub.example.com`) and a **server-issued bearer access token**, then
save. Saving enables automatic upload of cached data. Do not enter `/api/health/sync`
in the URL: the app appends it. Clearing the URL and saving disconnects and removes
the token. Changing settings marks cached days pending for the configured account.

URL/token are AES-GCM encrypted with a non-exportable Android Keystore key. Tokens
and health payloads are not logged. Health cache remains in app-private storage.
Backup/device transfer is excluded; clearing app storage deletes the cache and token.
Release builds reject cleartext HTTP and use the system certificate trust store.

### Future endpoint contract (backend unchanged)

`POST /api/health/sync`, `Authorization: Bearer <token>`, JSON content type, and an
`Idempotency-Key: <date>-<revision>` header. Payload:

```json
{
  "date": "2026-11-15",
  "zoneId": "Europe/Ljubljana",
  "steps": 4319,
  "sleepSessions": [{
    "id": "health-connect-record-id",
    "startTime": "2026-11-14T23:00:00+01:00",
    "endTime": "2026-11-15T07:00:00+01:00",
    "durationMinutes": 450,
    "sessionDurationMinutes": 480,
    "durationKind": "recorded_sleep",
    "source": "source.package.from.health.connect"
  }],
  "refreshedAt": "2026-11-15T12:00:00Z",
  "revision": "unique-revision-id",
  "source": "health_connect"
}
```

Example numbers are documentation only. `steps` may be `null`; an empty sleep array
means no sessions for that refreshed date. The server must authenticate the user,
validate the payload, upsert by **user + source + date**, and replace that day's sleep
set (including deletions), with session IDs unique per user/source. Handle retries
idempotently and return **2xx only after durable acceptance**. This client supports
a manually provisioned revocable token, not an OAuth login/refresh flow. A 401/403
requires replacing the token. The endpoint and token issuance are future backend work.

### Development on the same Wi-Fi

Use the PC's LAN IP from `ipconfig`, never `localhost` on the phone. Keep the phone
and PC on the same network and allow the chosen HTTPS port through Windows Firewall
for private networks. Put an HTTPS reverse proxy in front of the local Express port.
For example, a separate Caddy configuration (replace the IP and Express port):

```caddyfile
https://192.168.1.10:8443 {
    tls internal
    reverse_proxy 127.0.0.1:3000
}
```

Run Caddy with that file. Copy **only its public root CA certificate** to the phone
and install it through Android Settings → Security → Install a certificate → CA
certificate. The **debug APK only** trusts user-installed CAs; TLS hostname validation
still applies, so the IP must match the certificate. Configure
`https://192.168.1.10:8443` in the app. Remove the development CA when finished.
See [Caddy's internal TLS documentation](https://caddyserver.com/docs/caddyfile/directives/tls).
Production requires a normally trusted HTTPS certificate. HTTP is not enabled even
in debug builds. An unavailable endpoint leaves uploads pending and does not affect
local readings or the widget.

## Verification and source map

Run the Gradle checks above; see [TESTING.md](TESTING.md) for the device checklist.
Debug/release builds and all 12 unit tests passed; lint passed with no errors
(17 advisory warnings). An emulator system crash prevented reliable runtime smoke
testing; physical OnePlus behavior has not been verified.
Unit tests cover DST/midnight, stage overlap, missing values, JSON round trips,
duplicate prevention, deletions, stale acknowledgements, HTTPS validation, and HTTP
retry classification. These tests do not establish physical OnePlus/OHealth behavior.

- `data/`: Health Connect reads, cache, Keystore settings, authenticated uploads.
- `sync/`: periodic local refresh and network-constrained upload workers.
- `ui/`, `MainActivity.kt`: Compose home/date selection/settings and permission flow.
- `widget/`: responsive Glance widget; `res/xml/`: widget metadata and backup policy.
- `PermissionsRationaleActivity.kt`: permission rationale/privacy screen for both
  standalone and Android-integrated Health Connect.
