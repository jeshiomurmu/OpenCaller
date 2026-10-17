#!/usr/bin/env bash
# TC-E2E-001 (docs/testing.md): device A reports a number, the server's jobs run, device B updates its spam list and
# blocks the next call from that number. Two emulators and the local compose stack; the app is a debug build, which
# talks to the host at 10.0.2.2:8080 and has the debug-only QA receiver (QaOnline) for sign-in, reports and sync.
#
#   1. B: a call from the spammer rings (nothing known yet).
#   2. A: signs in (code from the development server's log), reports the spammer as spam (category loan), uploads.
#   3. Server: scoring job, then list job (admin endpoint on the internal port).
#   4. B: threshold "Block more" (0.4; one report from a new reporter scores 0.43), updates the spam list: the
#      spammer is on it; the next call is rejected by the spam list.
#   5. A: reports a second number; scoring runs but no new list is built. B: the call is rejected by the private
#      live lookup (only a short code of the number's hash leaves the phone; TC-LOOK-002 checks that in unit tests).
#
# Usage: scripts/e2e/report_to_block.sh <debug-apk> <applicationId> [serial-A] [serial-B]
# Needs: the compose stack up with OTP_PROVIDER=log and ADMIN_TOKEN set (like scripts/smoke.sh), both emulators booted.
# Env: COMPOSE_FILE (default infra/docker/compose.yml).
set -euo pipefail

apk="${1:?usage: report_to_block.sh <debug-apk> <applicationId> [serial-A] [serial-B]}"
app_id="${2:?missing applicationId}"
device_a="${3:-emulator-5554}"
device_b="${4:-emulator-5556}"
compose_file="${COMPOSE_FILE:-infra/docker/compose.yml}"
receiver="$app_id/io.github.jeshiomurmu.opencaller.qa.QaCommandReceiver"
role="android.app.role.CALL_SCREENING"
# Valid North American numbers in the 555-01xx range reserved for fiction (different from scripts/smoke.sh).
reporter="+12015550188"
spammer="+12025550166"
second_spammer="+12025550167"
failures=0

pass() { echo "PASS TC-E2E-001: $1"; }
fail() {
  echo "::error::FAIL TC-E2E-001: $1"
  failures=$((failures + 1))
}
die() {
  echo "::error::$1" >&2
  exit 1
}

compose() { docker compose -f "$compose_file" "$@"; }

# qa <device> <command> [--es key value ...]: one QA receiver command; echoes its answer ("ok …" or "error …").
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

# The newest development code logged for <number> (the server log shows numbers as +****<last 4 digits>).
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

# reports_on_server <number>: how many reports the server has stored for <number> (straight from PostgreSQL).
reports_on_server() {
  compose exec -T postgres sh -c \
    "psql -tA -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -c \"select count(*) from reports where e164 = '$1'\"" | tr -d '[:space:]'
}

# upload_report <number>: sends A's queued reports and checks that the server stored the one for <number>. Queuing a
# report also starts the app's own upload worker, which may send it before this explicit upload does, so the check is
# an empty outbox plus the report on the server, not the upload's count.
upload_report() {
  local number="$1" uploaded pending=""
  uploaded="$(qa_ok "$device_a" upload)"
  for _ in $(seq 1 30); do
    pending="$(qa_ok "$device_a" pending)"
    if [[ "$pending" == "ok pending=0" ]]; then break; fi
    sleep 1
  done
  local stored
  stored="$(reports_on_server "$number")"
  if [[ "$pending" == "ok pending=0" && "$stored" -ge 1 ]]; then
    pass "A's report for $number reached the server ($uploaded, $stored stored)"
  else
    fail "A's report for $number: upload '$uploaded', outbox '$pending', stored on the server: '${stored:-?}'"
  fi
}

# run_job <scoring|lists>: runs a server job now through the admin endpoint.
run_job() {
  compose exec -T server sh -c \
    "curl -fsS -X POST -H \"Authorization: Bearer \$ADMIN_TOKEN\" localhost:9090/admin/jobs/$1" || die "job $1 failed"
  echo
}

events() { adb -s "$device_b" shell run-as "$app_id" cat files/qa/screening_events.tsv 2> /dev/null | tr -d '\r' || true; }
count() { events | grep -c . || true; }
field() { cut -f "$2" <<< "$1"; }

next_event() {
  local before="$1"
  for _ in $(seq 1 40); do
    if [[ "$(count)" -gt "$before" ]]; then
      events | sed -n "$((before + 1))p"
      return 0
    fi
    sleep 0.5
  done
  return 1
}

call_state() { adb -s "$device_b" shell dumpsys telephony.registry | tr -d '\r' | grep -oE 'mCallState=[0-9]' | head -1 | cut -d= -f2; }

hang_up() {
  adb -s "$device_b" emu gsm cancel "$1" > /dev/null || true
  for _ in $(seq 1 20); do
    if [[ "$(call_state)" == 0 ]]; then return 0; fi
    sleep 0.5
  done
  adb -s "$device_b" shell input keyevent KEYCODE_ENDCALL || true
}

# ring <what> <number> <decision> <reason>: an incoming call on B; checks the logged decision, then hangs up.
ring() {
  local what="$1" number="$2" want_decision="$3" want_reason="$4" before event
  before="$(count)"
  adb -s "$device_b" emu gsm call "$number" > /dev/null
  if ! event="$(next_event "$before")"; then
    hang_up "$number"
    fail "$what: no screening decision logged for $number"
    return
  fi
  sleep 2
  hang_up "$number"
  if [[ "$(field "$event" 4)" == "$want_decision" && "$(field "$event" 5)" == "$want_reason" ]]; then
    pass "$what: $number → $want_decision ($want_reason), latency $(field "$event" 7) ms, score $(field "$event" 8)"
  else
    fail "$what: $number → $(field "$event" 4) ($(field "$event" 5)); expected $want_decision ($want_reason)"
  fi
}

install() {
  local device="$1" sdk
  adb -s "$device" wait-for-device
  sdk="$(adb -s "$device" shell getprop ro.build.version.sdk | tr -d '\r')"
  adb -s "$device" install -r "$apk" > /dev/null
  adb -s "$device" shell pm grant "$app_id" android.permission.READ_CONTACTS
  adb -s "$device" shell pm grant "$app_id" android.permission.READ_PHONE_STATE
  if [[ "$sdk" -ge 33 ]]; then adb -s "$device" shell pm grant "$app_id" android.permission.POST_NOTIFICATIONS; fi
  qa_ok "$device" reset > /dev/null
}

grant_screening_role() {
  local held=""
  for attempt in 1 2; do
    adb -s "$device_b" shell cmd role add-role-holder "$role" "$app_id" 0 || echo "add-role-holder attempt $attempt did not confirm"
    for _ in $(seq 1 30); do
      held="$(qa "$device_b" role)"
      if [[ "$held" == "ok held=true" ]]; then return 0; fi
      sleep 1
    done
  done
  die "call screening role not granted on B ($held)"
}

# --- Setup -------------------------------------------------------------------------------------------------------
curl -fsS localhost:8080/health > /dev/null || die "the server isn't up at localhost:8080"
install "$device_a"
install "$device_b"
grant_screening_role
qa_ok "$device_b" reset > /dev/null
qa_ok "$device_b" region --es region US > /dev/null
qa_ok "$device_b" settings --es threshold LOW --ez topSpammers true > /dev/null
# Keep B's process alive like a phone in everyday use.
adb -s "$device_b" shell am start -W -n "$app_id/io.github.jeshiomurmu.opencaller.MainActivity" > /dev/null
adb -s "$device_b" shell input keyevent KEYCODE_HOME
echo "A = $device_a, B = $device_b; B holds the call screening role"

# --- 1. Nothing known yet ----------------------------------------------------------------------------------------
ring "before any report" "$spammer" ALLOW no_match

# --- 2. A signs in and reports -----------------------------------------------------------------------------------
qa_ok "$device_a" signin-start --es number "$reporter"
code="$(latest_code "$reporter")" || die "no development code for $reporter in the server log"
qa_ok "$device_a" signin-verify --es number "$reporter" --es code "$code"
qa_ok "$device_a" report --es number "$spammer" --es category loan
upload_report "$spammer"

# --- 3. Server jobs ------------------------------------------------------------------------------------------------
run_job scoring
run_job lists

# --- 4. B updates its list and blocks ------------------------------------------------------------------------------
synced="$(qa_ok "$device_b" sync)"
echo "B: $synced"
listed="$(qa_ok "$device_b" listed --es number "$spammer")"
[[ "$listed" == "ok score="* ]] && pass "B's spam list has the reported number ($listed)" || fail "B's spam list: $listed"
ring "after the list update" "$spammer" BLOCK spam_list

# --- 5. Live lookup before the next list -----------------------------------------------------------------------
qa_ok "$device_a" report --es number "$second_spammer" --es category scam
upload_report "$second_spammer"
run_job scoring
listed="$(qa_ok "$device_b" listed --es number "$second_spammer")"
[[ "$listed" == "ok none" ]] || fail "the second number should not be on B's list yet: $listed"
ring "by live lookup" "$second_spammer" BLOCK network

if [[ "$failures" -gt 0 ]]; then
  echo "TC-E2E-001: $failures check(s) failed"
  adb -s "$device_b" logcat -d | grep -iE 'opencaller|Screening|AndroidRuntime' | tail -n 200 || true
  exit 1
fi
echo "TC-E2E-001: all checks passed"
