#!/usr/bin/env bash
# Emulator QA for the default SMS app (docs/testing.md). Gives the debug build the default SMS app role, then:
#
#   TC-SMS-001  an SMS from the emulator console (`adb emu sms send`) is stored in Android's message store (inbox),
#               gets a MessageMeta row (Personal) without its text, shows a conversation notification, and appears in
#               the thread, which then counts as read
#   TC-SMS-002  a long SMS (3 parts) written in the thread (opened with ACTION_SENDTO, the text from sms_body) goes out
#               with sendMultipartTextMessage, moves to the sent box, and comes back whole: the emulator delivers
#               messages to its own number (1555521<console port>)
#
# Screenshots go to <out-dir>. Removes the role again at the end.
#
# Usage: scripts/qa/sms.sh <applicationId> <out-dir>
set -euo pipefail

app_id="${1:?usage: sms.sh <applicationId> <out-dir>}"
out="${2:?missing out dir}"
receiver="$app_id/io.github.jeshiomurmu.opencaller.qa.QaCommandReceiver"
main="$app_id/io.github.jeshiomurmu.opencaller.MainActivity"
sms_role="android.app.role.SMS"
sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
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
wait_for_text_starting() {
  for _ in $(seq 1 30); do
    local window
    window="$(dump)" || true
    if grep -qF "text=\"$1" <<< "$window"; then return 0; fi
    sleep 1
  done
  return 1
}

# Taps the node whose content description is $1 (an icon button).
tap_description() {
  local window bounds x1 y1 x2 y2
  window="$(dump)" || true
  bounds="$(grep -oE "content-desc=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" <<< "$window" | sed -n 1p | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' || true)"
  [[ -n "$bounds" ]] || { echo "no on-screen node described '$1'"; return 1; }
  read -r x1 y1 x2 y2 <<< "$(sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/' <<< "$bounds")"
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

# Waits until `qa messages --es address $1` answers with something matching the regex $2; prints the answer.
wait_for_messages() {
  local answer=""
  for _ in $(seq 1 "${3:-40}"); do
    answer="$(qa messages --es address "$1")"
    if [[ "$answer" =~ $2 ]]; then
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

notifications() { adb shell dumpsys notification --noredact | tr -d '\r'; }

diagnostics() {
  dump | grep -oE '(text|content-desc)="[^"]+"' | head -40 || true
  adb logcat -d > "$out/logcat-$1.txt" 2>&1 || true
  grep -E 'opencaller|SmsManager|InboundSmsHandler|SMSDispatcher|AndroidRuntime' "$out/logcat-$1.txt" | tail -n 60 || true
}

cleanup() {
  adb shell cmd role remove-role-holder "$sms_role" "$app_id" 0 > /dev/null 2>&1 || true
}
trap cleanup EXIT

# --- Setup ---------------------------------------------------------------------------------------------------------
held=""
for attempt in 1 2; do
  adb shell cmd role add-role-holder "$sms_role" "$app_id" 0 || echo "add-role-holder attempt $attempt did not confirm"
  for _ in $(seq 1 30); do
    held="$(qa role --es role SMS)"
    if [[ "$held" == "ok held=true" ]]; then break 2; fi
    sleep 1
  done
done
[[ "$held" == "ok held=true" ]] || { echo "::error::default SMS app role not granted ($held)"; exit 1; }
echo "API $sdk: $app_id is the default SMS app"

# The role grants these; granting them again is a no-op and covers a slow role grant.
for permission in READ_SMS SEND_SMS RECEIVE_SMS RECEIVE_MMS READ_CONTACTS READ_PHONE_STATE; do
  adb shell pm grant "$app_id" "android.permission.$permission" > /dev/null 2>&1 || echo "note: $permission not granted by pm"
done
if [[ "$sdk" -ge 33 ]]; then adb shell pm grant "$app_id" android.permission.POST_NOTIFICATIONS; fi
qa_ok reset
adb shell input keyevent KEYCODE_HOME

# --- TC-SMS-001: receive as the default SMS app ----------------------------------------------------------------------
sender="5550001111"
text="TC-SMS-001 hello $(date +%s)"
adb logcat -c || true
adb emu sms send "$sender" "$text"
if answer="$(wait_for_messages "$sender" "body=$text\$")"; then
  echo "$answer"
  pass TC-SMS-001 "stored in the inbox of Android's message store"
  if [[ "$(field meta "$answer")" == PERSONAL ]]; then pass TC-SMS-001 "MessageMeta row (Personal)"; else fail TC-SMS-001 "no MessageMeta row ($answer)"; fi
  thread="$(field thread "$answer")"
  posted="$(notifications)"
  if grep -qE "pkg=$app_id .*tag=thread|$app_id\|$thread\|thread" <<< "$posted"; then
    pass TC-SMS-001 "conversation notification posted"
  else
    fail TC-SMS-001 "no conversation notification"
    grep -E "pkg=$app_id" <<< "$posted" | head -10 || true
  fi
  adb shell am start -n "$main" --el io.github.jeshiomurmu.opencaller.extra.OPEN_THREAD "$thread" > /dev/null
  if wait_for_text_starting "$text"; then
    screenshot thread-received
    pass TC-SMS-001 "shown in the thread"
    if wait_for_messages "$sender" "unread=0 " 15 > /dev/null; then pass TC-SMS-001 "the open thread counts as read"; else fail TC-SMS-001 "still unread after opening the thread"; fi
  else
    screenshot thread-received-missing
    diagnostics received-thread
    fail TC-SMS-001 "not shown in the thread"
  fi
else
  echo "last answer: $answer"
  diagnostics received
  fail TC-SMS-001 "the SMS was not stored"
fi
adb shell input keyevent KEYCODE_HOME

# --- TC-SMS-002: send a long SMS (multipart) ---------------------------------------------------------------------------
serial="$(adb get-serialno | tr -d '\r')"
own="1555521${serial#emulator-}"
long="TC-SMS-002 $(date +%s) $(printf 'long text %.0s' $(seq 1 40))"
long="${long% }"
adb logcat -c || true
adb shell "am start -n $main -a android.intent.action.SENDTO -d smsto:$own --es sms_body '$long'" > /dev/null
if wait_for_text_starting "TC-SMS-002"; then
  screenshot thread-draft
  tap_description "Send" || fail TC-SMS-002 "no Send button"
  parts=""
  for _ in $(seq 1 30); do
    log="$(adb logcat -d 2> /dev/null)" || true
    parts="$(grep -oE 'handed to the network in [0-9]+ parts' <<< "$log" | sed -n 1p | grep -oE '[0-9]+ parts' || true)"
    [[ -n "$parts" ]] && break
    sleep 1
  done
  if [[ "$parts" =~ ^([0-9]+)\ parts$ ]] && [[ "${BASH_REMATCH[1]}" -ge 3 ]]; then
    pass TC-SMS-002 "sent with sendMultipartTextMessage in $parts (${#long} characters)"
  else
    fail TC-SMS-002 "not sent as a multipart SMS ('$parts')"
  fi
  if answer="$(wait_for_messages "$own" " out=SENT/")"; then
    pass TC-SMS-002 "moved to the sent box (delivery report: $(field out "$answer" | cut -d/ -f2))"
  else
    diagnostics sent
    fail TC-SMS-002 "not in the sent box ($answer)"
  fi
  if answer="$(wait_for_messages "$own" "body=$long\$" 60)"; then
    screenshot thread-sent
    pass TC-SMS-002 "the long SMS came back to the emulator's own number whole"
  else
    diagnostics loopback
    fail TC-SMS-002 "the long SMS didn't come back whole ($answer)"
  fi
else
  screenshot thread-draft-missing
  diagnostics draft
  fail TC-SMS-002 "ACTION_SENDTO didn't open the thread with the text"
fi
adb shell input keyevent KEYCODE_HOME

if [[ "$failures" -gt 0 ]]; then
  echo "::error::$failures SMS check(s) failed"
  exit 1
fi
echo "SMS QA: all checks passed"
