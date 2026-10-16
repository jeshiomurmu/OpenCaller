#!/usr/bin/env bash
# Smoke test of the API against the compose stack (docs/implementation_plan.md Phase 4). Grows with each endpoint.
#
#   docker compose -f infra/docker/compose.yml up -d --build --wait
#   scripts/smoke.sh
#
# Needs the server with OTP_PROVIDER=log (the development default): codes are read from the server log.
# Env: BASE_URL (default http://localhost:8080), COMPOSE_FILE (default infra/docker/compose.yml), SMOKE_NUMBER.
set -euo pipefail

base="${BASE_URL:-http://localhost:8080}"
compose_file="${COMPOSE_FILE:-infra/docker/compose.yml}"
# A valid number that is reserved for fiction in the North American plan (555-01xx).
number="${SMOKE_NUMBER:-+12015550123}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

pass() { echo "PASS $1"; }
fail() {
  echo "FAIL $1"
  failures=$((failures + 1))
}

# request <name> <method> <path> [json]: writes body to $work/<name>.body and headers to $work/<name>.headers,
# echoes the status code.
request() {
  local name="$1" method="$2" path="$3" body="${4:-}"
  local args=(-sS -o "$work/$name.body" -D "$work/$name.headers" -w '%{http_code}' -X "$method" "$base$path")
  if [[ -n "$body" ]]; then args+=(-H 'Content-Type: application/json' --data "$body"); fi
  curl "${args[@]}"
}

field() { python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$work/$1.body" "$2"; }
header() { grep -i "^$2:" "$work/$1.headers" | head -1 | cut -d: -f2- | tr -d ' \r'; }
expect() {
  local what="$1" want="$2" got="$3"
  if [[ "$got" == "$want" ]]; then pass "$what ($got)"; else fail "$what: expected $want, got $got: $(cat "$work"/*.body 2> /dev/null | tail -c 300)"; fi
}

# The newest development code logged for $number (the log shows the number as +****<last 4 digits>).
latest_code() {
  local last4="${number: -4}"
  for _ in $(seq 1 20); do
    code="$(docker compose -f "$compose_file" logs --no-color server 2> /dev/null |
      grep -o "Development OTP for +\*\*\*\*$last4: [0-9]\{6\}" | tail -1 | grep -o '[0-9]\{6\}$' || true)"
    if [[ -n "$code" ]]; then
      echo "$code"
      return 0
    fi
    sleep 0.5
  done
  return 1
}

# Health and request IDs.
expect "GET /health" 200 "$(request health GET /health)"
[[ -n "$(header health X-Request-Id)" ]] && pass "X-Request-Id on every response" || fail "no X-Request-Id"

# TC-API-004 shape on auth: invalid E.164 → 400 problem+json.
expect "start with an invalid number" 400 "$(request bad POST /v1/auth/start '{"e164":"12345"}')"
expect "  problem+json" "application/problem+json" "$(header bad Content-Type | cut -d';' -f1)"
expect "  code" invalid_phone_number "$(field bad code)"

# Sign in: start → code from the log → verify.
expect "POST /v1/auth/start" 202 "$(request start POST /v1/auth/start "{\"e164\":\"$number\"}")"
code="$(latest_code)" || {
  fail "no development code in the server log (is OTP_PROVIDER=log?)"
  exit 1
}
expect "POST /v1/auth/verify" 200 "$(request verify POST /v1/auth/verify "{\"e164\":\"$number\",\"code\":\"$code\",\"flavor\":\"foss\"}")"
access="$(field verify accessToken)"
refresh="$(field verify refreshToken)"
[[ "$access" == *.*.* ]] && pass "access token is a JWT" || fail "access token: $access"
expect "  access token lifetime" 900 "$(field verify accessTokenExpiresIn)"
expect "the same code again" code_expired "$(request reuse_code POST /v1/auth/verify "{\"e164\":\"$number\",\"code\":\"$code\"}" > /dev/null; field reuse_code code)"

# Refresh rotation (TC-API-003): new token works, the old one is reuse and revokes the device.
expect "POST /v1/auth/refresh" 200 "$(request refresh POST /v1/auth/refresh "{\"refreshToken\":\"$refresh\"}")"
rotated="$(field refresh refreshToken)"
[[ "$rotated" != "$refresh" ]] && pass "refresh token rotated" || fail "refresh token not rotated"
expect "reuse of the old refresh token" 401 "$(request reuse POST /v1/auth/refresh "{\"refreshToken\":\"$refresh\"}")"
expect "  code" refresh_token_reused "$(field reuse code)"
expect "the rotated token after reuse (chain revoked)" invalid_refresh_token "$(request after POST /v1/auth/refresh "{\"refreshToken\":\"$rotated\"}" > /dev/null; field after code)"

# Rate limit (TC-API-001): this number has used 1 of its 3 starts; the 4th is refused.
request start2 POST /v1/auth/start "{\"e164\":\"$number\"}" > /dev/null
request start3 POST /v1/auth/start "{\"e164\":\"$number\"}" > /dev/null
expect "4th start within an hour" 429 "$(request limited POST /v1/auth/start "{\"e164\":\"$number\"}")"
retry="$(header limited Retry-After)"
[[ "$retry" =~ ^[0-9]+$ && "$retry" -gt 0 && "$retry" -le 3600 ]] && pass "  Retry-After $retry s" || fail "  Retry-After '$retry'"

# Metrics only on the internal port. (Read into a file first: `curl | grep -q` fails under pipefail when grep
# exits early.)
expect "GET /metrics on the API port" 404 "$(request metrics_api GET /metrics)"
docker compose -f "$compose_file" exec -T server curl -fsS localhost:9090/metrics > "$work/metrics.txt" || true
if grep -q '^ktor_http_server_requests_seconds_count{.*route="/v1/auth/verify"' "$work/metrics.txt"; then
  pass "Prometheus metrics on the internal port"
else
  fail "no request metrics on the internal port"
fi
active="$(grep '^ktor_http_server_requests_active' "$work/metrics.txt" | awk '{print $2}')"
[[ -n "$active" && "$active" != -* ]] && pass "  active requests gauge $active" || fail "  active requests gauge '$active'"

if [[ "$failures" -gt 0 ]]; then
  echo "$failures smoke check(s) failed"
  exit 1
fi
echo "OK: API smoke test passed against $base"
