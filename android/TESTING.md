# Verification checklist

## Results from this implementation (2026-11-15)

- `:app:assembleDebug`: passed; installable debug APK generated.
- `:app:testDebugUnitTest`: 12 tests passed, 0 failed/skipped.
- `:app:lintDebug`: passed, 0 errors and 17 advisory warnings (newer dependency/SDK
  versions, backward-compatible widget attributes, widget preview overdraw, and KTX suggestions).
- `:app:assembleRelease`: passed; unsigned release APK generated.
- Runtime smoke test: attempted with the existing API 37 emulator. One install
  reported success, but the emulator subsequently crashed its Android system process
  (`Lost network stack`) and lost access to emulated storage. Launch/UI/widget
  verification could not be completed reliably. No physical phone was connected.
- Real OHealth readings, OnePlus background execution, widget rendering, and the
  future Express endpoint remain device/integration checks below.

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
| Empty data | Pick an empty date. Steps show a dash; sleep shows a clear no-data message. Upload has steps null and an empty session array. |
| Offline / restart | Refresh, enable airplane mode, close/reopen app and view widget. Data remains; failed server upload remains pending. Restart phone and repeat. |
| Unique work | Reopen repeatedly; Android Studio Background Task Inspector shows one `stridehub-health-refresh` periodic job. |
| Background allowed | Grant the supported background-read permission, sync new OHealth data, leave app. Observe eventual local cache/widget update and network upload. Do not expect exactly 30 minutes. |
| Background unavailable/denied | Remove background access but keep steps/sleep. Background worker does not read Health Connect; cached uploads may continue. Open app to refresh successfully. |
| Widget | Add/resize to 2×2 and 4×2 on OxygenOS launcher. Verify text, tap-to-open/refresh, empty state, midnight transition, and refresh after a cache change. |
| Duplicate/correction | Refresh same data twice and sync twice. Day/session counts do not grow. Edit/delete a source session on a refreshed date; next payload replaces that day's set. |
| Backend missing/offline | Use a test HTTPS server with 404, disconnect it, or disable network. Pending data remains and last-success time does not advance. |
| Backend auth/retry | Return 401/403, then 429/503, then durable 200/204. Verify useful status, automatic retries for temporary errors, stable idempotency key, and acknowledgement only on success. |
| Concurrent update | Delay server response; refresh with new steps before replying 200. The newer revision must remain pending. |
| TLS/security | Reject HTTP URL, invalid/expired/hostname-mismatched certificate, and redirects. Release must reject a user CA that debug accepts. Verify no tokens/payloads in app logs. |
| Accessibility | Large font/display scaling, TalkBack labels, portrait/landscape and keyboard on Settings: controls remain reachable by scrolling. |

The future Express endpoint must implement the idempotent replacement contract in
README before end-to-end server acceptance can be verified.
