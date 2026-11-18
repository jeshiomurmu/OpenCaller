#!/usr/bin/env bash
# Prints the "sha256/…" public-key pins for the play flavor's opencaller.apiPins (docs/release/signing.md).
#
# Caddy makes a new key at every renewal, so pinning the server's own key would lock every installed app out within
# about 60 days. OkHttp's CertificatePinner accepts a pin of any certificate in the verified chain, root included, so
# the pins are Let's Encrypt's two roots (ISRG Root X1, RSA; ISRG Root X2, ECDSA): each is a backup for the other, and
# both outlive many releases. If you change certificate authority, ship an app with the new pins first.
#
# Usage: pins.sh [api domain]   (with a domain it also shows the chain the server sends and checks a root matches)
set -euo pipefail

pin() { openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64; }

pins=()
for root in ISRG_Root_X1 ISRG_Root_X2; do
  file="/etc/ssl/certs/$root.pem"
  [[ -f "$file" ]] || { echo "$file not found (install ca-certificates)" >&2; exit 1; }
  p="sha256/$(pin < "$file")"
  pins+=("$p")
  echo "$root: $p"
done

if [[ -n "${1:-}" ]]; then
  echo
  echo "Chain sent by $1 (the issuing root is not sent; it comes from the phone's trust store):"
  chain="$(openssl s_client -connect "$1:443" -servername "$1" -showcerts < /dev/null 2> /dev/null)"
  awk '/BEGIN CERTIFICATE/,/END CERTIFICATE/' <<< "$chain" | csplit -s -z -f "${TMPDIR:-/tmp}/oc-cert-" - '/BEGIN CERTIFICATE/' '{*}'
  for f in "${TMPDIR:-/tmp}"/oc-cert-*; do
    echo "  $(openssl x509 -in "$f" -noout -subject) / $(openssl x509 -in "$f" -noout -issuer)"
    rm -f "$f"
  done
  if grep -q "ISRG Root X" <<< "$chain"; then
    echo "OK: the chain leads to an ISRG root."
  else
    echo "WARNING: the chain doesn't name an ISRG root; these pins would not match $1." >&2
    exit 1
  fi
fi

echo
echo "gh secret set OPENCALLER_API_PINS --repo jeshiomurmu/OpenCaller --body '$(IFS=,; echo "${pins[*]}")'"
