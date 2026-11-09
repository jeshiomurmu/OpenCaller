#!/usr/bin/env bash
# Emulator run of TC-FAM-001/002 (docs/testing.md → Phase 12) on the two emulators of the e2e job, after
# scripts/e2e/voip.sh (both phones are signed in: A as +12015550190, B as +12015550191). The steps go through the
# debug build's QA receiver (QaFamily), which calls the same repositories and workers as the screens.
#
#   TC-FAM-001: B creates a group and makes an invite code; A sees what joining means; a join without A's own
#               consent tick sends nothing; with it, A is a member, and the server holds the consent time, the
#               wording version and A's two switches. The code works once.
#   TC-FAM-002: the OTP guard's warning on A (the reporter it calls) reaches B: the server pushes B's endpoint
#               (Web Push ciphertext, urgent) within 10 s, B fetches the item and shows "Family alert: Mum" with no
#               number. The admin's own warnings go nowhere. A's daily counts arrive; leaving deletes what A shared.
#
# B's push endpoint is a sink on the host (scripts/e2e/push_sink.py), as in voip.sh: emulators have no distributor,
# so B fetches the item the way a phone that missed the push does.
#
# Usage: scripts/e2e/family.sh <applicationId> <serial-A> <serial-B> <out-dir>
set -euo pipefail

app_id="${1:?usage: family.sh <applicationId> <serial-A> <serial-B> <out-dir>}"
device_a="${2:?missing serial A}"
device_b="${3:?missing serial B}"
out="${4:?missing out dir}"
compose_file="${COMPOSE_FILE:-infra/docker/compose.yml}"
receiver="$app_id/io.github.jeshiomurmu.opencaller.qa.QaCommandReceiver"
member="+12015550190"
sink_port=8098
sink_log="$out/family-push-sink.jsonl"
failures=0
mkdir -p "$out"
: > "$sink_log"

pass() { echo "PASS $1: $2"; }
fail() {
  echo "::error::FAIL $1: $2"
  failures=$((failures + 1))
}
die() {
  echo "::error::$1" >&2
  diagnostics
  exit 1
}
compose() { docker compose -f "$compose_file" "$@"; }
sql() {
  compose exec -T postgres sh -c "psql -tA -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -c \"$1\"" | tr -d '\r' | sed '/^$/d'
}
qa() {
  local device="$1" args="" arg
  shift
  for arg in "$@"; do args+=" '${arg//\'/\'\\\'\'}'"; done
  adb -s "$device" shell "am broadcast -n $receiver --es cmd$args" | tr -d '\r' | sed -n 's/.*data="\(.*\)".*/\1/p'
}
qa_ok() {
  local answer
  answer="$(qa "$@")"
  [[ "$answer" == ok* ]] || die "QA command '$*' answered '$answer'"
  echo "$answer"
}
now_ms() { date +%s%3N; }
pushes() { grep -c . "$sink_log" || true; }
member_rows() { sql "SELECT count(*) FROM family_members m JOIN users u ON u.id = m.user_id WHERE u.e164 = '$member'"; }
diagnostics() {
  for device in "$device_a" "$device_b"; do
    adb -s "$device" logcat -d -v threadtime > "$out/family-logcat-$device.txt" 2> /dev/null || true
    echo "--- $device: the app's lines"
    grep -E ' OpenCaller: |FATAL EXCEPTION| E AndroidRuntime|WM-' "$out/family-logcat-$device.txt" | tail -n 40 || true
  done
  echo "--- pushes received by the sink"
  cat "$sink_log" || true
}

# --- Set-up: B's push endpoint is the sink; nobody is in a group -----------------------------------------------
server_id="$(compose ps -q server)"
gateway="$(docker inspect "$server_id" -f '{{range .NetworkSettings.Networks}}{{.Gateway}} {{end}}' | awk '{print $1}')"
[[ -n "$gateway" ]] || die "no gateway address for the server's network"
python3 scripts/e2e/push_sink.py "$sink_port" "$sink_log" &
sink_pid=$!
trap 'kill "$sink_pid" 2> /dev/null || true' EXIT
for _ in $(seq 1 20); do
  if compose exec -T server sh -c "curl -s -o /dev/null -X POST http://$gateway:$sink_port/probe"; then break; fi
  sleep 0.5
done
: > "$sink_log"
for device in "$device_a" "$device_b"; do
  qa "$device" family-leave > /dev/null
  adb -s "$device" logcat -c
done
qa_ok "$device_b" push-endpoint --es url "http://$gateway:$sink_port/push/admin" > /dev/null

# --- TC-FAM-001 ------------------------------------------------------------------------------------------------
qa_ok "$device_b" family-create --es name "Rahman family" --es label Rafi > /dev/null
code="$(qa_ok "$device_b" family-invite | sed -n 's/^ok code=//p')"
[[ "$code" =~ ^[A-HJ-NP-Z2-9]{8}$ ]] || die "invite code looks wrong: $code"
preview="$(qa_ok "$device_a" family-preview --es code "$code")"
[[ "$preview" == "ok group=Rahman family admin=Rafi members=1" ]] || fail TC-FAM-001 "the consent screen's facts: $preview"
refused="$(qa "$device_a" family-join --es code "$code" --es label Mum --ez consent false --ez alerts true --ez counts true)"
if [[ "$refused" == *consent_required* && "$(member_rows)" == 0 ]]; then
  pass TC-FAM-001 "without the member's own consent tick nothing is sent and A is not in the group"
else
  fail TC-FAM-001 "a join without consent answered '$refused' (member rows: $(member_rows))"
fi
joined="$(qa "$device_a" family-join --es code "$code" --es label Mum --ez consent true --ez alerts true --ez counts true)"
row="$(sql "SELECT m.role, m.label, m.consent_version, m.consented_at IS NOT NULL, m.share_otp_alerts, m.share_daily_counts
  FROM family_members m JOIN users u ON u.id = m.user_id WHERE u.e164 = '$member'")"
if [[ "$joined" == "ok role=MEMBER" && "$row" == "member|Mum|1|t|t|t" ]]; then
  pass TC-FAM-001 "A joined with consent: version 1, consent time recorded, both switches A chose"
else
  fail TC-FAM-001 "join answered '$joined', server row '$row'"
fi
used="$(sql "SELECT count(*) FROM family_invites WHERE used_at IS NOT NULL")"
again="$(qa "$device_a" family-preview --es code "$code")"
if [[ "$used" -ge 1 && "$again" == error* ]]; then
  pass TC-FAM-001 "the invite code worked once (a second look answers '$again')"
else
  fail TC-FAM-001 "the code is still open after use (used rows $used, preview '$again')"
fi

# --- TC-FAM-002 ------------------------------------------------------------------------------------------------
before="$(pushes)"
qa_ok "$device_b" family-warn --es kind otp_during_call > /dev/null # the admin shares nothing
t0="$(now_ms)"
qa_ok "$device_a" family-warn --es kind otp_during_call > /dev/null
pushed=""
for _ in $(seq 1 60); do
  if [[ "$(pushes)" -gt "$before" ]]; then
    pushed="$(now_ms)"
    break
  fi
  sleep 0.25
done
if [[ -z "$pushed" ]]; then
  fail TC-FAM-002 "no push reached the admin's endpoint within 15 s"
else
  latency=$((pushed - t0))
  push="$(sed -n "$((before + 1))p" "$sink_log")"
  summary="$(python3 -c '
import json, sys
p = json.loads(sys.argv[1]); h = p["headers"]
print(h.get("Content-Encoding"), h.get("TTL"), h.get("Urgency"), p["plain"] is None)
' "$push")"
  if [[ "$summary" == "aes128gcm 3600 high True" && "$latency" -le 10000 ]]; then
    pass TC-FAM-002 "A's warning reached the admin's push endpoint in $latency ms (ciphertext, TTL 1 h, urgent)"
  else
    fail TC-FAM-002 "push after $latency ms looked wrong: $summary"
  fi
  sleep 2
  [[ "$(pushes)" -eq $((before + 1)) ]] || fail TC-FAM-002 "more than one push: the admin's own warning went out too"
  qa_ok "$device_b" catch-up > /dev/null
  shown=""
  for _ in $(seq 1 20); do
    shown="$(adb -s "$device_b" shell dumpsys notification --noredact | tr -d '\r' | grep -E 'android.title=.*Family alert: Mum' || true)"
    [[ -n "$shown" ]] && break
    sleep 0.5
  done
  stored="$(sql "SELECT kind FROM family_alerts")"
  if [[ -n "$shown" && "$stored" == otp_during_call ]]; then
    pass TC-FAM-002 "B shows 'Family alert: Mum' $(($(now_ms) - t0)) ms after A's warning; the server keeps the kind only"
  else
    fail TC-FAM-002 "B's notification: '$shown'; stored alerts: '$stored'"
  fi
fi
status="$(qa_ok "$device_b" family-status)"
[[ "$status" == "ok role=OWNER alerts=false counts=false members=2 received=1" ]] || fail TC-FAM-002 "admin's view: $status"
counts="$(qa_ok "$device_a" family-counts)"
day_rows="$(sql "SELECT count(*) FROM family_daily_counts")"
if [[ "$counts" == "ok DONE" && "$day_rows" == 1 ]]; then
  pass TC-FAM-002 "A's daily counts (two numbers) reached the server"
else
  fail TC-FAM-002 "daily counts answered '$counts' (rows: $day_rows)"
fi
qa_ok "$device_a" family-leave > /dev/null
left="$(sql "SELECT (SELECT count(*) FROM family_alerts) + (SELECT count(*) FROM family_daily_counts)")"
if [[ "$(member_rows)" == 0 && "$left" == 0 ]]; then
  pass TC-FAM-002 "A left; the server deleted A's alerts and counts"
else
  fail TC-FAM-002 "after leaving: member rows $(member_rows), alerts + counts $left"
fi
qa_ok "$device_b" family-leave > /dev/null

if [[ "$failures" -gt 0 ]]; then
  diagnostics
  echo "TC-FAM (emulators): $failures check(s) failed"
  exit 1
fi
echo "TC-FAM (emulators): all checks passed"
