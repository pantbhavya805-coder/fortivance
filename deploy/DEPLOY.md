# Deploying Fortivance to the VPS

Target: GoDaddy VPS, Ubuntu 24.04, 4 vCPU, 8 GB RAM. Everything runs in Docker under a normal user
(no sudo needed):

- `caddy` (Caddy 2): the only container with published ports (80, 443). It terminates HTTPS, obtains and
  renews the Let's Encrypt certificate automatically, and proxies to the app. Config: `deploy/Caddyfile`.
- `app`: Spring Boot on the internal Docker network only (port 8080 is not published).
- `mysql`: MySQL 8.4 on the internal Docker network only (no published port).

Replace `YOUR_DOMAIN` (for example `fortivance.example.com`) and `SERVER_IP` throughout.

## 0. Prerequisites (one-time, needs whoever administers the server)

The deploy user has no sudo, so these must already be in place. Check them as the deploy user:

```bash
docker --version && docker compose version   # Docker Engine and the compose plugin are installed
docker ps                                    # works without sudo (user is in the "docker" group)
git --version
```

If `docker ps` says "permission denied", ask the server administrator to run
`usermod -aG docker <your user>`, then log out and back in.

Ports 80 and 443 must be reachable from the internet (Caddy needs both for the certificate). If the server
has a firewall (ufw or a GoDaddy firewall rule), the administrator must allow 22, 80 and 443. You can check
from your own machine after step 4: `curl -I http://YOUR_DOMAIN` should get a response, not a timeout.

Resources: `free -h`, `nproc` and `df -h /` show memory, CPUs and disk. 8 GB RAM is enough without swap.

## 1. Point the domain at the server (GoDaddy)

1. GoDaddy > My Products > the domain > **DNS** > **Manage DNS**.
2. Add (or edit) a record: Type **A**, Name `@` for the bare domain or a subdomain such as `fortivance`,
   Value `SERVER_IP`, TTL 600 seconds (or the lowest offered).
3. Check from your own machine until it returns the server IP (usually a few minutes):
   ```
   nslookup YOUR_DOMAIN
   ```

Do this before step 4: Caddy requests the certificate when it starts, and Let's Encrypt can only issue it
once the domain resolves to this server. Without a domain yet, see "No domain yet" below.

## 2. Clone the repository

```bash
mkdir -p ~/apps && cd ~/apps
git clone https://github.com/BhavyaPant-bly/fortivance.git
cd fortivance
```
If the repository is private, use a GitHub personal access token (read-only) as the password when prompted,
or a deploy key.

## 3. Create `.env`

Create it on the server only (it is in `.gitignore`; never commit it):
```bash
nano .env
```
```
SITE_ADDRESS=YOUR_DOMAIN
ANTHROPIC_API_KEY=<the key from the console>
DB_NAME=fortivance
DB_USER=fortivance
DB_PASSWORD=<long random password>
DB_ROOT_PASSWORD=<another long random password>
```
Generate passwords with `openssl rand -base64 24`. Then restrict the file:
```bash
chmod 600 .env
```

All of `SITE_ADDRESS`, `ANTHROPIC_API_KEY`, `DB_PASSWORD` and `DB_ROOT_PASSWORD` must be set; the compose file
has no fallback for them. Optional: `ANTHROPIC_MODEL` (defaults to `claude-sonnet-5-5`).

Contact form (the About page sends messages to the domain mailbox; see "Domain email and contact form" below):
```
MAIL_USERNAME=admin@fortivance.in
MAIL_PASSWORD=<the mailbox password>
CONTACT_TO=admin@fortivance.in
```

Permanent accounts (created or updated at startup; they never expire). Use single quotes: Docker Compose
treats `$` in an unquoted value as a variable.
```
OWNER_PASSWORD='<password for the bhavya account>'
JUDGE_PASSWORD='<password for the judge account>'
```
The old shared `analyst` / `approver` accounts are disabled by migration V4. Visitors get personal 24-hour
accounts through `/request-demo.html`.

`SITE_ADDRESS` is the address Caddy serves:
- `fortivance.example.com` (your domain): HTTPS with an automatic Let's Encrypt certificate, and HTTP
  redirected to HTTPS.
- `:80`: plain HTTP on the server IP, for testing before DNS is ready.

## 4. Start the stack

```bash
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps                # mysql healthy, app and caddy running
docker compose -f docker-compose.prod.yml logs -f app       # wait for "Started FortivanceApplication", then Ctrl+C
docker compose -f docker-compose.prod.yml logs caddy | grep -i -E "certificate obtained|error"
```
The first build downloads Maven dependencies and takes several minutes. Flyway creates the schema and loads
the synthetic data on first start.

Open `https://YOUR_DOMAIN`. You should see the About page (public), with a "Log in" button. Log in as
`analyst` / `Fortivance@2026`, and upload one incident notice from `sample-docs/incident-notices` to confirm
the AI calls work end to end.

## 5. Demo reset

With the stack running, from the repository folder:
```bash
bash scripts/reset-demo.sh
```
This clears incidents, actions and the audit log, and keeps vendors, documents, evidence findings and the AI
result cache, so re-uploading the demo notices costs no extraction tokens. The script stops with
"Demo reset FAILED" if the MySQL container is not running.

Demo files to upload afterwards:
- `sample-docs/incident-notices/` (three vendor notices)
- `sample-docs/test-inputs/INJECTION-TEST-notice.md` (prompt-injection test; the plan should be withheld)

## Domain email and the demo request form

The mailbox `admin@fortivance.in` is GoDaddy Professional Email (Titan, Pro Light). It sends the welcome email
with demo credentials and receives a notice (without passwords) for every demo request.

1. Send a test email from another account to `admin@fortivance.in` and check it arrives in webmail.
2. Put `MAIL_USERNAME`, `MAIL_PASSWORD` and `CONTACT_TO` in `.env` on the server (never in the repository).
3. Recreate the app: `docker compose -f docker-compose.prod.yml up -d --build app`.
4. Open `https://fortivance.in/request-demo.html`, submit a request with an address you can read, and check:
   the credentials appear on screen, the welcome email arrives, and the notice reaches `admin@fortivance.in`
   (replying to the notice goes to the visitor).

SMTP defaults: `smtpout.secureserver.net`, port 465 (SSL), full email address as the username. If the login is
rejected, set `MAIL_HOST=smtp.titan.email` in `.env` and recreate the app. Errors appear in
`docker compose -f docker-compose.prod.yml logs app | grep -i demo`.

`POST /api/demo-requests` is public. It drops submissions that fill the hidden honeypot field, allows 3 requests
per IP per hour (`DEMO_REQUESTS_PER_HOUR`) and one request per email address. Without `MAIL_USERNAME` it answers
503 and the rest of the app runs normally. Accounts are activated only after the welcome email is sent.

## No domain yet

Set `SITE_ADDRESS=:80` in `.env` and start the stack; the app is then at `http://SERVER_IP`. When the A record
is in place, change `SITE_ADDRESS` to the domain and recreate Caddy so it picks up the new value:
```bash
docker compose -f docker-compose.prod.yml up -d caddy
```
Caddy then obtains the certificate on its own.

## Updating after new commits

```bash
cd ~/apps/fortivance
git pull
docker compose -f docker-compose.prod.yml up -d --build
```
The database (`mysql-data`) and the certificates (`caddy-data`) are kept across rebuilds. After editing only
`deploy/Caddyfile`, reload Caddy instead: `docker compose -f docker-compose.prod.yml restart caddy`.

## Operations

- Status: `docker compose -f docker-compose.prod.yml ps`
- Logs: `docker compose -f docker-compose.prod.yml logs -f app` (or `caddy`, `mysql`)
- Restart the app: `docker compose -f docker-compose.prod.yml restart app`
- Stop everything: `docker compose -f docker-compose.prod.yml down`
  (never add `-v` unless you mean to delete the database and certificates)
- Health check from the server (the app port is internal, so go through Caddy):
  `curl -s https://YOUR_DOMAIN/actuator/health` (or `http://localhost/actuator/health` with `SITE_ADDRESS=:80`)
- SQL shell:
  `docker exec -it fortivance-mysql sh -c 'mysql -u$MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE'`
- All containers use `restart: always`, so they come back after a reboot as long as the Docker service starts
  at boot (the default on Ubuntu).

## Troubleshooting

- **No certificate / browser shows a TLS error**: `docker compose -f docker-compose.prod.yml logs caddy`.
  Usual causes: the A record does not point to `SERVER_IP` yet, or port 80 or 443 is blocked by a firewall.
  Fix the cause, then `docker compose -f docker-compose.prod.yml restart caddy`.
- **"bind: address already in use" for port 80 or 443**: another web server (nginx or Apache) is running on
  the host. Ask the administrator to stop and disable it.
- **502 from Caddy**: the app is still starting or has failed; check `logs app`.
- **App cannot reach MySQL**: check `ps` shows mysql as healthy and that `DB_PASSWORD` in `.env` has not
  changed since the database was first created (MySQL keeps the original password in its volume).

## Security notes

- The demo accounts and their password are shown on the login page. Anyone who finds the URL can log in and
  trigger AI calls; spend is limited by the app's daily cap (`fortivance.daily-cap-usd`, $3 per day by
  default) and by the spend limit on the Anthropic organisation.
- Keep `.env` readable only by your user (`chmod 600`). Never copy it into the repository or an image.
- Only Caddy publishes ports. Do not add `ports:` to `mysql` or `app` in `docker-compose.prod.yml`.
