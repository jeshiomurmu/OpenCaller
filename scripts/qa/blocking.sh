#!/usr/bin/env bash
# Emulator QA for call screening (docs/testing.md, TC-BLK-*). Installs a debug APK, gives it the call screening role,
# sets rules and settings through the debug-only QA receiver, places calls with `adb emu gsm call` and reads every
# decision from the debug export (files/qa/*.tsv, via run-as). Prints PASS/FAIL per case and the decision latency
# (p50/p95, from the export and from the debug screen). Exits non-zero if any case fails.
#
#   TC-BLK-001  contact rings although a block rule covers it
#   TC-BLK-002  exact block            TC-BLK-003  prefix block (+1555123*)
#   TC-BLK-004  slow regex rejected     TC-BLK-006  foreign numbers (SIM BD), US allowed
#   TC-BLK-008  non-contacts, verified business rings
#   TC-BLK-009  spam list 0.9 rejected / 0.7 rings as Suspicious (threshold high)
#   TC-BLK-012  outgoing call to a blocked number proceeds
#   TC-BLK-015  Unblock in the blocked-calls log, next call rings
#   LATENCY     p95 of the local decisions (export and debug screen) under 50 ms, over 30+ calls
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

# The phone's call state from the telephony registry: 0 idle, 1 ringing, 2 off-hook (dialling or in a call).
# (`adb emu gsm list` lists no calls on recent emulator images.)
call_state() { adb shell dumpsys telephony.registry | tr -d '\r' | grep -oE 'mCallState=[0-9]' | head -1 | cut -d= -f2; }

wait_for_state() {
  for _ in $(seq 1 40); do
    if [[ "$(call_state)" == "$1" ]]; then return 0; fi
    sleep 0.5
  done
  return 1
}

hang_up() {
  adb emu gsm cancel "$1" > /dev/null || true
  wait_for_state 0 || { adb shell input keyevent KEYCODE_ENDCALL || true; wait_for_state 0 || true; }
}

# ring <tc> <number> <decision> <reason>: incoming call; checks the logged decision and whether the phone is still
# ringing two seconds later (a rejected call is gone, an allowed or silenced one rings), then hangs up.
ring() {
  local tc="$1" number="$2" want_decision="$3" want_reason="$4" before event decision reason state
  before="$(count)"
  adb emu gsm call "$number" > /dev/null
  if ! event="$(next_event "$before")"; then
    fail "$tc" "no screening decision logged for $number"
    hang_up "$number"
    return
  fi
  sleep 2
  state="$(call_state)"
  hang_up "$number"
  decision="$(field "$event" 4)"
  reason="$(field "$event" 5)"
  local ringing=no
  if [[ "$state" == 1 ]]; then ringing=yes; fi
  if [[ "$decision" == "$want_decision" && "$reason" == "$want_reason" ]] &&
    { [[ "$want_decision" == BLOCK && "$ringing" == no ]] || [[ "$want_decision" != BLOCK && "$ringing" == yes ]]; }; then
    pass "$tc" "$number → $decision ($reason), latency $(field "$event" 7) ms, ringing after 2 s: $ringing"
  else
    fail "$tc" "$number → $decision ($reason), ringing after 2 s: $ringing (call state $state); expected $want_decision ($want_reason)"
  fi
}

dump() { adb shell uiautomator dump /sdcard/window.xml > /dev/null 2>&1 && adb shell cat /sdcard/window.xml; }

wait_for_text() {
  for _ in $(seq 1 30); do
    if dump | grep -qF "text=\"$1"; then return 0; fi
    sleep 1
  done
  echo "::error::'$1' not found on screen; showing: $(dump | grep -oE 'text="[^"]+"' | head -40 | tr '\n' ' ')"
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

# add_contact <name> <number>: a local (account-less) contact, written through the contacts provider like the
# Contacts app would (the shell holds WRITE_CONTACTS). `adb shell` re-splits its arguments, so <name> is one word.
add_contact() {
  local uri=content://com.android.contacts id
  adb shell content insert --uri "$uri/raw_contacts" --bind account_type:n: --bind account_name:n:
  id="$(adb shell content query --uri "$uri/raw_contacts" --projection _id | tr -d '\r' | grep -oE '_id=[0-9]+' | cut -d= -f2 | sort -n | tail -1)"
  [[ -n "$id" ]] || { echo "::error::could not create a raw contact"; return 1; }
  adb shell content insert --uri "$uri/data" --bind "raw_contact_id:i:$id" \
    --bind mimetype:s:vnd.android.cursor.item/name --bind "data1:s:$1"
  adb shell content insert --uri "$uri/data" --bind "raw_contact_id:i:$id" \
    --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind "data1:s:$2" --bind data2:i:2
}

open_app() {
  wait_for_state 0 || true
  adb shell input keyevent KEYCODE_HOME
  sleep 1
  adb shell am start -W -n "$app_id/$activity" > /dev/null
  wait_for_text "Protection"
}

# --- Setup -------------------------------------------------------------------------------------------------------
adb wait-for-device
# Room for every call's "Screening timings" debug line (read back after the latency check).
adb logcat -G 16M || true
adb install -r "$apk" > /dev/null
adb shell pm grant "$app_id" android.permission.READ_CONTACTS
adb shell pm grant "$app_id" android.permission.READ_PHONE_STATE
if [[ "$sdk" -ge 33 ]]; then adb shell pm grant "$app_id" android.permission.POST_NOTIFICATIONS; fi
adb shell appops set "$app_id" SYSTEM_ALERT_WINDOW allow
# Granting the role can end the app's process on recent Android versions; the role stays granted. On API 29 the
# shell command can time out waiting for the role controller although the grant still happens, so it is retried and
# the result is read from the app (RoleManager.isRoleHeld; `cmd role get-role-holders` doesn't exist on API 29).
held=""
for attempt in 1 2; do
  adb shell cmd role add-role-holder "$role" "$app_id" 0 || echo "add-role-holder attempt $attempt did not confirm"
  for _ in $(seq 1 30); do
    held="$(qa role)"
    if [[ "$held" == "ok held=true" ]]; then break 2; fi
    sleep 1
  done
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

add_contact Ada +12015550160
qa_ok rule --es type PREFIX --es pattern '+1201555016*' --es action BLOCK
ring TC-BLK-001 +12015550160 ALLOW contact
ring TC-BLK-001 +12015550161 BLOCK rule

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
echo "  $(adb shell am start -a android.intent.action.CALL -d tel:+12015550140 | tr -d '\r' | tr '\n' ' ')"
event="$(next_event "$before" || true)"
sleep 3
hang_up +12015550140
sleep 2
# The system call log (readable by the shell) records an outgoing call (type 2) only if it was placed.
placed="$(adb shell content query --uri content://call_log/calls --projection number:type --where "type=2" | tr -d '\r' | grep -c "0140" || true)"
if [[ "$placed" -lt 1 ]]; then
  fail TC-BLK-012 "no outgoing call to +12015550140 in the call log; logged decision: ${event:-none}"
  adb shell dumpsys telecom | tr -d '\r' | grep -iE "disconnect|0140" | tail -n 20 || true
elif [[ -n "$event" && ("$(field "$event" 3)" != OUTGOING || "$(field "$event" 4)" != ALLOW) ]]; then
  fail TC-BLK-012 "outgoing call screened as '$event'"
else
  pass TC-BLK-012 "outgoing call to a blocked number was placed (call log); screening: ${event:-not consulted}"
fi

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
# With the dozen calls above, p95 would just be the slowest call. Four more rounds over every local path (contact,
# rule, spam list, Suspicious card, no match) bring the sample to over 30 decisions.
for round in 1 2 3 4; do
  ring "SAMPLE $round" +12015550160 ALLOW contact
  ring "SAMPLE $round" +15551230002 BLOCK rule
  ring "SAMPLE $round" +12015550130 BLOCK spam_list
  ring "SAMPLE $round" +12015550131 ALLOW no_match
  ring "SAMPLE $round" "+1201555018$round" ALLOW no_match
done
events > "$out/screening_events.tsv"
# Per-step durations of every decision (debug log of ScreeningEngine; durations and reason codes only).
adb logcat -d | tr -d '\r' | grep -F "Screening timings:" > "$out/screening_timings.txt" || true
echo "Slowest decisions (step durations in ms; lookups overlap):"
sed -E 's/.*Screening timings: //' "$out/screening_timings.txt" | sort -t' ' -k3,3 -n -r | head -n 6 | sed 's/^/  /' || true
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
