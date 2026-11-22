# StrideHub dashboard

React + Vite dashboard with session login, the existing date picker/Garmin cards,
and Health Connect steps/sleep cards.

See [the root README](../README.md) for server configuration, the password-hash
command, Android integration, security, testing, and deployment.

```powershell
npm ci
npm run dev
npm test
npm run lint
npm run build
```

Open http://localhost:5173. Vite proxies /api to http://127.0.0.1:3001 by default;
set the Vite process BACKEND_URL to change it. No credentials belong in VITE_*
variables or frontend source. Production requires same-origin HTTPS hosting of
client/dist and /api via a reverse proxy. The browser's origin must exactly match
the server DASHBOARD_ORIGIN for login/logout CSRF verification.
