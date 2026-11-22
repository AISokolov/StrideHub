# Verification checklist

## Widget polish and selected-date Garmin verification (2026-11-19)

- Android: **46 tests passed**, including the opt-in live Garmin client test; zero
  failures, errors, or skips in that run. The other 45 tests use synthetic inputs.
- Backend: **13 tests passed**; React: **8 tests passed**, lint and production build passed.
- Debug APK and debug lint passed; lint has 0 errors and 18 advisory warnings.
- Live GETs of both `/activities?date=2026-11-18` and `/daily?date=2026-11-18`
  returned the same activity ID and workout (Garmin's actual name is `Koper Бег`).
  Both November 19 requests returned HTTP 200 with an empty list.
- `GarminLiveTest` uses the production client/repository and cache JSON, writes to
  a temporary disk cache, reloads it, and verifies the title/distance/duration/pace.
  Set `STRIDEHUB_LIVE_URL` and `STRIDEHUB_LIVE_TOKEN` privately to opt in; otherwise
  this one test is skipped. Never print or commit the token.
- Updated APK installed over the existing app on the connected phone, preserving
  settings/cache. Health Connect still showed 3,822 steps and 7h 10m. Sanitized
  device logs confirmed the November 18 Garmin download (HTTP 200, count 1).
- After permission to control the phone, installed the final APK and selected
  November 18 using the existing date picker. The app visibly showed Running —
  Koper Бег, 8.30 km (localized decimal comma), 39:12 and 4:43/km.
- The existing home-screen widget visibly rendered both emoji, 3,822 steps, 7h 10m,
  and Updated time without any Garmin placeholder, clipping or overlap at its current size.
- Offline restart, other launcher sizes and multiple-workout visual layouts remain
  physical checks; failure retention and multiple activity data are covered by mocks.

Current acceptance expectations supersede the older baseline below: the date picker
controls Garmin; a widget never substitutes yesterday; an empty Garmin day renders
no Garmin UI. The smallest widget can summarize available workouts with a tap hint.
The launcher controls actual dimensions, and background redraws are not exact at midnight.
See [POLISH_REPORT.md](POLISH_REPORT.md) for changed files and remaining limitations.

## Dashboard + Garmin completion (2026-11-19)

- Backend: 11 tests passed using an isolated SQLite database and synthetic Garmin data.
- React: 8 tests passed; ESLint and Vite production build passed.
- Android: 40 unit tests passed (13 new Garmin tests plus 27 existing health/upload tests).
- Debug APK, unsigned release APK, and debug lint completed successfully with JDK 21.
- No Android device was connected. Actual widget layout and live Garmin/OHealth
  behavior remain physical acceptance checks; mocked tests do not establish them.

Additional physical checks:

| Scenario | Expected result |
| --- | --- |
| Sync now | Independent Health upload and Garmin download status on Home and Settings. |
| Health permission denied | Garmin can still download; health cache stays available. |
| Multiple workouts | Correct IDs/metrics after restart; no running pace for cycling. Resize wide/short and tall widgets; check `+N more` and tap to open. |
| Empty vs unavailable | Successful empty response says no workouts today. First load/offline/server 502 says unavailable/not loaded; old workouts stay cached. |
| Airplane mode / backend offline | Previously saved workouts and their refresh time remain. Health local refresh still works. |
| Midnight / timezone | Previous-day workouts carry their date; they must not be presented as today's. Match phone timezone with GARMIN_TIMEZONE. |
| Failed download | Health upload can succeed; Garmin failure does not erase health or workouts. Widget refresh follows the attempt. |
| Changed backend | Saving a different URL/token clears that backend's Garmin cache and authentication block. |
| Release | HTTP URL rejected; valid HTTPS works; no debug credentials embedded. |

Use the root README for browser login setup and the shared backend contract. Historical
Milestone 3.2 results below describe the earlier baseline.

## Milestone 3.2 verification (2026-11-16)

- Debug APK and unsigned release APK: built successfully.
- Unit tests: **27 passed**, 0 failures/errors, including an actual loopback HTTP request.
- Debug lint: **0 errors, 18 warnings** (17 existing advisory warnings plus the
  intentional debug-only HTTP policy warning).
- Release output verified: empty development credential fields, cleartext disabled,
  and no debug network-security policy.
- Local token properties and generated BuildConfig verified as Git-ignored.

No physical phone is connected; real OnePlus uploads and offline widget rendering remain device
checks. Tests use synthetic data and an isolated loopback HTTP server, never the
user's Express database. No user health records are deleted or overwritten by tests.

## Automated

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Unit report: `app/build/reports/tests/testDebugUnitTest/index.html`.
Lint report: `app/build/reports/lint-results-debug.html`.
The release output is unsigned until you configure your own signing key.

## Physical OnePlus / Health Connect acceptance checks

These require a phone and real OHealth data. Do not treat a successful build or
emulator smoke test as proof of watch integration.

| Scenario | Action and expected result |
| --- | --- |
| Permission grant/deny | Fresh install, deny then allow both read permissions. Denial keeps placeholders/cache and Grant Permissions; allowing reads enables Refresh Data. |
| Partial/revoked permission | Grant steps only, then both; revoke sleep while app is backgrounded. Reopen: no crash or fabricated result, permission status updates, cached values remain labeled. |
| Rationale | Open the privacy link from the Health Connect permission UI on Android 13 and 14+. The StrideHub privacy screen opens. |
| Missing provider | Test Android 9–13 without Health Connect, then install/update it. Status and install action work; app reconnects on return. |
| Steps | Sync OHealth, compare today's aggregate with Health Connect. Add overlapping source data using Health Connect Toolbox; aggregation avoids manually double-counting it. |
| Sleep | Compare overnight timestamps, offset, wake-up date, and duration. Stage-aware sleep excludes awake stages; a session without stages says session duration. |
| Pagination | With a test data writer/Toolbox, create more than 500 accessible sleep records and a known session on the final page. Refresh: the last-page session appears and the full day uploads. StrideHub itself has no write permission. |
| Midnight / DST / travel | Refresh around local midnight; check yesterday remains separate. Try Ljubljana's 2026-03-29 and 2026-10-25 with suitable test records, then change timezone and refresh. No stale steps from the previous zone. |
| Empty data | Pick an empty date. Steps show a dash; sleep shows a clear no-data message. Upload omits steps and includes an empty session array. |
| Offline / restart | Refresh, enable airplane mode, close/reopen app and view widget. Data remains; failed server upload remains pending. Restart phone and repeat. |
| Unique work | Reopen repeatedly; Android Studio Background Task Inspector shows one `stridehub-health-refresh` periodic job. |
| Background allowed | Grant the supported background-read permission, sync new OHealth data, leave app. Observe eventual local cache/widget update and network upload. Do not expect exactly 30 minutes. |
| Background unavailable/denied | Remove background access but keep steps/sleep. Background worker does not read Health Connect; cached uploads may continue. Open app to refresh successfully. |
| Widget | Add/resize to 2×2 and 4×2 on OxygenOS launcher. Verify text, tap-to-open/refresh, empty state, midnight transition, and refresh after a cache change. |
| Duplicate/correction | Refresh same data twice and sync twice. Day/session counts do not grow. Edit/delete a source session on a refreshed date; the local cache updates. The current Express API upserts IDs but does not remove deleted sessions; verify that limitation explicitly. |
| Backend missing/offline | Disconnect the development server or disable network. A 404 requires checking the URL/settings. Pending data remains and last-success time does not advance. |
| Backend auth/retry | Return 401/403: no further requests until URL/token changes, even after restart or manual sync. Return 429/503: automatic retry. A 200 must contain matching success/date/stepsSaved/sleepSessionsSaved; empty 204 must not acknowledge data. |
| Concurrent update | Delay server response; refresh with new steps before replying 200. The newer revision must remain pending. |
| TLS/security | Debug permits the LAN HTTP URL; release rejects HTTP. Reject invalid/expired/hostname-mismatched certificates and redirects. Release must reject a user CA that debug accepts. Verify no tokens/payloads in app logs. |
| Accessibility | Large font/display scaling, TalkBack labels, portrait/landscape and keyboard on Settings: controls remain reachable by scrolling. |

## Synchronization-specific checks

- Configure the ignored development properties as described in README. BuildConfig
  uses the configured URL/token only in debug; release fields must be empty. Do not
  print generated files or credentials. `git check-ignore sync.local.properties`
  and `git check-ignore app/build/generated/source/buildConfig/debug/com/stridehub/android/BuildConfig.java`
  must both succeed.
- Settings → Sync now must first refresh today plus seven previous dates, then update
  cache/widget and upload. Last-success time changes only after confirmed acceptance.
- Return 400 for one date and success for another: the second still uploads; the first
  remains pending and is not repeatedly resent until its measurements/settings change.
- Restart the app after a successful upload; unchanged revisions must stay acknowledged.
  Correct the step count and upload again: authenticated GET `/api/health/daily?date=...`
  must show the replacement count. Repeat sleep uploads: stable IDs must prevent growth.
- Missing steps must be absent in JSON. Two sleep sessions on one wake-up date must both
  upload. An overnight session must remain whole, with seconds and timezone offsets.
- With basic Health Connect permission revoked, no upload occurs and cache/widget data
  remains available. Background-read permission alone can be denied while cached uploads
  still work; opening the app refreshes the rolling window.
- The current Express API does not propagate deletions or clear missing step values.
  No deletion endpoint is called. Do not clear app storage or uninstall to test upgrades.
