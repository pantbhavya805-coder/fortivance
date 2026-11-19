# Deploying Fortivance to the VPS

Target: GoDaddy VPS, Ubuntu 24.04, 4 vCPU. The app and MySQL run in Docker; nginx on the host
terminates HTTPS and proxies to the app on 127.0.0.1:8080. MySQL is not exposed outside Docker.

Replace `YOUR_DOMAIN` (for example `fortivance.example.com`) and `SERVER_IP` throughout.

## 1. Point the domain at the server (GoDaddy)

1. GoDaddy > My Products > the domain > **DNS** > **Manage DNS**.
2. Add (or edit) a record: Type **A**, Name `@` for the bare domain or a subdomain such as `fortivance`,
   Value `SERVER_IP`, TTL 600 seconds (or the lowest offered).
3. Check from your own machine until it returns the server IP (usually a few minutes):
   ```
   nslookup YOUR_DOMAIN
   ```
   Certbot in step 7 fails until this resolves.

## 2. Log in and update the server

```bash
ssh root@SERVER_IP            # or the user GoDaddy created
apt update && apt upgrade -y
timedatectl set-timezone Asia/Kolkata   # optional; containers already run in IST
```

If the VPS has less than 4 GB RAM, add swap so the Maven build does not run out of memory:
```bash
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

## 3. Install Docker

```bash
apt install -y ca-certificates curl
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  > /etc/apt/sources.list.d/docker.list
apt update
apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
docker --version && docker compose version
```

## 4. Firewall (ufw)

Allow SSH first so you do not lock yourself out:
```bash
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw enable
ufw status
```
Docker can bypass ufw for published ports. This setup is safe because the app is published only on
127.0.0.1 and MySQL is not published at all. Do not add `ports:` to MySQL in `docker-compose.prod.yml`.

## 5. Clone the repository and create `.env`

```bash
apt install -y git
mkdir -p /opt && cd /opt
git clone https://github.com/BhavyaPant-bly/fortivance.git
cd fortivance
```
If the repository is private, use a GitHub personal access token (read-only) when prompted for the password,
or a deploy key.

Create `.env` on the server only (never commit it):
```bash
nano .env
```
```
ANTHROPIC_API_KEY=<the key from the console>
DB_NAME=fortivance
DB_USER=fortivance
DB_PASSWORD=<long random password>
DB_ROOT_PASSWORD=<another long random password>
FORTIVANCE_DAILY_CAP_USD=3.00
```
Generate passwords with `openssl rand -base64 24`. Then restrict the file:
```bash
chmod 600 .env
```

## 6. Start the app

```bash
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml logs -f app     # wait for "Started FortivanceApplication", then Ctrl+C
curl -s http://127.0.0.1:8080/actuator/health             # {"status":"UP"}
```
The first build downloads Maven dependencies and takes several minutes. Flyway creates the schema and
loads the synthetic data on first start.

## 7. nginx and HTTPS (certbot)

```bash
apt install -y nginx certbot python3-certbot-nginx
cp deploy/nginx-fortivance.conf /etc/nginx/sites-available/fortivance
sed -i 's/YOUR_DOMAIN/fortivance.example.com/' /etc/nginx/sites-available/fortivance   # use your domain
ln -s /etc/nginx/sites-available/fortivance /etc/nginx/sites-enabled/fortivance
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx
```
Check `http://YOUR_DOMAIN` loads the login page, then add HTTPS:
```bash
certbot --nginx -d YOUR_DOMAIN --redirect -m <your email> --agree-tos -n
certbot renew --dry-run
```
Certbot edits the nginx file to add the HTTPS server block and the HTTP-to-HTTPS redirect, and installs
a renewal timer.

Open `https://YOUR_DOMAIN`, log in as `analyst` / `Fortivance@2026`, and upload one incident notice from
`sample-docs` to confirm the AI calls work through the proxy.

## 8. Demo reset

With the containers running, from `/opt/fortivance`:
```bash
bash scripts/reset-demo.sh
```
This clears incidents, actions and the audit log, and keeps vendors, documents, evidence findings and the
AI result cache, so re-uploading the demo notices costs nothing.

## Updating after new commits

```bash
cd /opt/fortivance
git pull
docker compose -f docker-compose.prod.yml up -d --build
```
The database volume (`mysql-data`) is kept across rebuilds.

## Operations

- Logs: `docker compose -f docker-compose.prod.yml logs -f app`
- Restart: `docker compose -f docker-compose.prod.yml restart app`
- Stop everything: `docker compose -f docker-compose.prod.yml down` (add `-v` only to wipe the database)
- SQL shell:
  `docker exec -it fortivance-mysql sh -c 'mysql -u$MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE'`
- Containers restart automatically after a reboot (`restart: always`; Docker starts at boot by default).

## Security notes

- The demo accounts and their password are shown on the login page. Anyone who finds the URL can log in and
  trigger AI calls; spend is limited by `FORTIVANCE_DAILY_CAP_USD` (default $3 per day) and by the spend limit
  on the Anthropic organisation. Lower the cap if the URL will be shared widely.
- Keep `.env` readable only by root (`chmod 600`). Never copy it into the repository or a container image.
- MySQL has no published port; use the SQL shell above instead of opening 3306.
