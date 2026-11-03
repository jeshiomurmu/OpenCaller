#!/usr/bin/env bash
# Emulator QA for the OTP safety guard (docs/testing.md). Makes the debug build the default SMS app and the default
# phone app, then:
#
#   TC-OTP-006  a call from a contact is answered and a one-time code arrives by SMS (`adb emu sms send`): the code is
#               stored as an OTP without a risk, and no warning opens
#   TC-OTP-005  the same with a caller who is not a contact: the guard raises its warning within 1 s of the SMS
#               reaching OpenCaller (timed from the app's debug log), the full-screen warning opens over the in-call
#               screen, the message is stored with the OTP_DURING_CALL risk, and the warning's Hang up ends the call
#   TC-OTP-008  with a PIN and Android's default lock screen setting ("Show all notification content"), nothing of the
#               code's notification is on the lock screen or in its shade: it is posted VISIBILITY_SECRET (owner's
#               decision, docs/phase.md issue 100), which dumpsys confirms
#
# 006 runs first: a code arriving up to 2 minutes after a call with a stranger counts too. Each call rings with the
# screen off, so Android opens the in-call screen through its full-screen intent (with the screen on and unlocked it
# only posts a heads-up, which UI Automator can't tap). Screenshots go to <out-dir>. Removes both roles, the contact
# and the PIN again at the end.
#
# Usage: scripts/qa/otp.sh <applicationId> <out-dir>
set -euo pipefail

app_id="${1:?usage: otp.sh <applicationId> <out-dir>}"
out="${2:?missing out dir}"
receiver="$app_id/io.github.jeshiomurmu.opencaller.qa.QaCommandReceiver"
in_call="io.github.jeshiomurmu.opencaller.feature.incall.InCallActivity"
guard="io.github.jeshiomurmu.opencaller.feature.otp.OtpGuardActivity"
sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
contact_number="+12015550177"
stranger="+12015550188"
code_sender="57575"
pin="1234"
contact_id=""
lock_after=""
code_sent=""
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

wait_for_text() {
  for _ in $(seq 1 30); do
    local window
    window="$(dump)" || true
    if grep -qF "text=\"$1\"" <<< "$window"; then return 0; fi
    sleep 1
  done
  return 1
}

tap_text() {
  local bounds x1 y1 x2 y2 window
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

resumed_activity() {
  local activities
  activities="$(adb shell dumpsys activity activities | tr -d '\r')"
  grep -E 'mResumedActivity|topResumedActivity|ResumedActivity:' <<< "$activities" | sed -n 1p || true
}

# Waits up to $2 seconds for activity $1 to be in front.
wait_for_activity() {
  for _ in $(seq 1 "$2"); do
    if [[ "$(resumed_activity)" == *"$1"* ]]; then return 0; fi
    sleep 1
  done
  return 1
}

# Epoch milliseconds of the first log line matching $1 (logcat -v epoch prints seconds.millis first).
log_millis() {
  local line
  line="$(adb logcat -d -v epoch 2> /dev/null | grep -E "$1" | sed -n 1p)" || true
  [[ -n "$line" ]] || return 1
  awk '{ split($1, t, "."); printf "%d%03d\n", t[1], substr(t[2] "000", 1, 3) }' <<< "$line"
}

# The newest message from the code sender, as the QA command reads it (meta=, risks=, code=).
newest_code_message() {
  local answer
  for _ in $(seq 1 30); do
    answer="$(qa messages --es address "$code_sender")"
    if [[ "$answer" == *"body=$1"* && "$answer" == *"meta=OTP"* ]]; then
      echo "$answer"
      return 0
    fi
    sleep 1
  done
  echo "$answer"
  return 1
}

field() { sed -nE "s/.* $1=([^ ]*).*/\1/p" <<< "$2"; }

screenshot() { adb exec-out screencap -p > "$out/$1.png" || true; }

add_contact() {
  local uri=content://com.android.contacts
  adb shell content insert --uri "$uri/raw_contacts" --bind account_type:n: --bind account_name:n:
  contact_id="$(adb shell content query --uri "$uri/raw_contacts" --projection _id | tr -d '\r' | grep -oE '_id=[0-9]+' | cut -d= -f2 | sort -n | tail -1)"
  [[ -n "$contact_id" ]] || { echo "::error::could not create a raw contact"; exit 1; }
  adb shell content insert --uri "$uri/data" --bind "raw_contact_id:i:$contact_id" \
    --bind mimetype:s:vnd.android.cursor.item/name --bind "data1:s:$1"
  adb shell content insert --uri "$uri/data" --bind "raw_contact_id:i:$contact_id" \
    --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind "data1:s:$2" --bind data2:i:2
}

cleanup() {
  adb emu gsm cancel "$contact_number" > /dev/null 2>&1 || true
  adb emu gsm cancel "$stranger" > /dev/null 2>&1 || true
  adb shell input keyevent KEYCODE_WAKEUP || true
  adb shell locksettings clear --old "$pin" > /dev/null 2>&1 || true
  if [[ -n "$lock_after" ]]; then adb shell settings put secure lock_screen_lock_after_timeout "$lock_after" > /dev/null 2>&1 || true; fi
  adb shell wm dismiss-keyguard > /dev/null 2>&1 || true
  if [[ -n "$contact_id" ]]; then
    adb shell content delete --uri content://com.android.contacts/raw_contacts --where "_id=$contact_id" > /dev/null 2>&1 || true
  fi
  adb shell cmd role remove-role-holder android.app.role.DIALER "$app_id" 0 > /dev/null 2>&1 || true
  adb shell cmd role remove-role-holder android.app.role.SMS "$app_id" 0 > /dev/null 2>&1 || true
}
trap cleanup EXIT

diagnostics() {
  echo "--- resumed: $(resumed_activity)"
  echo "--- call state: $(call_state)"
  dump | grep -oE 'text="[^"]+"' | head -40 || true
  adb logcat -d > "$out/logcat-$1.txt" 2>&1 || true
  grep -E 'opencaller|OtpGuard|SMS received|Telecom' "$out/logcat-$1.txt" | grep -vE 'AudioRoute|Bluetooth' | tail -n 60 || true
}

grant_role() {
  local role="$1" name="$2" held=""
  for attempt in 1 2; do
    adb shell cmd role add-role-holder "android.app.role.$role" "$app_id" 0 || echo "add-role-holder attempt $attempt did not confirm"
    for _ in $(seq 1 30); do
      held="$(qa role --es role "$role")"
      if [[ "$held" == "ok held=true" ]]; then return 0; fi
      sleep 1
    done
  done
  echo "::error::$name role not granted ($held)"
  exit 1
}

keyguard_dump() { { adb shell dumpsys window policy; adb shell dumpsys activity activities; } | tr -d '\r'; }

keyguard_locked() {
  local state
  state="$(keyguard_dump)" || true
  grep -qE '^\s*showing=true|mKeyguardShowing=true|isKeyguardShowing=true|mIsShowing=true|isStatusBarKeyguard=true|KeyguardShowing: true' <<< "$state"
}

# Turns the screen off and waits until Android reports it asleep.
screen_off() {
  adb shell input keyevent KEYCODE_SLEEP
  for _ in $(seq 1 10); do
    if adb shell dumpsys power | tr -d '\r' | grep -qE 'mWakefulness=(Asleep|Dozing)'; then return 0; fi
    sleep 1
  done
  echo "warning: the screen did not report going off"
}

# Answers an incoming call from $1 on OpenCaller's in-call screen (opened by the full-screen intent, screen off).
answer_call() {
  screen_off
  adb emu gsm call "$1"
  if wait_for_activity "$in_call" 30 && wait_for_text "Answer"; then
    tap_text "Answer"
    wait_for_state 2
  else
    return 1
  fi
}

# --- Setup ---------------------------------------------------------------------------------------------------------
grant_role SMS "default SMS app"
grant_role DIALER "default phone app"
echo "API $sdk: $app_id is the default SMS app and the default phone app"
for permission in READ_SMS SEND_SMS RECEIVE_SMS READ_CONTACTS READ_PHONE_STATE CALL_PHONE; do
  adb shell pm grant "$app_id" "android.permission.$permission" > /dev/null 2>&1 || echo "note: $permission not granted by pm"
done
if [[ "$sdk" -ge 33 ]]; then adb shell pm grant "$app_id" android.permission.POST_NOTIFICATIONS; fi
if [[ "$sdk" -ge 34 ]]; then adb shell appops set "$app_id" USE_FULL_SCREEN_INTENT allow || echo "warning: could not allow full-screen intents"; fi
qa_ok reset
add_contact Dana "$contact_number"
adb shell input keyevent KEYCODE_HOME

# --- TC-OTP-006: a code during a call with a contact ------------------------------------------------------------------
if answer_call "$contact_number"; then
  code_006="TC-OTP-006 Your Ridely code is 551234. Valid for 5 minutes."
  adb logcat -c || true
  adb emu sms send "$code_sender" "$code_006"
  if answer="$(newest_code_message "$code_006")"; then
    echo "$answer"
    if [[ -z "$(field risks "$answer")" && "$(field code "$answer")" == yes ]]; then
      pass TC-OTP-006 "stored as a code without a risk"
    else
      fail TC-OTP-006 "unexpected risks or no code ($answer)"
    fi
  else
    diagnostics otp-006
    fail TC-OTP-006 "the code SMS was not stored as an OTP ($answer)"
  fi
  sleep 3
  if wait_for_activity "$guard" 3; then
    screenshot otp-006-guard
    fail TC-OTP-006 "the warning opened during a call with a contact"
  elif adb logcat -d 2> /dev/null | grep -q "OTP guard warning raised"; then
    fail TC-OTP-006 "the guard raised a warning during a call with a contact"
  else
    pass TC-OTP-006 "no warning during a call with a contact"
  fi
else
  diagnostics answer-006
  fail TC-OTP-006 "could not answer the contact's call"
fi
adb emu gsm cancel "$contact_number" > /dev/null 2>&1 || true
wait_for_state 0 || true
adb shell input keyevent KEYCODE_HOME

# --- TC-OTP-005: a code during a call with a stranger -----------------------------------------------------------------
code_005="TC-OTP-005 Your Ridely code is 482913. Valid for 5 minutes."
# Earlier QA left many OpenCaller notifications; Android bundles four or more of one app, and TC-OTP-008's lock screen
# would show that bundle only as "OpenCaller". Start TC-OTP-005 (whose code TC-OTP-008 checks) without them.
qa_ok clear-notifications
if answer_call "$stranger"; then
  adb logcat -c || true
  adb emu sms send "$code_sender" "$code_005"
  code_sent=yes
  if wait_for_activity "$guard" 15; then
    screenshot otp-005-guard
    received="$(log_millis 'SMS received' || echo "")"
    raised="$(log_millis 'OTP guard warning raised' || echo "")"
    displayed="$(log_millis "Displayed .*OtpGuardActivity" || echo "")"
    if [[ -n "$received" && -n "$raised" ]] && ((raised - received <= 1000)); then
      pass TC-OTP-005 "warning raised $((raised - received)) ms after the SMS reached OpenCaller"
    else
      fail TC-OTP-005 "warning not raised within 1 s (received=$received raised=$raised)"
    fi
    [[ -n "$received" && -n "$displayed" ]] && echo "Warning screen drawn $((displayed - received)) ms after the SMS reached OpenCaller"
    if wait_for_text "Don't share this code"; then pass TC-OTP-005 "full-screen warning shown during the call"; else fail TC-OTP-005 "warning text missing"; fi
    if answer="$(newest_code_message "$code_005")" && [[ "$(field risks "$answer")" == *OTP_DURING_CALL* ]]; then
      pass TC-OTP-005 "stored with the OTP_DURING_CALL risk"
    else
      fail TC-OTP-005 "no OTP_DURING_CALL risk stored ($answer)"
    fi
    if tap_text "Hang up" && wait_for_state 0; then
      pass TC-OTP-005 "the warning's Hang up ended the call"
    else
      fail TC-OTP-005 "Hang up on the warning did not end the call (state $(call_state))"
    fi
  else
    screenshot otp-005-missing
    diagnostics otp-005
    fail TC-OTP-005 "no warning for a code during a call with a stranger"
  fi
else
  diagnostics answer-005
  fail TC-OTP-005 "could not answer the stranger's call"
fi
adb emu gsm cancel "$stranger" > /dev/null 2>&1 || true
wait_for_state 0 || true

# --- TC-OTP-008: the code is hidden on the lock screen ----------------------------------------------------------------
# The notification checked is TC-OTP-005's code (482913): a new code now would raise the guard again, since the
# stranger's call ended less than 2 minutes ago. The phone is unlocked first, so the PIN applies to a fresh lock screen.
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard > /dev/null 2>&1 || true
for _ in $(seq 1 10); do
  if ! keyguard_locked; then break; fi
  sleep 1
done
keyguard_locked && echo "warning: the swipe lock screen is still showing before the PIN is set"
adb shell locksettings set-pin "$pin" > /dev/null
# Android's default ("Show all notification content") is kept: a secret notification must stay hidden even then.
# A PIN locks 5 s after the screen goes off by default; lock at once instead.
lock_after="$(adb shell settings get secure lock_screen_lock_after_timeout | tr -d '\r')"
[[ "$lock_after" =~ ^[0-9]+$ ]] || lock_after=5000
adb shell settings put secure lock_screen_lock_after_timeout 0
screen_off
adb shell input keyevent KEYCODE_WAKEUP
for _ in $(seq 1 10); do
  if keyguard_locked; then break; fi
  sleep 1
done
if keyguard_locked; then echo "Locked with a PIN"; else echo "warning: keyguard not reported as showing"; fi
keyguard_dump | grep -iE 'secure|showing' | sed -n 1,10p || true
sleep 1
# The lock screen shows only the first few notifications; the shade lists all it may show.
adb shell cmd statusbar expand-notifications > /dev/null 2>&1 || true
sleep 2
locked_window="$(dump)" || true
screenshot otp-008-lock-screen
# Notification.toString() names the channel and the visibility ("vis=SECRET").
posted="$(adb shell dumpsys notification --noredact | tr -d '\r' | grep -F 'channel=otp_codes' | head -1 || true)"
if [[ -z "$code_sent" ]]; then
  fail TC-OTP-008 "not checked: TC-OTP-005 sent no code"
elif grep -qE '482913|Code from|Verification code' <<< "$locked_window"; then
  echo "Lock screen texts:"
  grep -oE 'text="[^"]+"' <<< "$locked_window" | head -30 || true
  fail TC-OTP-008 "the code's notification is on the lock screen"
elif [[ "$posted" != *"vis=SECRET"* ]]; then
  echo "OTP notification in dumpsys: ${posted:-none}"
  fail TC-OTP-008 "no secret OTP notification is posted"
else
  pass TC-OTP-008 "the code's notification is posted secret and nothing of it is on the lock screen (default settings)"
fi

if [[ "$failures" -gt 0 ]]; then
  echo "::error::$failures OTP check(s) failed"
  exit 1
fi
echo "OTP QA: all checks passed"
