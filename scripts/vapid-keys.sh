#!/usr/bin/env bash
# Prints a new VAPID key pair for Web Push / UnifiedPush (RFC 8292) as .env lines:
#   VAPID_PUBLIC_KEY   65-byte uncompressed P-256 point, base64url (87 characters)
#   VAPID_PRIVATE_KEY  32-byte private scalar, base64url (43 characters)
# Usage: scripts/vapid-keys.sh >> infra/docker/.env   (keep the private key secret; changing it breaks
# every existing UnifiedPush registration until the apps register again).
set -euo pipefail

# hex on stdin → base64url without padding (printf instead of xxd, which minimal images lack).
b64url() { printf '%b' "$(sed 's/../\\x&/g')" | base64 -w0 | tr '+/' '-_' | tr -d '='; }

pem="$(openssl ecparam -name prime256v1 -genkey -noout)"
text="$(openssl ec -text -noout 2>/dev/null <<<"$pem")"
# `openssl ec -text` prints "priv:" and "pub:" followed by hex bytes over several lines.
hex_of() { awk -v k="$1:" '$1==k{f=1;next} /^[A-Za-z]/{f=0} f' <<<"$text" | tr -d ' :\n'; }
priv="$(hex_of priv)"
pub="$(hex_of pub)"
# The private scalar may be printed with a leading 00 byte or without leading zeros: make it exactly 32 bytes.
priv="$(printf '%064s' "${priv: -64}" | tr ' ' 0)"
[[ ${#pub} -eq 130 && ${pub:0:2} == 04 ]] || { echo "unexpected public key: $pub" >&2; exit 1; }

echo "VAPID_PUBLIC_KEY=$(b64url <<<"$pub")"
echo "VAPID_PRIVATE_KEY=$(b64url <<<"$priv")"
