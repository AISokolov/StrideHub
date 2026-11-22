# StrideHub Android

A Kotlin companion for **OnePlus Watch → OHealth → Health Connect → StrideHub**.
Reads steps and sleep from Health Connect and downloads Garmin workouts through the
existing authenticated Express backend. Home and Glance display a private DataStore
cache offline. See [the root README](../README.md) for dashboard authentication and
the completed Garmin integration.

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
  session ending exactly at midnight. Timestamps use the device timezone offset at each instant; the source package
  and stable Health Connect record ID are preserved. After travel, refreshing reinterprets the selected/recent dates in
  the current phone timezone. Old step values from a different zone are hidden.
- Known sleeping stages (sleeping/light/deep/REM) are summed without overlapping
  intervals. **Recorded sleep** means only supplied sleep stages; missing stage gaps
  are not inferred. Without known stages, the value is explicitly **session duration**.
- Today's screen/widget shows the latest cached session; a historical date shows
  its latest session. All sessions for refreshed dates are cached and uploaded.
- Every refresh re-reads today, the previous seven days, and the selected date. This
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
today's cached steps (👣), latest sleep/session duration (🌙), and today's last
health refresh time. Garmin workouts appear only when the cached date matches today
in the backend's configured timezone. Empty days reserve no Garmin section. Tap
anywhere to open the app, which refreshes automatically; the app
also has a manual refresh button. This works even when background reads are unsupported.

The widget updates after local refresh and receives periodic launcher redraws.
After midnight it shows a placeholder until today's cache exists; a launcher may
delay that redraw. It never fetches data from the backend.

The app's existing date picker also selects Garmin's date. **Refresh Data** requests
fresh Garmin data independently of Health Connect permission or read failures.
See [POLISH_REPORT.md](POLISH_REPORT.md) for the November 18 regression verification.

## Automatic synchronization

The existing unique `stridehub-health-refresh` WorkManager job refreshes local data
about every **30 minutes**. Cache and widget updates happen before uploads and do
not require a network. Background Health Connect reads still require the separate
background permission. If unavailable, open the app to refresh; cached uploads may
continue while the basic steps/sleep permissions remain granted.

The existing unique `stridehub-upload` job requires a connected network. It uploads
changed pending dates, at most ten per attempt, including today's rolling eight-day
recovery window. Unchanged acknowledged revisions are skipped. Transient IO/timeouts,
408, 429, and 5xx responses use WorkManager exponential backoff. A failed date does
not prevent other dates from uploading. A 400 or invalid acknowledgement holds that
revision until the data or settings change. A 401/403 blocks all further attempts,
including manual attempts, until the URL/token changes. Saving identical credentials
does not clear that block. No credentials or response bodies are logged.

Last successful upload advances only after the server confirms `success`, the date,
and the saved steps/session counts. It may represent partial progress when another
day failed; the status message and pending count show remaining work. WorkManager
execution is not exact: Doze, force-stop, and OnePlus battery restrictions can defer
it. Reopen after force-stop. See [WorkManager scheduling guidance](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).

## Development Express connection

The existing server is `http://192.168.0.79:3001`. Keep the PC and phone on the same
trusted Wi-Fi; `localhost` on the phone refers to the phone. The server must listen
on its LAN interface, and Windows Firewall must permit port 3001 on your private
network. This milestone does not change the Express backend or database.

1. Copy `sync.local.properties.example` to **`sync.local.properties`** in this Android
   directory. This local file is ignored by Git.
2. Set `STRIDEHUB_SYNC_TOKEN` to the same value configured as **`HEALTH_SYNC_TOKEN`**
   in the existing Express environment. Set `STRIDEHUB_SYNC_URL` if the PC address
   changes. Do not add `/api/health/sync` to the base URL.
3. Alternatively set environment variables `STRIDEHUB_SYNC_TOKEN` and
   `STRIDEHUB_SYNC_URL` before building; these override the file. Do not put real
   credentials in command-line Gradle arguments, source files, or committed properties.
4. Build and install from this directory:

   ```powershell
   $env:JAVA_HOME = 'C:\Users\Alex\.jdks\jbr-21.0.11'
   .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
   adb install -r .\app\build\outputs\apk\debug\app-debug.apk
   ```

   If `adb` is not on PATH, use the SDK's `platform-tools/adb.exe`, or Android Studio
   Run. `install -r` preserves the existing cache/settings; do not uninstall or clear
   app storage as part of this update.
5. Open StrideHub, grant steps and sleep access, then **Settings → Sync now**. This
   refreshes the rolling window, updates the cache/widget, and uses the same uploader
   as background work. Settings displays current state and the last successful upload.
   The existing home-screen **Sync to Server** button uses this same flow.

Debug BuildConfig defaults to the development URL and injects only a locally supplied
token. Release BuildConfig contains empty defaults and never embeds the development
token. Existing encrypted settings take precedence over build defaults. You can edit
the URL/token in Settings; a blank token keeps the existing token for the same origin.
Changing origins requires a new token. Clearing the URL explicitly disables sync,
including build defaults, without deleting health data.

**Security:** Generated BuildConfig and APKs contain the development token and must
remain uncommitted/unshared. They are under ignored `app/build/`; do not print them
or enable verbose build scans/debug logging with credentials configured. APK contents
can be inspected: this is personal development authentication, not production user
authentication. HTTP exposes the token and health data to the network; use only your
trusted development LAN. Debug network policy permits HTTP and user-installed CAs;
release rejects HTTP and trusts system CAs only. Settings entered at runtime are
AES-GCM encrypted using Android Keystore. No OAuth/refresh-token flow is implemented.

### Existing backend contract and verification

`POST /api/health/sync` uses `Authorization: Bearer <token>` and JSON:

```json
{
  "date": "2026-11-15",
  "steps": 6508,
  "source": "health_connect",
  "sleepSessions": [{
    "id": "stable-health-connect-record-id",
    "startTime": "2026-11-15T04:46:00+01:00",
    "endTime": "2026-11-15T13:00:00+01:00",
    "durationMinutes": 494,
    "source": "com.heytap.health.international"
  }]
}
```

Missing steps are **omitted**, never converted to zero. Complete sleep sessions are
assigned to their device-local wake-up date without splitting at midnight. Health
Connect record IDs are globally unique and stable; the fallback includes source and
session instants. Uploads deduplicate repeated IDs. Stage-aware duration semantics
remain unchanged from the existing app.

Verify a date through the authenticated read endpoint after a real phone sync. With
`STRIDEHUB_SYNC_TOKEN` already in the shell environment (do not print it):

```powershell
$headers = @{ Authorization = "Bearer $env:STRIDEHUB_SYNC_TOKEN" }
$day = Get-Date -Format 'yyyy-MM-dd'
Invoke-RestMethod -Uri "http://192.168.0.79:3001/api/health/daily?date=$day" -Headers $headers
```

Compare steps, session IDs/timestamps/durations against Health Connect. Repeat sync:
steps must be replaced and session counts must not grow. All `/api/health/*` data
endpoints need the same token. For older days, replace `$day` with `YYYY-MM-DD`.

The current backend upserts steps by date and sleep by ID. It **does not delete**
sessions removed from Health Connect or clear old steps when the field is omitted.
Local reconciliation therefore cannot remove stale server records; doing that would
require a separate backend change. No user health data is deleted by this milestone.

## Verification and source map

Run the Gradle checks above; see [TESTING.md](TESTING.md) for the device checklist.
Milestone 3.2 adds HTTP and synchronization-policy tests to the existing health-data
tests. See TESTING.md for current results and the remaining physical OnePlus checks.
Build/unit checks cannot establish real OHealth behavior or widget rendering.

- `data/`: Health Connect reads, cache, Keystore settings, authenticated uploads.
- `sync/`: periodic local refresh and network-constrained upload workers.
- `ui/`, `MainActivity.kt`: Compose home/date selection/settings and permission flow.
- `widget/`: responsive Glance widget; `res/xml/`: widget metadata and backup policy.
- `PermissionsRationaleActivity.kt`: permission rationale/privacy screen for both
  standalone and Android-integrated Health Connect.

### Milestone 3.2 file inventory

Created:
- `app/src/main/java/com/stridehub/android/data/HealthUploadClient.kt`
- `app/src/test/java/com/stridehub/android/data/HealthUploadClientTest.kt`
- `app/src/test/java/com/stridehub/android/data/SyncRepositoryTest.kt`
- `sync.local.properties.example`

Modified:
- `.gitignore`, `app/build.gradle.kts`, `README.md`, `TESTING.md`
- `app/src/debug/AndroidManifest.xml` (removed duplicate application element from the working copy)
- `app/src/debug/res/xml/debug_network_security.xml`
- `app/src/main/java/com/stridehub/android/StrideHubApplication.kt`
- `data/CacheJson.kt`, `data/HealthConnectRepository.kt`, `data/LocalRepository.kt`,
  `data/Models.kt`, `data/SecureSettings.kt`, `data/SyncRepository.kt`
- `sync/HealthSyncWorker.kt`, `ui/HomeViewModel.kt`, `ui/SettingsScreen.kt`
- `app/src/test/java/com/stridehub/android/data/HealthDataTest.kt`

The abbreviated data/sync/ui paths are under `app/src/main/java/com/stridehub/android/`.
No dependencies were added. Existing frontend/backend working-copy changes were
left intact; this milestone edits only Android files.
