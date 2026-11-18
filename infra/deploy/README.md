# Deploying the OpenCaller backend on a VPS

One Ubuntu LTS server runs the whole backend from the release's images: the API server, the video transcoder,
PostgreSQL, Redis, MinIO, LiveKit (app calls), Caddy (TLS) and Prometheus + Grafana. Backups go off-site every night;
uptime checks and the status page run on a second machine. The recording line, the call assistant and the voice
scanner are optional and have their own guide (`telephony.md`).

Files in this folder:

| File | What it is |
| --- | --- |
| `compose.prod.yml` | the production stack; images `ghcr.io/jeshiomurmu/opencaller-*:<version>` |
| `Caddyfile`, `caddy-sites/` | TLS and routing for the four domains (extra sites as `caddy-sites/*.caddy`) |
| `.env.example`, `init-env.sh` | settings; `init-env.sh` writes `.env` with fresh secrets |
| `preflight.sh` | checks `.env`, DNS, ports and images before the first start |
| `backup/backup.sh`, `backup/restore-test.sh`, `backup/rclone.conf.example` | nightly dump off-site, restore test |
| `monitoring/` | Prometheus scrape config, Grafana data source and the overview dashboard |
| `status/compose.status.yml` | Uptime Kuma (checks and status page) for a second machine |
| `pins.sh` | certificate pins for play builds |
| `sip-cert.sh`, `telephony.md` | the optional telephony services and SIP over TLS |

## Fresh server in under an hour

Before you start (not counted): a domain you can add DNS records to, a Twilio account with an SMS-capable sender
(sign-in codes), an S3-compatible bucket for backups (Backblaze B2, Cloudflare R2, Wasabi, AWS S3, …) with an access
key, and a GitHub release whose images exist (Actions → Images, green for the tag).

| Step | Section | Minutes |
| --- | --- | --- |
| [ ] Create the VPS (Ubuntu 24.04 or 26.04 LTS, 4 GB RAM, 2 vCPU, 60 GB disk, public IPv4) | 1 | 5 |
| [ ] Add the four A records (`api`, `media`, `calls`, `grafana`) | 1 | 5 |
| [ ] SSH key login, a sudo user, updates, firewall | 2 | 10 |
| [ ] Install Docker from Docker's repository | 3 | 5 |
| [ ] Clone the release tag, `./init-env.sh`, fill in `.env` | 4 | 10 |
| [ ] `./preflight.sh`, `docker compose up -d`, check `/health` | 5 | 5 |
| [ ] Backups: `rclone.conf`, first backup, restore test, cron | 6 | 10 |
| [ ] Grafana login; uptime checks on the second machine | 7, 8 | 10 |

Total about 60 minutes; most of it is waiting for packages and images. The local test run of this guide is recorded
in `docs/phase.md` (Phase 15).

## 1. Server and DNS

- **Size.** 4 GB RAM and 2 vCPU carry the API, the database and a few dozen simultaneous app calls; the transcoder
  (ffmpeg) is the heaviest part while users upload video caller IDs. Plan 60 GB disk: database, MinIO (pictures,
  clips, spam lists) and seven days of local dumps.
- **Ubuntu LTS**, 64-bit: Docker supports 22.04, 24.04 and 26.04 (Docker's install page). Take 24.04 or 26.04.
- **DNS.** Four A records pointing at the server's public IPv4 address, for example:

  | Name | Used for |
  | --- | --- |
  | `api.example.org` | the API, the business portal (`/portal`) and the unlist page; the app's server address |
  | `media.example.org` | presigned links to pictures, video clips, recordings and spam lists (MinIO behind Caddy) |
  | `calls.example.org` | LiveKit signalling for app calls (WebSocket) |
  | `grafana.example.org` | Grafana (its own login) |

  Lower the records' TTL to 300 s while you set up. AAAA records are optional; LiveKit announces the IPv4 address.

## 2. Log in, update, firewall

As root (or the provider's first user), once:

```shell
adduser opencaller && usermod -aG sudo opencaller
mkdir -p /home/opencaller/.ssh && cp ~/.ssh/authorized_keys /home/opencaller/.ssh/
chown -R opencaller:opencaller /home/opencaller/.ssh && chmod 700 /home/opencaller/.ssh
apt update && apt full-upgrade -y && apt install -y unattended-upgrades git openssl ca-certificates curl
```

Log in again as `opencaller`, then turn off password logins (`PasswordAuthentication no` in
`/etc/ssh/sshd_config.d/50-opencaller.conf`, `sudo systemctl reload ssh`) after checking that key login works in a
second terminal.

Firewall: allow SSH, HTTPS and LiveKit's media ports, nothing else.

```shell
sudo ufw default deny incoming
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp          # Let's Encrypt HTTP challenge and the redirect to HTTPS
sudo ufw allow 443/tcp
sudo ufw allow 443/udp         # HTTP/3
sudo ufw allow 7881/tcp        # LiveKit: WebRTC over TCP
sudo ufw allow 3478/udp        # LiveKit: TURN
sudo ufw allow 50000:50200/udp # LiveKit: WebRTC media
sudo ufw enable
```

Ports published by Docker bypass ufw (Docker's install page, "Firewall limitations"); `compose.prod.yml` therefore
publishes only the ports above, plus the admin web on `127.0.0.1` (§9). Prometheus, Grafana, PostgreSQL, Redis, MinIO
and the server's metrics port publish nothing. If your provider has a cloud firewall, open the same ports there.

## 3. Install Docker

From Docker's official page (docs.docker.com → Install Docker Engine on Ubuntu → Install using the apt repository).
Remove distribution packages that conflict, add Docker's repository, install:

```shell
sudo apt remove $(dpkg --get-selections docker.io docker-compose docker-compose-v2 docker-doc docker-buildx podman-docker containerd runc | cut -f1)

sudo apt update
sudo apt install ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

sudo tee /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF

sudo apt update
sudo apt install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl status docker      # active (running)
sudo docker run hello-world
```

`apt` may say none of the conflicting packages are installed; that's fine. The commands below use `sudo docker`; to
drop `sudo`, add the user to the `docker` group (Docker's post-installation steps; the group is root-equivalent).

## 4. Get the release and write `.env`

Pick the release to run (the latest GitHub release, e.g. `v1.1.0`) and clone that tag:

```shell
sudo mkdir -p /opt/opencaller && sudo chown opencaller: /opt/opencaller
git clone --depth 1 --branch v1.1.0 https://github.com/jeshiomurmu/OpenCaller.git /opt/opencaller
cd /opt/opencaller/infra/deploy
./init-env.sh
```

`init-env.sh` writes `.env` (mode 600) with a fresh random value for every secret marked `<generated>` and a VAPID key
pair for UnifiedPush, and creates `backups/` and `secrets/`. It refuses to overwrite an existing `.env`. Edit `.env`:

- `OPENCALLER_VERSION`: the release without the `v` (`1.1.0`); the images are tagged with it.
- `PUBLIC_IP`, the four `*_DOMAIN` names, `ACME_EMAIL` (Let's Encrypt expiry mail).
- Twilio: `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` and `TWILIO_FROM` (or `TWILIO_MESSAGING_SERVICE_SID`). Send one
  test message with Twilio's test credentials first (`docs/phase.md` issue 45).
- `VAPID_SUBJECT=mailto:you@example.org` (push services contact this address about problems).
- play builds only: put the Firebase service-account key in `secrets/fcm.json` and set
  `FCM_SERVICE_ACCOUNT_FILE=/secrets/fcm.json`, `FCM_PROJECT_ID`; for Play billing, `secrets/play.json`,
  `GOOGLE_PLAY_SERVICE_ACCOUNT_FILE=/secrets/play.json` and `GOOGLE_PLAY_PACKAGE_NAME`.
- `SELF_HOSTED=true` if the server is for you or your community (every supporter feature unlocked, no badge).

Keep a copy of `.env` in your password manager: losing `JWT_SECRET` logs everyone out, losing `OTP_PEPPER` or
`BILLING_ACCOUNT_SECRET` breaks stored hashes, and the database password is needed to restore a backup into a new
server.

## 5. Start

```shell
./preflight.sh
sudo docker compose -f compose.prod.yml pull
sudo docker compose -f compose.prod.yml up -d
sudo docker compose -f compose.prod.yml ps
curl -fsS https://api.example.org/health
```

`preflight.sh` stops with a message when something is missing: unset variables, example domains, short secrets, DNS
that doesn't point at `PUBLIC_IP`, ports 80/443 already in use, an image that doesn't exist for
`OPENCALLER_VERSION`, or `BACKUP_REMOTE` without `backup/rclone.conf`.

On start the server migrates the database with **Flyway** (`backend/server/src/main/resources/db/migration`); the
log shows `Successfully applied N migrations` the first time and `Schema … is up to date` afterwards:

```shell
sudo docker compose -f compose.prod.yml logs server | grep -iE "flyway|migrat|Responding"
```

Caddy gets the certificates in the first minute (`logs caddy`, "certificate obtained successfully"). Then:

- `https://api.example.org/health` answers `{"status":"ok",…}`.
- `https://media.example.org/` answers 404 (only presigned bucket links are served).
- `https://grafana.example.org` shows Grafana's login (user `admin`, password `GRAFANA_ADMIN_PASSWORD` from `.env`).

Point the apps at the server: release builds take `https://api.example.org` from `OPENCALLER_API_BASE_URL` and the
pins from `OPENCALLER_API_PINS` (`docs/release/signing.md`, Certificate pins below).

### Certificate pins

play builds pin the server's certificate chain (rules.md §7). Caddy makes a new key at every renewal (about every 60
days), so the pins are Let's Encrypt's two roots, not the server's own key: OkHttp accepts a pin of any certificate
in the verified chain, and ISRG Root X1 (RSA) and X2 (ECDSA) back each other up.

```shell
./pins.sh api.example.org
```

prints both pins, checks that the server's chain leads to an ISRG root and prints the `gh secret set
OPENCALLER_API_PINS …` line. If you ever move away from Let's Encrypt, ship an app with the new pins first and switch
the certificate after users have updated. foss builds use the system's trust store.

## 6. Backups

`backup/backup.sh` dumps PostgreSQL (`pg_dump --format=custom`), checks the dump with `pg_restore --list`, copies it
to `BACKUP_REMOTE` with rclone and prunes: local dumps after 7 days, off-site ones after `BACKUP_KEEP_DAYS` (30).
`BACKUP_MEDIA=true` also syncs the MinIO bucket. Redis holds only rate limits and short-lived state and isn't backed
up.

The dumps contain account phone numbers, so the remote encrypts them (an rclone `crypt` remote over S3):

```shell
cp backup/rclone.conf.example backup/rclone.conf && chmod 600 backup/rclone.conf
# Fill in the endpoint, region and access key under [offsite] and the bucket name in [offsite-crypt], then:
openssl rand -hex 32    # passphrase 1: save it in your password manager
openssl rand -hex 32    # passphrase 2: the same
sudo docker run --rm rclone/rclone:1.76.0 obscure <passphrase 1>   # → password = … in [offsite-crypt]
sudo docker run --rm rclone/rclone:1.76.0 obscure <passphrase 2>   # → password2 = …
```

Keep both passphrases outside the server: without them the off-site dumps can't be decrypted, and a lost server takes
the obscured copies in `rclone.conf` with it.

First backup and restore test:

```shell
sudo ./backup/backup.sh                     # "Backup … copied to offsite-crypt:opencaller/db/"
sudo ./backup/restore-test.sh               # restores the newest local dump into a throwaway PostgreSQL
sudo ./backup/restore-test.sh --remote      # the same with the newest off-site dump
```

`restore-test.sh` restores with `pg_restore --exit-on-error` and prints the Flyway version and the row counts of
`users`, `devices` and `reports` next to the live database's (a nightly dump trails the live counts by a day). It exits
1 when the download or restore fails or the dump has no Flyway history. Then the cron jobs (as root, in
`/etc/cron.d/opencaller`):

```shell
sudo tee /etc/cron.d/opencaller <<'EOF'
# m h dom mon dow user command
17 3 * * * root /opt/opencaller/infra/deploy/backup/backup.sh >> /var/log/opencaller-backup.log 2>&1
47 4 * * 0 root /opt/opencaller/infra/deploy/backup/restore-test.sh --remote >> /var/log/opencaller-backup.log 2>&1
EOF
```

Add a "push" check in Uptime Kuma (§8) or healthchecks.io and put its URL in `BACKUP_PING_URL`: a night without a
successful backup then raises an alert.

**Restoring after a loss**, on a new server: follow §2–§4 with the old `.env`, start only PostgreSQL
(`docker compose -f compose.prod.yml up -d postgres`), copy a dump into `backups/` (from the remote, with the same `rclone.conf`:
`sudo docker run --rm -v $PWD/backup/rclone.conf:/config/rclone/rclone.conf:ro -v $PWD/backups:/backups
rclone/rclone:1.76.0 copy offsite-crypt:opencaller/db/<file> /backups/`), restore it with
`sudo docker compose -f compose.prod.yml exec -T postgres pg_restore -U opencaller -d opencaller --clean --if-exists
/backups/<file>`, then `up -d` the rest.

## 7. Monitoring

Prometheus (`monitoring/prometheus.yml`) scrapes the server's metrics port, MinIO, Caddy, PostgreSQL (postgres-exporter)
and the host (node-exporter) every 30 s and keeps 30 days. Grafana has the Prometheus data source and the
**OpenCaller overview** dashboard provisioned: request rate and 5xx rate, p95 latency, JVM heap, database connections,
CPU, memory and disk. Neither publishes a port; Grafana is reached through Caddy with its own login. Change the admin
password on first login if you want one that isn't in `.env`.

Worth an alert (Grafana → Alerting, contact point e-mail or a chat webhook): disk above 80 %, 5xx rate above 1 % for
10 minutes, the server's p95 above 500 ms.

## 8. Uptime checks and status page

Run Uptime Kuma on a **different machine** (a second small VPS, a home server), so it still reports when this VPS is
down:

```shell
git clone --depth 1 https://github.com/jeshiomurmu/OpenCaller.git && cd OpenCaller/infra/deploy/status
echo STATUS_DOMAIN=status.example.org > .env    # its A record points at this machine
sudo docker compose -f compose.status.yml up -d
```

Open `https://status.example.org`, create the admin account, then add monitors:

| Monitor | Type | Target |
| --- | --- | --- |
| API | HTTP(s) – keyword | `https://api.example.org/health`, keyword `ok`, every 60 s |
| Media | HTTP(s) | `https://media.example.org/`, accepted status 404 |
| Calls | HTTP(s) | `https://calls.example.org/` (LiveKit answers 200 "OK") |
| Certificate | the API monitor's "Certificate expiry notification" | warn 14 days before |
| Backups | Push | its URL in `BACKUP_PING_URL`, heartbeat 26 h |

Add a notification (e-mail or a chat app) and a status page (Status Pages → New) listing API, Media and Calls; link
it from the README or the store listing. A hosted checker works too if you'd rather not run a second machine.

## 9. Admin web and admin jobs

The admin web (claims, scam cards, recording lines, audit log) listens on the server's port 8081, published on the
VPS's loopback only. Reach it through an SSH tunnel:

```shell
ssh -L 8081:127.0.0.1:8081 opencaller@<server>
# then open http://127.0.0.1:8081/ on your computer; user ADMIN_WEB_USER, password ADMIN_WEB_PASSWORD
```

Requests through the tunnel reach the server from the Docker network's gateway (`172.30.0.1`), the only address in
`ADMIN_WEB_ALLOWED_NETWORKS`. The admin jobs (`POST /admin/jobs/{scoring|lists}` with `ADMIN_TOKEN`) are on the
metrics port inside the Docker network:

```shell
sudo docker compose -f compose.prod.yml exec server \
  sh -c 'curl -fsS -X POST -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:9090/admin/jobs/lists'
```

## 10. Updates and rollback

A new release: check its CHANGELOG entry for notes (new variables, a minimum app version), then

```shell
cd /opt/opencaller && git fetch --depth 1 origin tag v1.2.0 && git checkout v1.2.0
cd infra/deploy && diff <(grep -oE '^[A-Z_]+=' .env.example) <(grep -oE '^[A-Z_]+=' .env)   # new settings?
sudo ./backup/backup.sh                                   # a dump from just before the update
sed -i 's/^OPENCALLER_VERSION=.*/OPENCALLER_VERSION=1.2.0/' .env
sudo docker compose -f compose.prod.yml pull && sudo docker compose -f compose.prod.yml up -d
```

Flyway migrates forward on start. Rolling back the images (`OPENCALLER_VERSION` back, `up -d`) works while the new
release added no migration; after a migration, restore the pre-update dump (§6) together with the old version.

`MIN_APP_VERSION_CODE` makes the server answer older apps with 426 `upgrade_required`, and the app asks the user to
update. Raise it only after the release that needs it has reached the stores (Play's staged rollout at 100 %, the
F-Droid build published), or those users are locked out.

Keep the host updated: `unattended-upgrades` installs Ubuntu's security updates; check `sudo docker compose pull`
monthly for the third-party images, after bumping their tags in `compose.prod.yml` from a new OpenCaller release.

## 11. Telephony (optional)

The recording line, the call assistant and the voice scanner need a SIP provider, numbers, LiveKit SIP and Egress and
the agents; `telephony.md` covers them, including SIP over TLS (`infra/docker/compose.sip-tls.yml`).

**Not on the same server as `compose.prod.yml` yet.** The telephony services come from `infra/docker` with the
telephony overlays: they run with host networking and expect LiveKit and Redis on the host's loopback, while
`compose.prod.yml` keeps LiveKit and Redis inside its Docker network and publishes LiveKit's ports itself. Run
together they would clash on LiveKit's ports. Until the production stack includes them (`docs/phase.md` issue 155), run
the telephony setup on its own server following `telephony.md`, or leave the recording line, assistant and scanner
off (the default: the server offers none of them unless `RECORDING_LINES`, `ASSISTANT_NUMBERS` or `SCANNER_ENABLED`
is set).

## 12. Troubleshooting

| Symptom | Look at |
| --- | --- |
| Caddy keeps failing to get a certificate | DNS (`dig +short api.example.org` = `PUBLIC_IP`), port 80 open in ufw and the provider's firewall; Let's Encrypt rate limits after many attempts (use `CADDY_EXTRA_GLOBAL=local_certs` while debugging) |
| `server` restarts | `logs server`: a missing variable names itself; a database password changed after the first start (PostgreSQL keeps the first one) |
| Pictures or clips don't load in the app | `MEDIA_DOMAIN` must resolve and serve the bucket path; `curl -I` a presigned link from the app's log (debug build) |
| App calls connect but no audio | UDP 50000–50200 and 3478, TCP 7881 open; `PUBLIC_IP` is the public address, not a private one |
| Everyone shares one rate limit | `TRUST_FORWARDED_HEADERS` must be `true` behind Caddy (it is in `compose.prod.yml`) |
| Backup fails | `/var/log/opencaller-backup.log`; `docker run --rm -v $PWD/backup/rclone.conf:/config/rclone/rclone.conf:ro rclone/rclone:1.76.0 lsd offsite:` |

## Local test of this guide

To try the stack without a VPS or public DNS (as the Phase 15 check did): any Linux machine with Docker, images built
from the checkout and tagged with the GHCR names, the domains in `/etc/hosts`, and Caddy's own CA:

```shell
for i in server:runtime transcoder:transcoder; do
  sudo docker build -t ghcr.io/jeshiomurmu/opencaller-${i%%:*}:1.1.0 --target ${i##*:} backend/server
done
sudo docker build -t ghcr.io/jeshiomurmu/opencaller-minio:1.1.0 infra/docker/minio
cd infra/deploy && ./init-env.sh
# .env: PUBLIC_IP=127.0.0.1, *.opencaller.test domains, CADDY_EXTRA_GLOBAL=local_certs, OTP_PROVIDER=log is refused in
# production, so set dummy Twilio values; /etc/hosts: 127.0.0.1 api.opencaller.test media.opencaller.test …
./preflight.sh && sudo docker compose -f compose.prod.yml up -d
curl -fsS --cacert <(sudo docker compose -f compose.prod.yml exec -T caddy cat /data/caddy/pki/authorities/local/root.crt) https://api.opencaller.test/health
```
