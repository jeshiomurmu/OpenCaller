#!/usr/bin/env bash
# Creates infra/deploy/.env from .env.example with fresh random secrets (and a VAPID key pair), readable by root only.
# Refuses to overwrite an existing .env: secrets must not change under a running server.
set -euo pipefail
cd "$(dirname "$0")"

if [[ -e .env ]]; then
  echo ".env already exists; edit it by hand (changing a secret logs everyone out or breaks push registrations)." >&2
  exit 1
fi

vapid="$(../../scripts/vapid-keys.sh)"
vapid_public="$(sed -n 's/^VAPID_PUBLIC_KEY=//p' <<< "$vapid")"
vapid_private="$(sed -n 's/^VAPID_PRIVATE_KEY=//p' <<< "$vapid")"

umask 077
while IFS= read -r line; do
  case "$line" in
    VAPID_PUBLIC_KEY=\<generated\>) echo "VAPID_PUBLIC_KEY=$vapid_public" ;;
    VAPID_PRIVATE_KEY=\<generated\>) echo "VAPID_PRIVATE_KEY=$vapid_private" ;;
    *=\<generated\>) echo "${line%%=*}=$(openssl rand -hex 32)" ;;
    *) echo "$line" ;;
  esac
done < .env.example > .env
chmod 600 .env
mkdir -p backups secrets
chmod 700 backups secrets
echo "Wrote .env with new secrets. Now set PUBLIC_IP, the four domains, ACME_EMAIL, the Twilio values and"
echo "VAPID_SUBJECT (mailto:you@example.org), then run ./preflight.sh."
