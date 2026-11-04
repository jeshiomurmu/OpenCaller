#!/usr/bin/env bash
# Smoke test of the whole API against the compose stack (docs/implementation_plan.md Phase 4), with curl + jq:
# health, sign-in, refresh rotation, rate limits, reports, scoring, bucket lookup, search, lists, disputes,
# unlisting (API and HTML form), sender IDs, OTP sources, profiles (Phase 8), business claims with the admin web
# (Phase 9), app calls against the real LiveKit, Flash messages and video caller ID through the transcoder (Phase 11)
# and metrics.
#
#   docker compose -f infra/docker/compose.yml up -d --build --wait
#   scripts/smoke.sh
#
# Needs: OTP_PROVIDER=log (the development default; codes are read from the server log), ADMIN_TOKEN (jobs are run
# on demand through the admin endpoints on the internal port) and the ADMIN_WEB_* settings of .env.example (claims
# are approved through the admin web from inside the server container) and a VAPID key pair (scripts/vapid-keys.sh)
# set in infra/docker/.env. Run it once per
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
profile="+12015550188"  # edits, hides and deletes its profile
business="+12015550166" # is claimed as a business and approved
callee="+12015550155"   # gets an app call, a Flash message, and publishes a video caller ID
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
# as_profile <name> <method> <path> [json]: request with the profile user's access token.
as_profile() { request "$1" "$2" "$3" "${4:-}" -H "Authorization: Bearer $profile_access"; }

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

echo "== Supporter tier (F-UT-08; compose runs without Play billing and not self-hosted)"
expect "GET /v1/server" 200 "$(request server_info GET /v1/server)"
expect "  not self-hosted" false "$(field server_info .selfHosted)"
expect "  no Play product without billing" null "$(field server_info .billing.googlePlayProductId)"
expect "GET /v1/entitlements" 200 "$(authed entitlements GET /v1/entitlements)"
expect "  not entitled" false "$(field entitlements .entitled)"
expect "  tier" none "$(field entitlements .tier)"
expect "POST /v1/billing/verify without billing" 503 "$(authed verify_purchase POST /v1/billing/verify '{"productId":"opencaller_supporter","purchaseToken":"smoke-token"}')"
expect "  code" billing_not_configured "$(field verify_purchase .code)"
expect "extended list file without a token" 401 "$(request extended_anon GET /v1/lists/US/files/1/extended)"
expect "extended list file, not a supporter" 403 "$(authed extended_user GET /v1/lists/US/files/1/extended)"
expect "  code" supporter_required "$(field extended_user .code)"

echo "== Profiles (TC-SOC-001, TC-SOC-003)"
expect "sign in the profile user" 200 "$(sign_in "$profile" profile_user)"
profile_access="$(field profile_user .accessToken)"
expect "PUT /v1/me" 200 "$(as_profile me_put PUT /v1/me '{"displayName":"Smoke Profile","visibility":"everyone","incognito":false}')"
expect "  visibility" everyone "$(field me_put .visibility)"
expect "  Cache-Control" no-store "$(header me_put Cache-Control)"
profile_bucket="$(bucket_of "$profile")"
profile_hash="$(hash_of "$profile")"
name_in_bucket() {
  request "$1" GET "/v1/lookup/buckets/1/$profile_bucket" > /dev/null
  jq -r --arg h "$profile_hash" '[.entries[] | select(.hash == $h) | .info.name][0]' "$work/$1.body"
}
expect "  name in the anonymous bucket" "Smoke Profile" "$(name_in_bucket bucket_everyone)"
as_profile me_hide PUT /v1/me '{"displayName":"Smoke Profile","visibility":"nobody","incognito":false}' > /dev/null
expect "  hidden at once (TC-SOC-001)" null "$(name_in_bucket bucket_nobody)"
expect "POST /v1/me/avatar" 200 "$(as_profile avatar POST /v1/me/avatar '{}')"
printf '\xff\xd8\xff\xe0smoke-test-jpeg\xff\xd9' > "$work/avatar.jpg"
upload_status="$(curl -sS -o /dev/null -w '%{http_code}' -X PUT -H 'Content-Type: image/jpeg' --data-binary "@$work/avatar.jpg" "$(field avatar .uploadUrl)")"
expect "  upload to the presigned URL (no token)" 200 "$upload_status"
avatar_key="$(field avatar .avatarKey)"
expect "  set avatarKey" 200 "$(as_profile me_avatar PUT /v1/me "{\"displayName\":\"Smoke Profile\",\"visibility\":\"nobody\",\"incognito\":false,\"avatarKey\":\"$avatar_key\"}")"
expect "  picture readable from avatarUrl" "$(sha256sum < "$work/avatar.jpg")" "$(curl -sS "$(field me_avatar .avatarUrl)" | sha256sum)"
expect "GET /v1/me/viewers (not a supporter)" 403 "$(as_profile viewers GET /v1/me/viewers)"
expect "  code" supporter_required "$(field viewers .code)"
expect "POST /v1/contact-requests" 202 "$(authed contact POST /v1/contact-requests "{\"e164\":\"$profile\"}")"
expect "  same answer for everyone" sent "$(field contact .status)"
expect "GET /v1/contact-requests" 1 "$(as_profile incoming GET /v1/contact-requests > /dev/null; field incoming '.items | length')"
expect "DELETE /v1/me" 204 "$(as_profile me_delete DELETE /v1/me)"
expect "  GET /v1/me afterwards (TC-SOC-003)" 401 "$(as_profile me_gone GET /v1/me)"
run_job retention
expect "  purged by the retention job" 1 "$(jq -r .result.accountsPurged "$work/job_retention.body")"

echo "== Business claims (TC-BIZ-001, TC-BIZ-002)"
# admin_web <name> <path> [curl args…]: the admin web from inside the server container (its port, user and password
# come from the container's environment); the status code is the first line of $work/<name>.admin, the page follows.
admin_web() {
  local name="$1" path="$2"
  shift 2
  compose exec -T server sh -c \
    'p="$1"; shift; curl -sS -u "$ADMIN_WEB_USER:$ADMIN_WEB_PASSWORD" -o /tmp/admin.body -w "%{http_code}\n" "$@" "http://127.0.0.1:$ADMIN_WEB_PORT$p"; cat /tmp/admin.body' \
    sh "$path" "$@" > "$work/$name.admin" || true
}
claim_json="{\"e164\":\"$business\",\"legalName\":\"Smoke Bank LLC\",\"displayName\":\"Smoke Bank\",\"category\":\"bank\",\"callReason\":\"Card security\"}"
expect "POST /v1/business/claims" 201 "$(authed claim POST /v1/business/claims "$claim_json")"
claim_id="$(field claim .id)"
expect "  status" verifying "$(field claim .status)"
expect "  upload before the code (TC-BIZ-001)" 403 "$(authed claim_early POST "/v1/business/claims/$claim_id/uploads" '{"kind":"document","contentType":"application/pdf"}')"
expect "  code" number_not_verified "$(field claim_early .code)"
claim_code="$(latest_code "$business")" || fail "no development code for $business in the server log"
expect "  code sent to the business number" 200 "$(authed claim_code POST "/v1/business/claims/$claim_id/code" "{\"code\":\"$claim_code\"}")"
expect "  status" verified "$(field claim_code .status)"
expect "  document upload URL" 200 "$(authed claim_doc POST "/v1/business/claims/$claim_id/uploads" '{"kind":"document","contentType":"application/pdf"}')"
printf '%%PDF-1.4\n%% smoke test document\n' > "$work/claim.pdf"
expect "  upload the document (no token)" 200 "$(curl -sS -o /dev/null -w '%{http_code}' -X PUT -H 'Content-Type: application/pdf' --data-binary "@$work/claim.pdf" "$(field claim_doc .uploadUrl)")"
expect "  submit" 200 "$(authed claim_submit POST "/v1/business/claims/$claim_id/submit" "{\"documentKeys\":[\"$(field claim_doc .key)\"]}")"
expect "  status" submitted "$(field claim_submit .status)"
admin_web admin_noauth / -u nobody:wrong
expect "admin web: wrong password" 401 "$(head -1 "$work/admin_noauth.admin")"
admin_web admin_claim "/claims/$claim_id"
expect "admin web: the claim's page" 200 "$(head -1 "$work/admin_claim.admin")"
csrf="$(grep -o 'name="csrf" value="[^"]*"' "$work/admin_claim.admin" | head -1 | sed 's/.*value="//; s/"$//')"
admin_web admin_forged "/claims/$claim_id/approve" -X POST --data-urlencode "csrf=forged"
expect "  approve without the page's token" 403 "$(head -1 "$work/admin_forged.admin")"
admin_web admin_approve "/claims/$claim_id/approve" -X POST --data-urlencode "csrf=$csrf"
expect "  approve (redirect)" 302 "$(head -1 "$work/admin_approve.admin")"
expect "GET /v1/businesses/US" "Smoke Bank" "$(request directory GET /v1/businesses/US > /dev/null; jq -r --arg n "$business" '[.entries[] | select(.e164 == $n) | .name][0]' "$work/directory.body")"
business_id="$(jq -r --arg n "$business" '[.entries[] | select(.e164 == $n) | .businessId][0]' "$work/directory.body")"
expect "GET /v1/business/{id}" 200 "$(request business_profile GET "/v1/business/$business_id")"
expect "  call reason" "Card security" "$(field business_profile '.numbers[0].callReason')"
business_bucket="$(bucket_of "$business")"
business_hash="$(hash_of "$business")"
request business_bucket GET "/v1/lookup/buckets/1/$business_bucket" > /dev/null
expect "  verified in the bucket (TC-BIZ-002)" "Smoke Bank" "$(jq -r --arg h "$business_hash" '[.entries[] | select(.hash == $h) | .info.business.name][0]' "$work/business_bucket.body")"
expect "GET /v1/business/claims" approved "$(authed claims_after GET /v1/business/claims > /dev/null; field claims_after '.claims[0].status')"

echo "== App calls, Flash messages, video caller ID (TC-VOIP-001 server side; ADR 0007)"
# The reporter calls and pings the callee. The callee registers a Web Push endpoint nobody listens on (development
# allows local addresses), so pushes fail and the callee finds its items through GET /v1/notifications instead.
expect "  features: app calls" true "$(field server_info .features.appCalls)"
expect "  features: Flash" true "$(field server_info .features.flash)"
expect "  VAPID key for UnifiedPush" 87 "$(field server_info '.features.webPushVapidKey | length')"
expect "sign in the callee" 200 "$(sign_in "$callee" callee_user)"
callee_access="$(field callee_user .accessToken)"
as_callee() { request "$1" "$2" "$3" "${4:-}" -H "Authorization: Bearer $callee_access"; }
b64url() { base64 -w0 | tr '+/' '-_' | tr -d '='; }
p256dh="$(scripts/vapid-keys.sh | sed -n 's/^VAPID_PUBLIC_KEY=//p')"
push_auth="$(openssl rand 16 | b64url)"
expect "PUT /v1/push (webpush)" 204 "$(as_callee push PUT /v1/push "{\"type\":\"webpush\",\"endpoint\":\"http://127.0.0.1:9/up/smoke\",\"p256dh\":\"$p256dh\",\"auth\":\"$push_auth\"}")"
expect "POST /v1/voip/calls" 200 "$(authed call POST /v1/voip/calls "{\"to\":\"$callee\"}")"
invite="$(field call .inviteId)"
expect "  push not delivered (no distributor)" 0 "$(field call .reachedDevices)"
# LiveKit itself checks the token the server signed (its key, the room grant, the expiry).
livekit_accepts() { curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:7880/rtc/validate?access_token=$1"; }
expect "  LiveKit accepts the caller's token" 200 "$(livekit_accepts "$(field call .token)")"
expect "  LiveKit refuses a forged token" 401 "$(livekit_accepts "$(field call .token | sed 's/.$//')x")"
expect "GET /v1/notifications (callee)" call_invite "$(as_callee pending GET /v1/notifications > /dev/null; field pending '.[0].kind')"
expect "  the invite" "$invite" "$(field pending '.[0].call.inviteId')"
expect "  from the caller" "$reporter" "$(field pending '.[0].call.number')"
expect "POST /v1/voip/token (callee)" 200 "$(as_callee room_token POST /v1/voip/token "{\"inviteId\":\"$invite\"}")"
expect "  same room" "$(field call .room)" "$(field room_token .room)"
expect "  LiveKit accepts the callee's token" 200 "$(livekit_accepts "$(field room_token .token)")"
expect "  accept" accepted "$(as_callee accept POST "/v1/voip/calls/$invite/accept" > /dev/null; field accept .state)"
expect "  hang up" ended "$(authed hangup POST "/v1/voip/calls/$invite/end" > /dev/null; field hangup .state)"
expect "  no token after the call" 409 "$(as_callee late_token POST /v1/voip/token "{\"inviteId\":\"$invite\"}")"
expect "POST /v1/voip/calls to a number without OpenCaller" 404 "$(authed call_nobody POST /v1/voip/calls '{"to":"+12015550111"}')"
expect "  code" not_reachable "$(field call_nobody .code)"
expect "POST /v1/flash" 200 "$(authed flash POST /v1/flash "{\"to\":\"$callee\",\"kind\":\"running_late\"}")"
expect "  free text refused" 400 "$(authed flash_text POST /v1/flash "{\"to\":\"$callee\",\"kind\":\"see you at 5\"}")"
as_callee pending_flash GET /v1/notifications > /dev/null
expect "  the callee's phone fetches it" running_late "$(jq -r '[.[] | select(.kind == "flash") | .flash.kind][0]' "$work/pending_flash.body")"
expect "  then it is gone from the server" 0 "$(as_callee pending_after GET /v1/notifications > /dev/null; jq '[.[] | select(.kind == "flash")] | length' "$work/pending_after.body")"

# Video caller ID: a 12 s 720p test clip with sound, made by the transcoder container's own ffmpeg.
expect "POST /v1/me/video/upload" 200 "$(as_callee video_upload POST /v1/me/video/upload)"
compose exec -T transcoder sh -c 'ffmpeg -loglevel error -y -f lavfi -i testsrc=duration=12:size=1280x720:rate=30 \
  -f lavfi -i sine=duration=12 -c:v libx264 -preset ultrafast -c:a aac -shortest /tmp/smoke.mp4 && cat /tmp/smoke.mp4 && rm /tmp/smoke.mp4' \
  > "$work/clip.mp4"
expect "  upload to the presigned URL (no token)" 200 "$(curl -sS -o /dev/null -w '%{http_code}' -X PUT -H 'Content-Type: video/mp4' --data-binary "@$work/clip.mp4" "$(field video_upload .uploadUrl)")"
expect "  submit" queued "$(as_callee video_submit POST /v1/me/video "{\"key\":\"$(field video_upload .key)\"}" > /dev/null; field video_submit .job)"
for _ in $(seq 1 60); do
  as_callee my_video GET /v1/me/video > /dev/null
  [[ "$(field my_video .job)" == none || "$(field my_video .job)" == failed ]] && break
  sleep 1
done
expect "  transcoded by the worker" none "$(field my_video .job)"
video_id="$(field my_video .clip.videoId)"
callee_hash="$(hash_of "$callee")"
as_callee callee_bucket GET "/v1/lookup/buckets/1/$(bucket_of "$callee")" > /dev/null
expect "  clip id in the bucket entry" "$video_id" "$(jq -r --arg h "$callee_hash" '[.entries[] | select(.hash == $h) | .info.videoId][0]' "$work/callee_bucket.body")"
expect "POST /v1/videos/links (no token)" 1 "$(request video_links POST /v1/videos/links "{\"ids\":[\"$video_id\"]}" > /dev/null; field video_links '.links | length')"
curl -sS -o "$work/published.mp4" "$(field video_links '.links[0].url')"
# ISO BMFF: bytes 4–7 of the first box are "ftyp".
expect "  the published clip is an MP4" ftyp "$(head -c 8 "$work/published.mp4" | tail -c 4)"
[[ "$(stat -c %s "$work/published.mp4")" -lt "$(stat -c %s "$work/clip.mp4")" ]] && pass "  smaller than the upload" || fail "  not smaller than the upload"

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
