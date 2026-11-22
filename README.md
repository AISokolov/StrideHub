# StrideHub

Personal dashboard and Android companion for Garmin workouts and Health Connect
steps/sleep. The existing SQLite upload contract and Garmin activity cards are
preserved. Garmin credentials stay on Express; Android uses only the device Bearer
token. The browser uses a separate password and HTTP-only session cookie.

## Local setup

Use Node.js 22.12+ and JDK 21 for Android. Run commands in the named directory.

1. In `server/`, run `npm ci`. Merge the settings in `.env.example` into your
   existing ignored `.env`; **do not overwrite existing Garmin/token settings**.
2. Run `npm run hash-password`. Enter a new dashboard password (12+ characters);
   input is hidden in an interactive terminal. Save the generated
   `DASHBOARD_PASSWORD_HASH='scrypt$…'` line in `.env`. The password itself is not
   stored. Do not use the Android token as this password.
3. Generate an independent session secret with
   `node -e "console.log(require('crypto').randomBytes(48).toString('hex'))"`.
   Save it as `SESSION_SECRET` in `.env`.
4. Start Express with `npm run dev` (port 3001 by default).
5. In a second terminal in `client/`, run `npm ci` and `npm run dev`. Open
   `http://localhost:5173` and sign in with the dashboard password.

Vite forwards `/api` to `http://127.0.0.1:3001`. There are no frontend secrets or
Vite credential variables. To use another backend, set the **Vite process's**
`BACKEND_URL` environment variable before starting Vite. Browser requests remain
same-origin. For LAN browser testing, bind Vite with `npm run dev -- --host` and
set `DASHBOARD_ORIGIN` to the exact browser origin, e.g.
`http://192.168.0.79:5173`, then restart Express. Avoid mixing localhost and IP
origins. Plain HTTP development sessions are only suitable for a trusted LAN.

## Environment variables

| Variable | Where / purpose |
| --- | --- |
| `GARMIN_API_KEY` | Server only; existing Garmin Personal API key. |
| `HEALTH_SYNC_TOKEN` | Server only; existing token provisioned to Android Settings. |
| `DASHBOARD_PASSWORD_HASH` | Server; generated scrypt hash, never a plaintext password. |
| `SESSION_SECRET` | Server; independent random secret of at least 32 characters (48 random bytes recommended). |
| `DASHBOARD_ORIGIN` | Server; exact allowed browser origin without trailing slash, default `http://localhost:5173`. |
| `GARMIN_TIMEZONE` | Server; IANA timezone, default `Europe/Ljubljana`. Keep consistent with the phone for daily views. |
| `DB_PATH` | Server; optional SQLite path; default `server/data/stridehub.db`. Includes health, sessions, and login limits. |
| `PORT` | Server; default `3001`. |
| `HOST` | Server; default `0.0.0.0` for LAN development; use `127.0.0.1` behind local Nginx. |
| `NODE_ENV` | Server; set `production` for HTTPS-only session cookies and configuration validation. |
| `TRUST_PROXY` | Server; explicit trusted proxy IPs/subnets separated by commas. Use `loopback` only for local Nginx; unset by default. |
| `BACKEND_URL` | Vite process; optional proxy target, default `http://127.0.0.1:3001`. |
| `STRIDEHUB_SYNC_URL` / `STRIDEHUB_SYNC_TOKEN` | Optional Android debug build inputs from environment or ignored `android/sync.local.properties`; Settings is preferred. Release defaults are empty. |

## Authentication and endpoints

- `GET /api/auth/session` returns authentication status and a CSRF token.
- `POST /api/auth/login` accepts `{ "password": "…" }`, the exact allowed
  `Origin`, and `X-CSRF-Token` from the session endpoint. It regenerates the session.
- `POST /api/auth/logout` requires the same origin/CSRF protection and deletes the session.
- Health and **all Garmin routes**, including status/profile/recent activities,
  require either a valid session or `Authorization: Bearer <HEALTH_SYNC_TOKEN>`.
- `GET /api/health/daily?date=YYYY-MM-DD` retains its existing response and validation.
- `POST /api/health/sync` retains Android's existing payload and acknowledgement.
  Cookie-authenticated writes also require CSRF protection; Bearer writes do not.
- `GET /api/garmin/activities?date=YYYY-MM-DD` keeps the raw card-compatible format.
- `GET /api/garmin/daily?date=YYYY-MM-DD` returns `{ date, timeZone, activities }`.
  Omitting `date` selects today in `GARMIN_TIMEZONE`; Android uses this for today's refresh.
  Each activity has `id`, `name`, `type`, `distanceMeters`, `durationSeconds`,
  `averagePaceSecondsPerKm`, `averageHeartRate`, `calories`, `elevationGainMeters`.
  Missing metrics are null. Running pace is omitted/null for other activity types.

Sessions last 12 hours, persist in SQLite across server restarts, use HTTP-only
SameSite=Strict cookies, and are invalidated by changing the password hash or session
secret. Production cookies are Secure with a `__Host-` name. Private responses are
`Cache-Control: no-store`. Login allows 5 attempts per IP and 30 globally per fixed
15-minute window, persisted across restarts; successful attempts also count. At most
two password derivations run concurrently. After a limit, wait 15 minutes.
The implementation follows the [Express session guidance](https://expressjs.com/en/resources/middleware/session/)
and uses [Node's scrypt](https://nodejs.org/api/crypto.html#cryptoscryptpassword-salt-keylen-options-callback).

## Test the React dashboard

1. Sync health data from Android, sign in, and choose that date with the existing
   picker. Confirm daily steps, individual sleep intervals, and unchanged Garmin cards.
2. Pick an empty date: cards say **No data**, while a genuine zero step count stays zero.
   Multiple sleep sessions have their own start/wake times and a summed duration.
3. Change dates rapidly. Only the final date may populate the cards. Disconnect the
   backend and retry using **Refresh selected day**; errors must not look like empty data.
4. Sign out and verify private data disappears. Reload: the login screen remains.
   An expired session returns to login; a Garmin outage does not hide available health data.
5. Check narrow screens: the health cards stack vertically.

## Test Garmin on Android

Build/install the debug APK using [android/README.md](android/README.md). In Settings,
use the current backend origin (development: `http://192.168.0.79:3001`) and the existing
`HEALTH_SYNC_TOKEN`. No Garmin key or dashboard password belongs in Android.

1. Select a date above the cards, then tap **Refresh Data** or **Sync now**. Garmin
   follows that date and shows the actual workouts and backend calendar timezone.
   Refresh Data downloads Garmin even without Health Connect permission. Settings
   and background sync fetch today's Garmin workouts.
2. Add/resize the StrideHub widget. Small squares show health values plus a Garmin
   summary only when today's workouts exist; wide short widgets show one workout; taller layouts show up to 1/3/5
   workouts and `+N more`. Distance, duration, and running-only pace use cached values.
   Tap to open the app and see the full list. Empty or never-fetched days have no
   Garmin section. Steps and sleep use 👣 and 🌙 icons. Larger font settings reduce
   the number of visible workouts to preserve space.
3. After a successful download, disable network or stop Express, then tap Sync now.
   Workouts remain cached with their saved time. Health reads/cache remain independent.
   Repeat after restarting the app. A fresh install without a download says not loaded
   or unavailable; only a successful empty response can mean no workouts today.
4. Verify multiple activities, no-workout days, a backend 502, and invalid token behavior.
   Authentication failures wait for changed settings; temporary failures use existing
   WorkManager exponential backoff.
5. At midnight, older cached Garmin data is excluded from today's widget.
   Android schedules updates opportunistically; exact 30-minute or midnight execution
   is not guaranteed. Widget content changes when the launcher/worker refreshes it.

The existing periodic local-health worker still queues one unique network-constrained
worker. That worker uploads health, downloads Garmin, and refreshes Glance; no new
periodic job was added. Android's private DataStore stores Garmin alongside health.
Release builds reject HTTP and have no embedded development token. A debug APK may
contain a locally configured debug token: do not distribute it publicly.

## Automated checks

```powershell
# server/
npm test
# client/
npm test
npm run lint
npm run build
# android/ (JDK 21)
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Backend tests use an isolated temporary SQLite database and mocked Garmin responses.
React tests cover cards, multiple sleep sessions, dates/stale responses, login/logout,
expiration, independent failures, loading, retry, and StrictMode request deduplication.
Android tests cover HTTP/parsing, cache round trips, offline/empty/multiple workouts,
independent sync outcomes/widget refresh callbacks, and the existing health behavior.
See [android/TESTING.md](android/TESTING.md) for physical device checks.

## Before VPS deployment

1. Provision a domain/TLS certificate and serve `client/dist` and `/api` on the **same
   HTTPS origin**. Build with `npm run build`; Vite's development proxy is not a server
   deployment. Do not expose Vite or the Express port publicly.
2. Set `NODE_ENV=production`, `DASHBOARD_ORIGIN=https://your-domain`, `HOST=127.0.0.1`,
   and `TRUST_PROXY=loopback` for same-host Nginx. Configure your real secrets and a
   durable `DB_PATH` outside ephemeral release directories. Restrict file permissions
   and back up SQLite safely, including WAL handling. Keep the server clock correct.
3. Nginx should proxy `/api/` to `http://127.0.0.1:3001`, preserve `Host`, set
   `X-Forwarded-Proto $scheme`, and **overwrite** `X-Forwarded-For $remote_addr` at the
   trusted edge. Do not cache API responses. Serve SPA routes with fallback to index.html.
   Restrict network access so clients cannot bypass the trusted proxy. Use TLS redirect,
   HSTS after validating HTTPS, and a restrictive CSP for the static app.
4. Run Express under a supervised service with its working directory at `server/`.
   Verify Secure cookies, login/logout, unauthorized responses, and a real Android sync
   through HTTPS. Update Android Settings to the HTTPS origin and sign the release APK.

## Known limitations

- One personal dashboard account; no password recovery, multi-user isolation, or MFA.
- Health upload retains existing upsert semantics: deleted source sessions and omitted
  step values do not remove existing backend records. This milestone does not change that contract.
- When Garmin supplies GMT timestamps, filtering uses `GARMIN_TIMEZONE`, including DST.
  Local-only upstream records fall back to Garmin's supplied local calendar date.
  The upstream search spans adjacent dates, paginates to exhaustion, and fails safely
  after 100 pages or non-advancing pagination instead of returning an incomplete success.
- Garmin today uses the backend's configured calendar timezone, retained in the cache
  for offline display. Health Connect continues to use the phone's local timezone.
- Unit tests cannot establish launcher font scaling, OEM background scheduling, or live
  Garmin/OHealth availability. See `android/POLISH_REPORT.md` for the latest live and
  connected-device checks and remaining acceptance checks. Release APK is unsigned by default.

## Files changed for this completion

Existing uncommitted health synchronization work was retained. This completion adds:

- `server/src/app.js`, `server/src/auth/{browserAuth,password,sqliteSessionStore}.js`,
  `server/src/services/garminDaily.service.js`, `server/scripts/hash-password.js`,
  `server/test/api.test.js`, `server/.env.example`.
- `client/src/components/{StepsCard,SleepCard}.jsx`, `client/src/test/{setup.js,dashboard.test.jsx}`.
- Android `data/{BackendHttp,GarminClient,GarminData,GarminRepository,SyncCoordinator}.kt`
  and `app/src/test/java/com/stridehub/android/data/GarminTest.kt`.

It updates server entry/routes/Garmin timeouts and package manifests; client App,
ActivitiesPage, API client, CSS, Vite config and package manifests; Android AppGraph,
Models/CacheJson, SyncRepository/HealthUploadClient, workers, Home/Settings UI,
HomeViewModel, widget/preview/strings; and the root/client/Android documentation.
Android source paths above are under `android/app/src/main/java/com/stridehub/android/`.
