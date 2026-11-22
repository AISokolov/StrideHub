# Deploy StrideHub to stridehub.com

This guide targets an **Ubuntu or Debian VPS with systemd** and root/sudo access.
Run Linux commands on the VPS unless a step explicitly says **Windows**. The
repository must include the deployment files from this change before cloning it.

The server IP supplied for this deployment is `78.17.220.166`. Confirm that it is
still your VPS address. You must own `stridehub.com` and control its DNS.

## 1. Understand the layout

```text
Browser / Android → https://stridehub.com:443 → host Nginx
                                               ├─ /api/ → 127.0.0.1:3002 → Express container
                                               └─ /     → 127.0.0.1:8088 → React/Nginx container

Database: /var/lib/stridehub/data/stridehub.db
Backups:  /var/backups/stridehub/
Code:     /opt/stridehub/
Secrets:  /opt/stridehub/deploy/.env.production
```

Ports 3000 and 3001 remain available to your existing applications. No MariaDB is
needed. Docker builds React and installs Node dependencies, so host Node.js is not
required. Host Nginx handles certificates and routes both the dashboard and API
through the same domain.

The Compose file deliberately uses **Linux host networking**. Each container
listens only on `127.0.0.1`; it does not publish a public port. This lets Express
trust the local Nginx proxy using `TRUST_PROXY=loopback`. Treat other host processes
as trusted. This configuration is intended for the Linux VPS, not Windows Docker
Desktop without host-network support.

## 2. Commit and push the deployment files — Windows

From PowerShell in `D:\Projects\StrideHub`:

```powershell
git status --short
git add Dockerfile .dockerignore compose.yaml deploy DEPLOYMENT.md README.md .gitignore server/scripts/backup-db.js server/test/backup.test.js android/app/build.gradle.kts android/sync.local.properties.example android/README.md
git diff --cached --stat
git commit -m "feat: add Docker deployment and verified SQLite backups"
git push
```

Review any previously staged changes before committing. Real `.env` files and
Android local properties remain ignored; the production template contains no secrets.

## 3. Check the VPS

Connect from Windows:

```powershell
ssh root@78.17.220.166
```

On the VPS:

```bash
cat /etc/os-release
uname -m
ss -tlnp
df -h /
free -h
```

Confirm ports **80, 443, 3002, and 8088** are free. If this is not Ubuntu or Debian,
use the distribution's installation instructions instead of the apt commands below.
Do not stop the existing Node services on 3000/3001 to make room for StrideHub.

## 4. Configure DNS

In your domain provider's DNS panel, create or update:

| Type | Name | Value |
| --- | --- | --- |
| A | `@` | `78.17.220.166` |

This guide uses `stridehub.com` only, not `www.stridehub.com`. Remove an incorrect
AAAA record if the domain points to an IPv6 address that does not serve this VPS.
If using Cloudflare, use DNS-only mode for initial setup and certificate issuance.

Check from Windows:

```powershell
Resolve-DnsName stridehub.com -Type A
```

The result should include the VPS IP before requesting an HTTPS certificate.

## 5. Install Docker, Nginx, and Certbot

First check whether Docker already exists:

```bash
docker --version
docker compose version
```

If already installed, retain that installation. This project requires Compose
**2.30+** (or a newer major version) for raw environment-file handling.

For a new Docker installation on Ubuntu/Debian, run:

```bash
apt update
apt install -y ca-certificates curl git nginx certbot python3-certbot-nginx sqlite3
install -m 0755 -d /etc/apt/keyrings
. /etc/os-release
curl -fsSL "https://download.docker.com/linux/$ID/gpg" -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/$ID
Suites: ${UBUNTU_CODENAME:-$VERSION_CODENAME}
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
apt update
apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker nginx
docker compose version
```

If Docker already exists, install only the other required tools with the first
`apt install` command and enable Docker/Nginx. If apt reports conflicting Docker
packages, follow the official distribution-specific Docker installation guide;
do not remove packages supporting other running containers without reviewing them.

## 6. Open the public web ports

In the VPS provider firewall, allow inbound TCP **22, 80, and 443**. Preserve any
rules required by your existing applications. Do not open 3002 or 8088.

If UFW is already active:

```bash
ufw status verbose
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
```

If you want to enable UFW for the first time, install it, add these rules, and add
rules needed by the existing services before running `ufw enable`. Keep the current
SSH session open and verify a second SSH connection before disconnecting.
Do not reset an existing firewall. This deployment uses loopback listeners rather
than relying on UFW to hide Docker-published ports.

## 7. Clone and build

```bash
git clone https://github.com/AISokolov/StrideHub.git /opt/stridehub
cd /opt/stridehub
install -d -m 700 -o 1000 -g 1000 /var/lib/stridehub/data /var/backups/stridehub
cp deploy/.env.production.example deploy/.env.production
chmod 600 deploy/.env.production
docker compose build --pull
```

If the repository is private, authenticate with a read-only deploy key or GitHub
credential prompt. Do not put an access token in the clone URL. If `/opt/stridehub`
already exists, inspect it and update the existing checkout rather than overwriting it.

The API container runs as UID/GID 1000, which is why the data and backup directories
use those numeric owners. Secrets remain root-readable on the host; Compose passes
them to the API process without baking them into either image.

## 8. Set production secrets

Generate a dashboard password hash interactively (at least 12 characters):

```bash
docker compose run --rm --no-deps api node scripts/hash-password.js
```

Generate a separate session secret:

```bash
docker compose run --rm --no-deps api node -e "console.log(require('crypto').randomBytes(48).toString('hex'))"
```

Edit the production file:

```bash
nano deploy/.env.production
```

Fill every secret field:

```dotenv
GARMIN_API_KEY=your-existing-garmin-key
HEALTH_SYNC_TOKEN=your-existing-android-token
DASHBOARD_PASSWORD_HASH=scrypt$your-salt$your-hash
SESSION_SECRET=your-generated-random-secret
DASHBOARD_ORIGIN=https://stridehub.com
GARMIN_TIMEZONE=Europe/Ljubljana
```

The values above are placeholders. Preserve the existing Garmin key and Android
token from your local `server/.env`. You may also reuse your existing valid dashboard
hash. **Remove the surrounding single quotes from the hash generator's output:**
this production file uses Compose `format: raw`, so dollar signs are literal and
quotes would become part of the value. Local `server/.env` still uses dotenv syntax.
Never paste these values into GitHub, chat, or the frontend.

If you need a new Android token, use the random-secret command again and copy that
different result into both `HEALTH_SYNC_TOKEN` and Android Settings.

Validate without printing secrets:

```bash
docker compose config --quiet
```

Avoid posting ordinary `docker compose config` output: it includes resolved secrets.

## 9. Optional: migrate existing health history

Skip this step for an empty database. Perform it **before the first VPS startup**.

On Windows, create a consistent backup of the local database:

```powershell
cd D:\Projects\StrideHub\server
node --env-file=.env scripts/backup-db.js "$env:TEMP\stridehub-migration"
```

The script prints the backup's exact filename. Copy that file over SSH, replacing
`<printed-backup-path>` with the actual path:

```powershell
scp "<printed-backup-path>" root@78.17.220.166:/var/lib/stridehub/data/stridehub.db
```

On the VPS:

```bash
chown 1000:1000 /var/lib/stridehub/data/stridehub.db
chmod 600 /var/lib/stridehub/data/stridehub.db
sqlite3 /var/lib/stridehub/data/stridehub.db 'PRAGMA integrity_check;'
```

Expect `ok`. The backup API includes committed WAL data. Do not copy only a live
SQLite `.db` file or overwrite an already running VPS database.

## 10. Start the containers

```bash
cd /opt/stridehub
docker compose up -d --wait
docker compose ps
curl -f http://127.0.0.1:3002/api/health
curl -I http://127.0.0.1:8088/
ss -tlnp
```

Expect `{"status":"ok"}`, a successful frontend response, and loopback listeners
on 3002/8088. If startup fails, inspect `docker compose logs --tail=100 api web`.
Browser login needs HTTPS, which is configured next.

## 11. Enable Nginx routing and HTTPS

Install the host configuration once:

```bash
cd /opt/stridehub
cp deploy/nginx-host.conf /etc/nginx/sites-available/stridehub
ln -s /etc/nginx/sites-available/stridehub /etc/nginx/sites-enabled/stridehub
nginx -t
systemctl reload nginx
curl -I http://stridehub.com
```

If the files or symlink already exist, inspect them instead of overwriting an
existing TLS configuration. Leave unrelated Nginx sites alone.

Once DNS is correct and HTTP responds, request the certificate:

```bash
certbot --nginx -d stridehub.com --redirect
systemctl enable --now certbot.timer
certbot renew --dry-run
systemctl list-timers certbot.timer
```

Enter your email and accept the certificate authority's terms when prompted.
Certbot adds HTTPS and redirects HTTP to HTTPS. It also reloads Nginx after managed
renewals. Do not subsequently copy the original HTTP-only config over its changes.

## 12. Test the public site

```bash
curl -I https://stridehub.com
curl -f https://stridehub.com/api/health
curl -f https://stridehub.com/api/auth/session
curl -i 'https://stridehub.com/api/health/daily?date=2026-11-22'
```

Expected results:

- The website returns HTML over HTTPS without a certificate warning.
- `/api/health` returns `{"status":"ok"}`.
- `/api/auth/session` returns `authenticated: false` and a CSRF token.
- The private daily endpoint returns **401** without authentication.

Open <https://stridehub.com>, log in, select a date, refresh, and log out. Browser
developer tools should show a Secure, HttpOnly `__Host-stridehub.sid` cookie.
An empty health history is expected until you migrate data or sync Android.

## 13. Update Android

The source now defaults to `https://stridehub.com` for new installs. Existing saved
settings take precedence, as do debug overrides in `android/sync.local.properties`
or environment variables. An app update does not overwrite saved credentials.

On the phone, open **Settings** and save:

```text
Backend URL: https://stridehub.com
Token:       the exact HEALTH_SYNC_TOKEN from the VPS
```

Do not add `/api` or `:3002` to the URL. Grant Health Connect permissions, run
**Sync now**, and confirm that steps/sleep appear on the website and Garmin workouts
appear in the app. Check the widget and then test cached data offline.

To build an updated debug APK on Windows with JDK 21:

```powershell
cd D:\Projects\StrideHub\android
.\gradlew.bat :app:assembleDebug
```

The output is `app/build/outputs/apk/debug/app-debug.apk`. Keep the same signing key
for app updates. Follow the Android README for release signing; never embed the
device token in a publicly distributed APK.

## 14. Enable daily backups and automatic startup

Docker and Nginx are enabled at boot. Both containers use `restart: unless-stopped`:
they restart after a reboot unless you explicitly stopped them. A container marked
unhealthy is visible in `docker compose ps`; health checks alone do not restart it.

Run the first backup and install the daily timer:

```bash
cd /opt/stridehub
docker compose exec -T api node scripts/backup-db.js
cp deploy/stridehub-backup.service deploy/stridehub-backup.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now stridehub-backup.timer
systemctl start stridehub-backup.service
systemctl list-timers stridehub-backup.timer
journalctl -u stridehub-backup.service -n 20 --no-pager
ls -lh /var/backups/stridehub/
```

Backups run daily around **03:15 Europe/Ljubljana** and catch up after downtime.
Each uses SQLite's online backup API and passes an integrity check before receiving
its final `.db` filename. A failed operation leaves a `.partial` file, not a valid
backup. The job requires a running API container; inspect its logs if it fails.

Backups are kept without automatic deletion. Monitor disk space and choose a
retention policy once you have tested recovery. Download backups regularly to a
different machine or configure encrypted off-server storage: copies on the same
VPS do not protect against VPS loss. For example, from Windows:

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\StrideHubBackups"
scp "root@78.17.220.166:/var/backups/stridehub/*.db" "$env:USERPROFILE\StrideHubBackups\"
```

Keep a private, separate copy of production secrets as well. SQLite backups do not
contain `.env.production` or TLS certificates, but do contain personal health data
and session records. Do not commit them.

## 15. Restore a backup if needed

This replaces the active database with the selected snapshot. Choose and inspect
the exact backup path first, and stop writes before restoring:

```bash
cd /opt/stridehub
BACKUP=/var/backups/stridehub/REPLACE_WITH_EXACT_BACKUP_FILENAME.db
test -f "$BACKUP"
sqlite3 "$BACKUP" 'PRAGMA integrity_check;'
```

Continue only if the file exists and the result is `ok`:

```bash
systemctl stop stridehub-backup.timer
systemctl stop stridehub-backup.service
docker compose stop api
SAVED_DIR="/var/lib/stridehub/data-before-restore-$(date +%Y%m%d-%H%M%S)"
mv /var/lib/stridehub/data "$SAVED_DIR"
install -d -m 700 -o 1000 -g 1000 /var/lib/stridehub/data
install -m 600 -o 1000 -g 1000 "$BACKUP" /var/lib/stridehub/data/stridehub.db
docker compose up -d --force-recreate --wait api
systemctl start stridehub-backup.timer
```

The old database and WAL files remain together in `data-before-restore-*`. Recreating
the container ensures its bind mount uses the new directory. Verify login, dates,
and Android sync. Keep the old directory until you are satisfied with recovery.

## 16. Deploy future updates

After pushing a tested change to GitHub:

```bash
cd /opt/stridehub
docker compose exec -T api node scripts/backup-db.js
git pull --ff-only
docker compose build --pull
docker compose up -d --wait
docker compose ps
```

Run the backup successfully before continuing. Database files and backups live
outside the checkout and survive container replacement. For secret-only changes,
edit `.env.production` and run `docker compose up -d --force-recreate --wait api`;
`docker compose restart` alone does not load new environment values. Frontend
source changes require rebuilding the web image.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Certificate issuance fails | DNS A/AAAA records, provider firewall, port 80 reachability |
| 502 from Nginx | `docker compose ps`, API/web logs, loopback listeners on 3002/8088 |
| Login fails or returns 403 | Exact `DASHBOARD_ORIGIN`, HTTPS URL, proxy headers, unquoted raw password hash |
| 429 after login attempts | Wait for the 15-minute rate-limit window |
| Android 401/403 | Correct server URL and matching device token; resave Settings |
| Garmin unavailable | Server Garmin API key, outbound connectivity, API logs |
| SQLite permission error | Data/backup directories owned by UID/GID 1000 and writable by the container |
| Backup missing | `journalctl -u stridehub-backup.service`, timer status, disk space, running API |

## References

- [Docker Engine installation on Ubuntu](https://docs.docker.com/engine/install/ubuntu/) and [Debian](https://docs.docker.com/engine/install/debian/)
- [Docker host networking](https://docs.docker.com/engine/network/drivers/host/)
- [Compose environment files](https://docs.docker.com/compose/how-tos/environment-variables/set-environment-variables/)
- [Nginx proxy configuration](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)
- [Certbot installation alternatives](https://eff-certbot.readthedocs.io/en/stable/install.html)
- [SQLite online backups](https://sqlite.org/backup.html)
