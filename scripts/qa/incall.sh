#!/usr/bin/env bash
# Emulator QA for the default phone app (docs/testing.md). Gives the debug build the default phone app role (and keeps
# the call screening role from scripts/qa/blocking.sh), then:
#
#   TC-INCALL-001  the phone is locked with a PIN and asleep; an incoming call (`adb emu gsm call`) turns the screen
#                  on and shows OpenCaller's in-call screen over the lock screen, with the caller card from screening
#                  ("Suspicious" for a number on the spam list below the threshold); Answer connects the call without
#                  unlocking; Hang up ends it
#   TC-DIAL-001    a number opened in the dial pad (ACTION_DIAL with tel:) and Call place the call through Telecom
#                  (placeCall) and show the in-call screen; Hang up ends it
#
# The call state is read from the telephony registry (0 idle, 1 ringing, 2 off-hook). Screenshots go to <out-dir>.
# Run it last: it leaves the dialer role removed and the lock screen cleared, but the app's settings reset.
#
# Usage: scripts/qa/incall.sh <applicationId> <out-dir>
set -euo pipefail

app_id="${1:?usage: incall.sh <applicationId> <out-dir>}"
out="${2:?missing out dir}"
receiver="$app_id/io.github.jeshiomurmu.opencaller.qa.QaCommandReceiver"
in_call="io.github.jeshiomurmu.opencaller.feature.incall.InCallActivity"
dialer_role="android.app.role.DIALER"
sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
pin="1234"
caller="+12015550199"
failures=0
mkdir -p "$out"

pass() { echo "PASS $1: $2"; }
fail() {
  echo "::error::FAIL $1: $2"
  failures=$((failures + 1))
}

qa() {
  local args="" arg
  for arg in "$@"; do args+=" '${arg//\'/\'\\\'\'}'"; done
  adb shell "am broadcast -n $receiver --es cmd$args" | tr -d '\r' | sed -n 's/.*data="\(.*\)".*/\1/p'
}

qa_ok() {
  local answer
  answer="$(qa "$@")"
  [[ "$answer" == ok* ]] || { echo "::error::QA command '$*' answered '$answer'"; exit 1; }
}

dump() { adb shell uiautomator dump /sdcard/window.xml > /dev/null 2>&1 && adb shell cat /sdcard/window.xml; }

# Checks read a command's whole output first: with pipefail, `cmd | grep -q` fails when grep stops reading early.
on_screen() {
  local window
  window="$(dump)" || true
  grep -qF "text=\"$1\"" <<< "$window"
}

# Waits until some on-screen text starts with $1 (the risk banner reads e.g. "Suspicious · Loan offers · 1 report").
wait_for_text_starting() {
  for _ in $(seq 1 20); do
    local window
    window="$(dump)" || true
    if grep -qF "text=\"$1" <<< "$window"; then return 0; fi
    sleep 1
  done
  return 1
}

wait_for_text() {
  for _ in $(seq 1 30); do
    if on_screen "$1"; then return 0; fi
    sleep 1
  done
  return 1
}

tap_text() {
  local bounds x1 y1 x2 y2
  local window
  window="$(dump)" || true
  bounds="$(grep -oE "text=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" <<< "$window" | sed -n 1p | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' || true)"
  [[ -n "$bounds" ]] || { echo "no on-screen node with text '$1'"; return 1; }
  read -r x1 y1 x2 y2 <<< "$(sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/' <<< "$bounds")"
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

call_state() {
  local registry
  registry="$(adb shell dumpsys telephony.registry | tr -d '\r')"
  grep -oE 'mCallState=[0-9]' <<< "$registry" | sed -n 1p | cut -d= -f2
}

wait_for_state() {
  for _ in $(seq 1 40); do
    if [[ "$(call_state)" == "$1" ]]; then return 0; fi
    sleep 0.5
  done
  return 1
}

# The activity in front (the resumed one), from the activity manager.
resumed_activity() {
  local activities
  activities="$(adb shell dumpsys activity activities | tr -d '\r')"
  grep -E 'mResumedActivity|topResumedActivity|ResumedActivity:' <<< "$activities" | sed -n 1p || true
}

wait_for_in_call_screen() {
  for _ in $(seq 1 30); do
    if [[ "$(resumed_activity)" == *"$in_call"* ]]; then return 0; fi
    sleep 1
  done
  return 1
}

# The dump format differs between versions; the window policy's KeyguardServiceDelegate prints "showing=true" while the
# keyguard is up (also while an activity shows over it), and the activity manager's KeyguardController has its own line.
keyguard_dump() { { adb shell dumpsys window policy; adb shell dumpsys activity activities; } | tr -d '\r'; }

keyguard_locked() {
  local state
  state="$(keyguard_dump)" || true
  grep -qE '^\s*showing=true|mKeyguardShowing=true|isKeyguardShowing=true|mIsShowing=true|isStatusBarKeyguard=true|KeyguardShowing: true' <<< "$state"
}

# Unlocks the way a person does (the PIN on the bouncer), then removes the PIN.
unlock() {
  for _ in $(seq 1 5); do
    adb shell input keyevent KEYCODE_WAKEUP
    adb shell input keyevent KEYCODE_MENU
    # On a secure keyguard this brings up the PIN bouncer.
    adb shell wm dismiss-keyguard > /dev/null 2>&1 || true
    sleep 1
    adb shell input text "$pin"
    adb shell input keyevent KEYCODE_ENTER
    sleep 2
    if ! keyguard_locked; then break; fi
  done
  adb shell locksettings clear --old "$pin" > /dev/null || echo "warning: could not clear the PIN"
  adb shell wm dismiss-keyguard > /dev/null 2>&1 || true
  sleep 1
  if keyguard_locked; then
    echo "::error::the phone is still locked"
    keyguard_dump | grep -iE 'showing|keyguard' | head -20 || true
    return 1
  fi
}

screenshot() { adb exec-out screencap -p > "$out/$1.png" || true; }

cleanup() {
  adb shell settings put secure lock_screen_lock_after_timeout "${lock_after:-5000}" > /dev/null 2>&1 || true
  adb emu gsm cancel "$caller" > /dev/null 2>&1 || true
  adb shell input keyevent KEYCODE_WAKEUP || true
  adb shell locksettings clear --old "$pin" > /dev/null 2>&1 || true
  adb shell wm dismiss-keyguard > /dev/null 2>&1 || true
  adb shell cmd role remove-role-holder "$dialer_role" "$app_id" 0 > /dev/null 2>&1 || true
}
trap cleanup EXIT

diagnostics() {
  echo "--- resumed: $(resumed_activity)"
  echo "--- call state: $(call_state)"
  dump | grep -oE 'text="[^"]+"' | head -40 || true
  adb shell dumpsys telecom | tr -d '\r' | grep -iE 'state|call ' | head -40 || true
  adb logcat -d > "$out/logcat-$1.txt" 2>&1 || true
  adb shell dumpsys notification --noredact | tr -d '\r' | grep -E 'pkg=|fullscreenIntent|android.title' | grep -A2 "$app_id" | head -20 || true
}

# --- Setup ---------------------------------------------------------------------------------------------------------
# The role can take a moment (and can end the app's process on recent Android versions); read it back from the app.
held=""
for attempt in 1 2; do
  adb shell cmd role add-role-holder "$dialer_role" "$app_id" 0 || echo "add-role-holder attempt $attempt did not confirm"
  for _ in $(seq 1 30); do
    held="$(qa role --es role DIALER)"
    if [[ "$held" == "ok held=true" ]]; then break 2; fi
    sleep 1
  done
done
[[ "$held" == "ok held=true" ]] || { echo "::error::default phone app role not granted ($held)"; exit 1; }
echo "API $sdk: $app_id is the default phone app"

if [[ "$sdk" -ge 33 ]]; then adb shell pm grant "$app_id" android.permission.POST_NOTIFICATIONS; fi
adb shell pm grant "$app_id" android.permission.CALL_PHONE
# Android 14+: full-screen intents can be off for sideloaded apps; OpenCaller's Permissions screen offers Fix.
if [[ "$sdk" -ge 34 ]]; then
  echo "USE_FULL_SCREEN_INTENT before: $(adb shell appops get "$app_id" USE_FULL_SCREEN_INTENT | tr -d '\r')"
  adb shell appops set "$app_id" USE_FULL_SCREEN_INTENT allow || echo "warning: could not allow full-screen intents"
fi
qa_ok reset
qa_ok settings --es threshold HIGH
qa_ok spam --es number "$caller" --es score 0.7 --es category Loan

# --- TC-INCALL-001: answer from the lock screen ---------------------------------------------------------------------
adb shell locksettings set-pin "$pin" > /dev/null
# A PIN locks 5 s after the screen goes off by default; lock at once instead.
lock_after="$(adb shell settings get secure lock_screen_lock_after_timeout | tr -d '\r')"
[[ "$lock_after" =~ ^[0-9]+$ ]] || lock_after=5000
adb shell settings put secure lock_screen_lock_after_timeout 0
adb shell input keyevent KEYCODE_SLEEP
for _ in $(seq 1 10); do
  if keyguard_locked; then break; fi
  sleep 1
done
if keyguard_locked; then
  echo "Locked with a PIN, screen off"
else
  echo "warning: keyguard state not reported as showing:"
  keyguard_dump | grep -iE 'showing|keyguard' | head -20 || true
fi

adb emu gsm call "$caller"
if wait_for_in_call_screen && wait_for_text "Answer"; then
  screenshot incoming-locked
  if keyguard_locked; then
    pass TC-INCALL-001 "in-call screen shown over the lock screen"
  else
    fail TC-INCALL-001 "in-call screen shown, but the phone is not locked"
  fi
  if wait_for_text_starting "Suspicious"; then pass TC-INCALL-001 "caller card from screening (Suspicious)"; else fail TC-INCALL-001 "no caller card"; fi
  tap_text "Answer"
  if wait_for_state 2 && wait_for_text "Hang up"; then
    screenshot answered-locked
    pass TC-INCALL-001 "answered without unlocking"
  else
    fail TC-INCALL-001 "Answer did not connect the call (state $(call_state))"
  fi
  tap_text "Hang up" || true
  if wait_for_state 0; then pass TC-INCALL-001 "Hang up ended the call"; else fail TC-INCALL-001 "call still up"; fi
else
  screenshot incoming-locked-missing
  diagnostics incoming
  fail TC-INCALL-001 "no in-call screen for the incoming call"
fi
adb emu gsm cancel "$caller" > /dev/null 2>&1 || true
wait_for_state 0 || true

# Unlock for the next case and let Telecom finish with the first call (disconnect tone, unbinding).
wait_for_state 0 || true
unlock || fail TC-INCALL-001 "could not unlock after the call"
sleep 5

# --- TC-DIAL-001: the dial pad places a call and the in-call screen shows it --------------------------------------
adb shell am start -a android.intent.action.DIAL -d "tel:+12015550198" > /dev/null
if wait_for_text "+12015550198" && on_screen "Call"; then
  screenshot dial-pad
  tap_text "Call"
  if wait_for_in_call_screen && wait_for_text "Hang up"; then
    screenshot outgoing
    pass TC-DIAL-001 "the dial pad placed the call and the in-call screen shows it"
    tap_text "Hang up" || true
    if wait_for_state 0; then pass TC-DIAL-001 "Hang up ended the call"; else fail TC-DIAL-001 "call still up"; fi
  else
    screenshot outgoing-missing
    diagnostics outgoing
    fail TC-DIAL-001 "no in-call screen for the call placed from the dial pad"
  fi
else
  screenshot dial-pad-missing
  diagnostics dial-pad
  fail TC-DIAL-001 "the dial pad didn't open with the number"
fi
adb emu gsm cancel "+12015550198" > /dev/null 2>&1 || true

if [[ "$failures" -gt 0 ]]; then
  echo "::error::$failures in-call check(s) failed"
  exit 1
fi
echo "In-call QA: all checks passed"
