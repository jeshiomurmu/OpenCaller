#!/usr/bin/env bash
# Emulator QA for call screening (docs/testing.md, TC-BLK-*). Installs a debug APK, gives it the call screening role,
# sets rules and settings through the debug-only QA receiver, places calls with `adb emu gsm call` and reads every
# decision from the debug export (files/qa/*.tsv, via run-as). Prints PASS/FAIL per case and the decision latency
# (p50/p95, from the export and from the debug screen). Exits non-zero if any case fails.
#
#   TC-BLK-002  exact block            TC-BLK-003  prefix block (+1555123*)
#   TC-BLK-004  slow regex rejected     TC-BLK-006  foreign numbers (SIM BD), US allowed
#   TC-BLK-008  non-contacts, verified business rings
#   TC-BLK-009  spam list 0.9 rejected / 0.7 rings as Suspicious (threshold high)
#   TC-BLK-012  outgoing call to a blocked number proceeds
#   TC-BLK-015  Unblock in the blocked-calls log, next call rings
#
# Usage: scripts/qa/blocking.sh <debug-apk> <applicationId> <out-dir>
set -euo pipefail

apk="${1:?usage: blocking.sh <debug-apk> <applicationId> <out-dir>}"
app_id="${2:?missing applicationId}"
out="${3:?missing out dir}"
activity="io.github.jeshiomurmu.opencaller.MainActivity"
receiver="$app_id/io.github.jeshiomurmu.opencaller.qa.QaCommandReceiver"
role="android.app.role.CALL_SCREENING"
sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
failures=0
mkdir -p "$out"

pass() { echo "PASS $1: $2"; }
fail() {
  echo "::error::FAIL $1: $2"
  failures=$((failures + 1))
}

# qa <command> [--es key value | --ez key true ...]: one QA receiver command; every argument is single-quoted for the
# device shell (patterns contain * and $). Echoes the receiver's answer ("ok …" or "error …").
qa() {
  local args="" arg
  for arg in "$@"; do args+=" '${arg//\'/\'\\\'\'}'"; done
  adb shell "am broadcast -n $receiver --es cmd$args" | tr -d '\r' | sed -n 's/.*data="\(.*\)".*/\1/p'
}

qa_ok() {
  local answer
  answer="$(qa "$@")"
  if [[ "$answer" != ok* ]]; then
    echo "::error::QA command '$*' answered '$answer'"
    exit 1
  fi
}

events() { adb shell run-as "$app_id" cat files/qa/screening_events.tsv 2> /dev/null | tr -d '\r' || true; }
caller_ids() { adb shell run-as "$app_id" cat files/qa/caller_id.tsv 2> /dev/null | tr -d '\r' || true; }
count() { events | grep -c . || true; }

# Waits for the next logged decision after line $1 and prints it (tab-separated, see QaExport).
next_event() {
  local before="$1" n
  for _ in $(seq 1 40); do
    n="$(count)"
    if [[ "$n" -gt "$before" ]]; then
      events | sed -n "$((before + 1))p"
      return 0
    fi
    sleep 0.5
  done
  return 1
}

field() { cut -f "$2" <<< "$1"; }

# ring <tc> <number> <decision> <reason>: incoming call; checks the logged decision and whether the call is still
# ringing two seconds later (rejected calls are gone), then hangs up.
ring() {
  local tc="$1" number="$2" want_decision="$3" want_reason="$4" before event decision reason calls
  before="$(count)"
  adb emu gsm call "$number" > /dev/null
  if ! event="$(next_event "$before")"; then
    fail "$tc" "no screening decision logged for $number"
    adb emu gsm cancel "$number" > /dev/null || true
    return
  fi
  sleep 2
  calls="$(adb emu gsm list | tr -d '\r')"
  adb emu gsm cancel "$number" > /dev/null || true
  sleep 1
  decision="$(field "$event" 4)"
  reason="$(field "$event" 5)"
  local ringing=no
  if grep -q "${number: -4}" <<< "$calls"; then ringing=yes; fi
  if [[ "$decision" == "$want_decision" && "$reason" == "$want_reason" ]] &&
    { [[ "$want_decision" == BLOCK && "$ringing" == no ]] || [[ "$want_decision" != BLOCK && "$ringing" == yes ]]; }; then
    pass "$tc" "$number → $decision ($reason), latency $(field "$event" 7) ms, still ringing: $ringing"
  else
    fail "$tc" "$number → $decision ($reason), still ringing: $ringing; expected $want_decision ($want_reason). Calls: ${calls//$'\n'/ | }"
  fi
}

dump() { adb shell uiautomator dump /sdcard/window.xml > /dev/null 2>&1 && adb shell cat /sdcard/window.xml; }

wait_for_text() {
  for _ in $(seq 1 30); do
    if dump | grep -qF "text=\"$1"; then return 0; fi
    sleep 1
  done
  echo "::error::'$1' not found on screen"
  return 1
}

# Taps the node whose text is exactly $1.
tap_text() {
  local bounds
  bounds="$(dump | grep -oE "text=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' || true)"
  [[ -n "$bounds" ]] || { echo "::error::no node with text '$1'"; return 1; }
  read -r x1 y1 x2 y2 <<< "$(sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/' <<< "$bounds")"
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

open_app() {
  adb shell am start -W -n "$app_id/$activity" > /dev/null
  wait_for_text "Calls"
}

# --- Setup -------------------------------------------------------------------------------------------------------
adb wait-for-device
adb install -r "$apk" > /dev/null
adb shell pm grant "$app_id" android.permission.READ_CONTACTS
adb shell pm grant "$app_id" android.permission.READ_PHONE_STATE
if [[ "$sdk" -ge 33 ]]; then adb shell pm grant "$app_id" android.permission.POST_NOTIFICATIONS; fi
adb shell appops set "$app_id" SYSTEM_ALERT_WINDOW allow
# Granting the role can end the app's process on recent Android versions; the role stays granted.
adb shell cmd role add-role-holder "$role" "$app_id" 0 || adb shell cmd role add-role-holder "$role" "$app_id"
# `cmd role get-role-holders` doesn't exist on API 29, so ask the app (RoleManager.isRoleHeld).
held=""
for _ in $(seq 1 20); do
  held="$(qa role)"
  if [[ "$held" == "ok held=true" ]]; then break; fi
  sleep 1
done
[[ "$held" == "ok held=true" ]] || { echo "::error::call screening role not granted ($held)"; exit 1; }
qa_ok reset
# Keep the process alive (and the encrypted database open) like a phone in everyday use.
open_app
adb shell input keyevent KEYCODE_HOME
echo "API $sdk: $app_id holds the call screening role"

# --- Cases -------------------------------------------------------------------------------------------------------
qa_ok rule --es type EXACT --es pattern +12015550111 --es action BLOCK
ring TC-BLK-002 +12015550111 BLOCK rule

qa_ok rule --es type PREFIX --es pattern '+1555123*' --es action BLOCK
ring TC-BLK-003 +15551230001 BLOCK rule
ring TC-BLK-003 +15551240001 ALLOW no_match

answer="$(qa rule --es type REGEX --es pattern '(a+)+$' --es action BLOCK)"
if [[ "$answer" == "error SLOW_REGEX" ]]; then pass TC-BLK-004 "editor rejected (a+)+\$ on this device"; else fail TC-BLK-004 "answer: $answer"; fi

qa_ok region --es region BD
qa_ok settings --ez foreign true
ring TC-BLK-006 +12015550123 BLOCK foreign
qa_ok settings --es allowed US
ring TC-BLK-006 +12015550123 ALLOW no_match
qa_ok settings --ez foreign false --es allowed ""
qa_ok region

qa_ok settings --ez nonContacts true
ring TC-BLK-008 +12015550124 BLOCK non_contact
qa_ok verified --es number +12015550125
ring TC-BLK-008 +12015550125 ALLOW no_match
qa_ok settings --ez nonContacts false

qa_ok settings --es threshold HIGH
qa_ok spam --es number +12015550130 --es score 0.9 --es category Loan
qa_ok spam --es number +12015550131 --es score 0.7 --es category Loan
ring TC-BLK-009 +12015550130 BLOCK spam_list
ring TC-BLK-009 +12015550131 ALLOW no_match
card="$(caller_ids | grep -F "+12015550131" | tail -1 || true)"
if [[ "$(field "$card" 3)" == SUSPICIOUS ]]; then
  pass TC-BLK-009 "caller ID showed the Suspicious card ($(field "$card" 2))"
else
  fail TC-BLK-009 "caller ID for +12015550131: '${card:-none}'"
fi
risk="$(events | grep -F "+12015550131" | tail -1 | cut -f 9)"
[[ "$risk" == SUSPICIOUS ]] || fail TC-BLK-009 "risk logged for +12015550131: $risk"

qa_ok rule --es type EXACT --es pattern +12015550140 --es action BLOCK
before="$(count)"
adb shell am start -a android.intent.action.CALL -d tel:+12015550140 > /dev/null
event="$(next_event "$before" || true)"
sleep 3
calls="$(adb emu gsm list | tr -d '\r')"
if ! grep -qi "outbound.*0140" <<< "$calls"; then
  fail TC-BLK-012 "the outgoing call did not go out: ${calls//$'\n'/ | }"
elif [[ -n "$event" && ("$(field "$event" 3)" != OUTGOING || "$(field "$event" 4)" != ALLOW) ]]; then
  fail TC-BLK-012 "outgoing call screened as '$event'"
else
  pass TC-BLK-012 "outgoing call to a blocked number proceeds (${calls//$'\n'/ | }; logged: ${event:-not screened})"
fi
adb emu gsm cancel +12015550140 > /dev/null || true
adb shell input keyevent KEYCODE_ENDCALL || true
sleep 2

qa_ok rule --es type EXACT --es pattern +12015550150 --es action BLOCK
ring TC-BLK-015 +12015550150 BLOCK rule
open_app
tap_text "Protection"
wait_for_text "Blocked calls"
tap_text "Blocked calls"
wait_for_text "Unblock"
adb exec-out screencap -p > "$out/blocked-log.png"
tap_text "Unblock"
wait_for_text "+1 ••• 0150 will ring next time" || fail TC-BLK-015 "no confirmation snackbar"
adb shell input keyevent KEYCODE_HOME
ring TC-BLK-015 +12015550150 ALLOW no_match

# --- Latency -----------------------------------------------------------------------------------------------------
events > "$out/screening_events.tsv"
caller_ids > "$out/caller_id.tsv"
python3 - "$out/screening_events.tsv" << 'PY'
import math, sys
rows = [line.rstrip("\n").split("\t") for line in open(sys.argv[1]) if line.strip()]
local = sorted(int(r[6]) for r in rows if r[2] == "INCOMING" and r[4] not in ("timeout", "error", "emergency"))
pct = lambda p: local[max(0, math.ceil(p * len(local)) - 1)]
print(f"Export: {len(local)} local decisions, p50 {pct(0.5)} ms, p95 {pct(0.95)} ms, max {local[-1]} ms")
PY
open_app
tap_text "Me"
wait_for_text "About"
for _ in $(seq 1 7); do
  tap_text "About"
  sleep 0.3
done
if wait_for_text "Local decisions"; then
  line="$(dump | grep -oE 'text="Local decisions[^"]*"' | head -1)"
  echo "Debug screen: ${line#text=}"
  adb exec-out screencap -p > "$out/debug-screen.png"
  p95="$(sed -nE 's/.*p95 ([0-9]+) ms.*/\1/p' <<< "$line")"
  if [[ -n "$p95" && "$p95" -lt 50 ]]; then pass LATENCY "p95 $p95 ms < 50 ms"; else fail LATENCY "p95 '${p95:-?}' ms is not under 50 ms"; fi
else
  fail LATENCY "debug screen did not open"
fi

if adb logcat -d -b crash | grep -q "$app_id"; then
  fail CRASH "$app_id crashed"
  adb logcat -d -b crash
fi

if [[ "$failures" -gt 0 ]]; then
  echo "::error::$failures blocking QA check(s) failed"
  exit 1
fi
echo "OK: all blocking QA cases passed on API $sdk"
