#!/usr/bin/env bash
# Smoke test of the whole API against the compose stack (docs/implementation_plan.md Phase 4), with curl + jq:
# health, sign-in, refresh rotation, rate limits, reports, scoring, bucket lookup, search, lists, disputes,
# unlisting (API and HTML form), sender IDs, OTP sources and metrics.
#
#   docker compose -f infra/docker/compose.yml up -d --build --wait
#   scripts/smoke.sh
#
# Needs: OTP_PROVIDER=log (the development default; codes are read from the server log) and ADMIN_TOKEN set in
# infra/docker/.env (jobs are run on demand through the admin endpoints on the internal port). Run it once per
# stack: the sign-in rate limits (3 codes per number per hour) would refuse a second run within the hour.
# Env: BASE_URL (default http://localhost:8080), COMPOSE_FILE (default infra/docker/compose.yml).
set -euo pipefail

base="${BASE_URL:-http://localhost:8080}"
compose_file="${COMPOSE_FILE:-infra/docker/compose.yml}"
# Valid North American numbers in the 555-01xx range reserved for fiction.
number="+12015550123"   # signs in, rotates its refresh token, hits the start limit
reporter="+12015550199" # signs in and uses the API
spammer="+12025550143"  # gets reported
unlisted="+12015550177" # is unlisted through the HTML form and the API
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

for tool in curl jq openssl sha256sum; do
  command -v "$tool" > /dev/null || {
    echo "FAIL $tool is required"
    exit 1
  }
done

pass() { echo "PASS $1"; }
fail() {
  echo "FAIL $1"
  failures=$((failures + 1))
}

# request <name> <method> <path> [json] [extra curl args…]: body in $work/<name>.body, headers in
# $work/<name>.headers; echoes the status code.
request() {
  local name="$1" method="$2" path="$3" body="${4:-}"
  shift 4 2> /dev/null || shift $#
  local args=(-sS -o "$work/$name.body" -D "$work/$name.headers" -w '%{http_code}' -X "$method" "$base$path" "$@")
  if [[ -n "$body" ]]; then args+=(-H 'Content-Type: application/json' --data "$body"); fi
  curl "${args[@]}"
}
# authed <name> <method> <path> [json]: request with the reporter's access token.
authed() { request "$1" "$2" "$3" "${4:-}" -H "Authorization: Bearer $access"; }

field() { jq -r "$2" "$work/$1.body"; }
header() { grep -i "^$2:" "$work/$1.headers" | head -1 | cut -d: -f2- | sed 's/^ *//' | tr -d '\r'; }
expect() {
  local what="$1" want="$2" got="$3"
  if [[ "$got" == "$want" ]]; then pass "$what ($got)"; else fail "$what: expected $want, got $got"; fi
}

compose() { docker compose -f "$compose_file" "$@"; }

# The newest development code logged for <number> (the log shows numbers as +****<last 4 digits>).
latest_code() {
  local last4="${1: -4}" code
  for _ in $(seq 1 20); do
    code="$(compose logs --no-color server 2> /dev/null |
      grep -o "Development OTP for +\*\*\*\*$last4: [0-9]\{6\}" | tail -1 | grep -o '[0-9]\{6\}$' || true)"
    if [[ -n "$code" ]]; then
      echo "$code"
      return 0
    fi
    sleep 0.5
  done
  return 1
}

# run_job <scoring|lists>: runs a job now through the admin endpoint (token from the server's own environment).
run_job() {
  compose exec -T server sh -c \
    "curl -sS -X POST -H \"Authorization: Bearer \$ADMIN_TOKEN\" localhost:9090/admin/jobs/$1" > "$work/job_$1.body"
}

# sign_in <number> <name>: start → code from the log → verify; the response lands in $work/<name>.body.
sign_in() {
  local status code
  status="$(request "$2_start" POST /v1/auth/start "{\"e164\":\"$1\"}")"
  [[ "$status" == 202 ]] || {
    fail "start for $1: HTTP $status"
    return 1
  }
  code="$(latest_code "$1")" || {
    fail "no development code for $1 in the server log (is OTP_PROVIDER=log?)"
    return 1
  }
  request "$2" POST /v1/auth/verify "{\"e164\":\"$1\",\"code\":\"$code\",\"flavor\":\"foss\"}"
}

# Bucket of a number: the first 16 bits of SHA-256(E.164), and the full hash in base64 (docs/architecture.md §4.6).
bucket_of() { echo $((16#$(printf '%s' "$1" | sha256sum | cut -c1-4))); }
hash_of() { printf '%s' "$1" | openssl dgst -sha256 -binary | base64; }

echo "== Health"
expect "GET /health" 200 "$(request health GET /health)"
[[ -n "$(header health X-Request-Id)" ]] && pass "X-Request-Id on every response" || fail "no X-Request-Id"

echo "== Sign-in (TC-API-001…004)"
expect "start with an invalid number" 400 "$(request bad POST /v1/auth/start '{"e164":"12345"}')"
expect "  problem+json" "application/problem+json" "$(header bad Content-Type | cut -d';' -f1)"
expect "  code" invalid_phone_number "$(field bad .code)"

expect "start + verify" 200 "$(sign_in "$number" verify)"
refresh="$(field verify .refreshToken)"
[[ "$(field verify .accessToken)" == *.*.* ]] && pass "access token is a JWT" || fail "access token is not a JWT"
expect "  access token lifetime" 900 "$(field verify .accessTokenExpiresIn)"
expect "  tokenType" Bearer "$(field verify .tokenType)"

expect "POST /v1/auth/refresh" 200 "$(request refresh POST /v1/auth/refresh "{\"refreshToken\":\"$refresh\"}")"
rotated="$(field refresh .refreshToken)"
[[ "$rotated" != "$refresh" ]] && pass "refresh token rotated" || fail "refresh token not rotated"
expect "reuse of the old refresh token" 401 "$(request reuse POST /v1/auth/refresh "{\"refreshToken\":\"$refresh\"}")"
expect "  code" refresh_token_reused "$(field reuse .code)"
request after POST /v1/auth/refresh "{\"refreshToken\":\"$rotated\"}" > /dev/null
expect "the rotated token after reuse (chain revoked)" invalid_refresh_token "$(field after .code)"

request start2 POST /v1/auth/start "{\"e164\":\"$number\"}" > /dev/null
request start3 POST /v1/auth/start "{\"e164\":\"$number\"}" > /dev/null
expect "4th start within an hour" 429 "$(request limited POST /v1/auth/start "{\"e164\":\"$number\"}")"
retry="$(header limited Retry-After)"
[[ "$retry" =~ ^[0-9]+$ && "$retry" -gt 0 && "$retry" -le 3600 ]] && pass "  Retry-After $retry s" || fail "  Retry-After '$retry'"

expect "sign in the reporter" 200 "$(sign_in "$reporter" reporter)"
access="$(field reporter .accessToken)"

echo "== Reports"
expect "report without a token" 401 "$(request noauth POST /v1/reports '{"reports":[{"e164":"+12025550143","label":"spam"}]}')"
expect "report with an invalid number (TC-API-004)" 400 "$(authed badreport POST /v1/reports '{"reports":[{"e164":"+12025550143","label":"spam"},{"e164":"2025550143","label":"spam"}]}')"
expect "  code" invalid_phone_number "$(field badreport .code)"
expect "POST /v1/reports" 202 "$(authed report POST /v1/reports "{\"reports\":[{\"e164\":\"$spammer\",\"label\":\"spam\",\"category\":\"telemarketing\",\"comment\":\"Smoke test\"}]}")"
expect "  accepted" 1 "$(field report .accepted)"

echo "== Scoring job (admin endpoint)"
run_job scoring
expect "scoring run: numbers rescored" 1 "$(jq -r .result.rescored "$work/job_scoring.body")"

echo "== Bucket lookup (TC-API-007)"
bucket="$(bucket_of "$spammer")"
hash="$(hash_of "$spammer")"
expect "GET /v1/lookup/buckets/1/$bucket" 200 "$(request bucket GET "/v1/lookup/buckets/1/$bucket")"
expect "  entry found by its full hash" 1 "$(jq --arg h "$hash" '[.entries[] | select(.hash == $h)] | length' "$work/bucket.body")"
expect "  report count" 1 "$(jq -r --arg h "$hash" '.entries[] | select(.hash == $h) | .info.reportCount' "$work/bucket.body")"
expect "  category" telemarketing "$(jq -r --arg h "$hash" '.entries[] | select(.hash == $h) | .info.category' "$work/bucket.body")"
expect "  Cache-Control" "public, max-age=86400" "$(header bucket Cache-Control)"
etag="$(header bucket ETag)"
expect "  If-None-Match → 304" 304 "$(request bucket304 GET "/v1/lookup/buckets/1/$bucket" "" -H "If-None-Match: $etag")"
expect "  with a token: private cache" "private, max-age=86400" "$(authed bucket_authed GET "/v1/lookup/buckets/1/$bucket" > /dev/null; header bucket_authed Cache-Control)"
expect "  invalid bucket" invalid_bucket "$(request badbucket GET /v1/lookup/buckets/1/70000 > /dev/null; field badbucket .code)"

echo "== Search (TC-API-009 quota not exhausted here; see SearchApiTest)"
expect "GET /v1/numbers/$spammer" 200 "$(authed search GET "/v1/numbers/%2B${spammer#+}")"
expect "  e164" "$spammer" "$(field search .e164)"
expect "  label (score 0.43 is below Suspicious)" null "$(field search .info.label)"
expect "  Cache-Control" no-store "$(header search Cache-Control)"
expect "search without a token" 401 "$(request search_noauth GET "/v1/numbers/%2B${spammer#+}")"

echo "== Spam lists"
run_job lists
expect "list build: US list built" 1 "$(jq '[.result.built[] | select(.countryIso == "US")] | length' "$work/job_lists.body")"
expect "GET /v1/lists/US/manifest" 200 "$(request manifest GET /v1/lists/US/manifest)"
url="$(field manifest .full.url)"
sha="$(field manifest .full.sha256)"
expect "GET $url" 200 "$(curl -sS -o "$work/list.ocsl" -w '%{http_code}' "$base$url")"
expect "  SHA-256 matches the manifest" "$sha" "$(sha256sum "$work/list.ocsl" | cut -d' ' -f1)"
expect "  magic" OCSL "$(head -c 4 "$work/list.ocsl")"
expect "unknown country" invalid_country "$(request badiso GET /v1/lists/XX/manifest > /dev/null; field badiso .code)"

echo "== Disputes"
expect "POST /v1/disputes" 201 "$(authed dispute POST /v1/disputes "{\"e164\":\"$spammer\",\"reason\":\"Smoke test dispute\"}")"
expect "  status" pending "$(field dispute .status)"

echo "== Unlist (TC-API-010)"
expect "GET /unlist (HTML)" 200 "$(request unlist_form GET /unlist)"
expect "  content type" text/html "$(header unlist_form Content-Type | cut -d';' -f1)"
expect "POST /unlist (form, number with spaces)" 200 "$(request unlist_post POST /unlist "" --data-urlencode "e164=+1 201 555 0177")"
grep -q 'action="/unlist/verify"' "$work/unlist_post.body" && pass "  code form shown" || fail "  no code form"
code="$(latest_code "$unlisted")" || fail "no unlist code in the server log"
expect "POST /v1/unlist/verify" 202 "$(request unlist_verify POST /v1/unlist/verify "{\"e164\":\"$unlisted\",\"code\":\"$code\"}")"
run_job scoring
expect "  processed by the next job run" 1 "$(jq -r .result.unlistProcessed "$work/job_scoring.body")"

echo "== Sender IDs and OTP sources"
expect "GET /v1/sender-ids/BD" 200 "$(request senders GET /v1/sender-ids/BD)"
expect "  example rows" 2 "$(field senders '.senderIds | length')"
since="$(field senders .syncedAt)"
expect "  nothing newer than syncedAt" 0 "$(request senders_since GET "/v1/sender-ids/BD?since=$since" > /dev/null; field senders_since '.senderIds | length')"
expect "GET /v1/otp-sources" 200 "$(request otp GET /v1/otp-sources)"
expect "  app hashes" 2 "$(field otp '.appHashes | length')"

echo "== Metrics (internal port only)"
expect "GET /metrics on the API port" 404 "$(request metrics_api GET /metrics)"
# Read into a file first: `curl | grep -q` fails under pipefail when grep exits early.
compose exec -T server curl -fsS localhost:9090/metrics > "$work/metrics.txt" || true
for series in 'ktor_http_server_requests_seconds_count{.*route="/v1/lookup/buckets/{cc}/{bucketId}"' \
  'opencaller_job_duration_seconds_count{job="scoring"' 'opencaller_job_last_success{job="lists"'; do
  grep -q "^$series" "$work/metrics.txt" && pass "  ${series%%\{*}" || fail "  missing ${series}"
done
active="$(grep '^ktor_http_server_requests_active' "$work/metrics.txt" | awk '{print $2}')"
[[ -n "$active" && "$active" != -* ]] && pass "  active requests gauge $active" || fail "  active requests gauge '$active'"

if [[ "$failures" -gt 0 ]]; then
  echo "$failures smoke check(s) failed"
  exit 1
fi
echo "OK: API smoke test passed against $base"
