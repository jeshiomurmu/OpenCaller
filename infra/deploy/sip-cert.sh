#!/usr/bin/env bash
# Copies the certificate Caddy obtained for SIP_DOMAIN into secrets/sip/ for the SIP server's TLS listener
# (infra/docker/compose.sip-tls.yml, telephony.md → Security) and restarts the SIP server when it changed.
# Caddy obtains it because the Caddyfile has a site for {$SIP_DOMAIN}. Run it once after the first start and weekly from
# cron (README → Backups and cron); Let's Encrypt certificates last 90 days and Caddy renews them after about 60.
#
# Usage: sip-cert.sh [compose project directory with the telephony stack, default infra/docker]
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a
: "${SIP_DOMAIN:?set SIP_DOMAIN in infra/deploy/.env}"
telephony_dir="${1:-../docker}"

# Caddy stores certificates under /data/caddy/certificates/<issuer>/<domain>/ (Let's Encrypt or its ZeroSSL fallback).
crt="$(docker compose -f compose.prod.yml exec -T caddy find /data/caddy/certificates -name "$SIP_DOMAIN.crt" | head -1)"
if [[ -z "$crt" ]]; then
  echo "Caddy has no certificate for $SIP_DOMAIN yet (is SIP_DOMAIN in .env, its DNS pointing here, and Caddy up?)" >&2
  exit 1
fi
key="${crt%.crt}.key"
install -d -m 700 secrets/sip
docker compose -f compose.prod.yml exec -T caddy cat "$crt" > secrets/sip/sip.crt.new
docker compose -f compose.prod.yml exec -T caddy cat "$key" > secrets/sip/sip.key.new
chmod 600 secrets/sip/sip.crt.new secrets/sip/sip.key.new

if cmp -s secrets/sip/sip.crt.new secrets/sip/sip.crt 2> /dev/null; then
  rm secrets/sip/sip.crt.new secrets/sip/sip.key.new
  echo "SIP certificate unchanged"
  exit 0
fi
mv secrets/sip/sip.crt.new secrets/sip/sip.crt
mv secrets/sip/sip.key.new secrets/sip/sip.key
echo "SIP certificate updated: $(openssl x509 -in secrets/sip/sip.crt -noout -subject -enddate | tr '\n' ' ')"
# The SIP server reads the certificate at start.
if docker compose --project-directory "$telephony_dir" ps --services --status running 2> /dev/null | grep -qx sip; then
  docker compose --project-directory "$telephony_dir" restart sip
fi
