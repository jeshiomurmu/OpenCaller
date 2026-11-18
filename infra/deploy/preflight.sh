#!/usr/bin/env bash
# Checks infra/deploy/.env before `docker compose up`: required values, DNS, free ports, the release's images.
# Prints every problem, exits 1 if there was one.
set -uo pipefail
cd "$(dirname "$0")"

problems=0
fail() { echo "FAIL: $*"; problems=$((problems + 1)); }
ok() { echo "ok:   $*"; }

[[ -f .env ]] || { echo "FAIL: no .env (run ./init-env.sh)"; exit 1; }
[[ "$(stat -c %a .env)" == "600" ]] || fail ".env should be chmod 600"
set -a
# shellcheck disable=SC1091
. ./.env
set +a

for name in OPENCALLER_VERSION PUBLIC_IP API_DOMAIN MEDIA_DOMAIN LIVEKIT_DOMAIN GRAFANA_DOMAIN ACME_EMAIL \
  POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD REDIS_PASSWORD MINIO_ROOT_USER MINIO_ROOT_PASSWORD JWT_SECRET \
  OTP_PEPPER ADMIN_WEB_USER ADMIN_WEB_PASSWORD LIVEKIT_API_SECRET GRAFANA_ADMIN_PASSWORD; do
  value="${!name:-}"
  if [[ -z "$value" || "$value" == "<generated>" ]]; then fail "$name is empty"; fi
done
for name in API_DOMAIN MEDIA_DOMAIN LIVEKIT_DOMAIN GRAFANA_DOMAIN; do
  [[ "${!name:-}" == *example.org ]] && fail "$name is still the example (${!name})"
done
for name in JWT_SECRET OTP_PEPPER; do
  value="${!name:-}"
  [[ ${#value} -lt 32 ]] && fail "$name must be at least 32 characters"
done
if [[ "${OTP_PROVIDER:-}" == "twilio" && -z "${TWILIO_ACCOUNT_SID:-}" ]]; then
  fail "OTP_PROVIDER=twilio needs TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN and TWILIO_FROM or TWILIO_MESSAGING_SERVICE_SID"
fi
[[ -n "${VAPID_PUBLIC_KEY:-}" && -z "${VAPID_SUBJECT:-}" ]] && fail "VAPID_SUBJECT is empty (mailto:you@example.org)"

if [[ "${CADDY_EXTRA_GLOBAL:-}" != *local_certs* ]]; then
  for name in API_DOMAIN MEDIA_DOMAIN LIVEKIT_DOMAIN GRAFANA_DOMAIN; do
    ip="$(getent ahostsv4 "${!name}" | awk 'NR==1{print $1}')"
    if [[ "$ip" == "${PUBLIC_IP:-}" ]]; then ok "${!name} → $ip"; else fail "${!name} resolves to '${ip:-nothing}', not PUBLIC_IP ${PUBLIC_IP:-}"; fi
  done
fi

if command -v ss > /dev/null; then
  for port in 80 443; do
    if ss -ltnH "sport = :$port" | grep -q . && ! docker ps --format '{{.Names}}' | grep -q '^opencaller-caddy'; then
      fail "port $port is already in use"
    fi
  done
else
  echo "skip: ports 80/443 not checked (no ss; apt install iproute2)"
fi

for image in server transcoder minio; do
  ref="ghcr.io/jeshiomurmu/opencaller-$image:${OPENCALLER_VERSION:-}"
  if docker manifest inspect "$ref" > /dev/null 2>&1 || docker image inspect "$ref" > /dev/null 2>&1; then
    ok "$ref"
  else
    fail "image $ref not found (is OPENCALLER_VERSION a released version, and is the package public?)"
  fi
done

# Backups are set up after the first start (README §6); until then this is a reminder, not a failure.
if [[ -n "${BACKUP_REMOTE:-}" && ! -f backup/rclone.conf ]]; then
  echo "note: backup/rclone.conf is missing; set up backups next (README §6)"
fi

if [[ $problems -gt 0 ]]; then
  echo "$problems problem(s); fix them and run ./preflight.sh again."
  exit 1
fi
echo "Preflight passed: docker compose -f compose.prod.yml up -d"
