#!/usr/bin/env bash
# Emulator run of TC-VOIP-001…003 (docs/testing.md → Phase 11): two emulators, the local compose stack with the real
# LiveKit and the transcoder, the debug build's QA receiver (QaVoip) for sign-in and set-up steps; the call itself
# goes through the app's call screen, Jetpack Telecom and LiveKit. Not a replacement for the two-phone test: both
# emulators sit on one machine (no Wi-Fi ↔ mobile data), and there is no push distributor, so:
#
#   - the callee's push endpoint is a sink on the host (scripts/e2e/push_sink.py), which records what the server
#     sends (it must be Web Push ciphertext with the right TTL and urgency);
#   - the callee gets the invite the way a phone that missed the push does: it opens the app (`GET /v1/notifications`).
#
#   1. TC-VOIP-001: A calls B over the app; B rings (Telecom, the incoming-call notification, A's clip); Answer on B;
#      both are in the LiveKit room with audio published; A hangs up and B's call ends. Prints the timings.
#   2. TC-VOIP-002: A sends B a Flash message; B shows it and keeps it; the server deletes it once fetched.
#   3. TC-VOIP-003: A's clip is transcoded; B gets it for the app call, and again from the private bucket lookup when
#      A's number rings B as a normal phone call.
#
# Usage: scripts/e2e/voip.sh <applicationId> <serial-A> <serial-B> <out-dir>
# Needs: the stack up as for scripts/e2e/report_to_block.sh, with LIVEKIT_URL, LIVEKIT_NODE_IP and
# MINIO_PUBLIC_ENDPOINT set to how the emulators reach the host (10.0.2.2, or 127.0.0.1 with adb reverse), the app
# installed on both emulators (report_to_block.sh does it), ffmpeg and python3 on the host.
set -euo pipefail

app_id="${1:?usage: voip.sh <applicationId> <serial-A> <serial-B> <out-dir>}"
device_a="${2:?missing serial A}"
device_b="${3:?missing serial B}"
out="${4:?missing out dir}"
compose_file="${COMPOSE_FILE:-infra/docker/compose.yml}"
receiver="$app_id/io.github.jeshiomurmu.opencaller.qa.QaCommandReceiver"
main_activity="$app_id/io.github.jeshiomurmu.opencaller.MainActivity"
# Valid North American numbers in the 555-01xx range reserved for fiction (not used by the other scripts).
caller="+12015550190"
callee="+12015550191"
sink_port=8097
sink_log="$out/push-sink.jsonl"
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
# scripts/e2e/ci.sh stops a script that runs too long: print what the phones and the stack were doing.
trap 'die "stopped after the time limit in scripts/e2e/ci.sh"' TERM
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

sign_in() {
  local device="$1" number="$2" code
  qa_ok "$device" signin-start --es number "$number" > /dev/null
  code="$(latest_code "$number")" || die "no development code for $number in the server log"
  qa_ok "$device" signin-verify --es number "$number" --es code "$code" > /dev/null
}

# `am start -W` waits for the launch to report; once it never did and the test sat there for 19 minutes (run
# 42790801180). Give it 30 s; the checks after it show whether the app really came up.
foreground() {
  adb -s "$1" shell input keyevent KEYCODE_HOME
  timeout 30 adb -s "$1" shell am start -W -n "$main_activity" > /dev/null ||
    echo "::warning::$1: the launch of the app didn't report within 30 s"
}

# log_time <device> <pattern>: epoch milliseconds of the first log line matching <pattern> since the last `logcat -c`.
log_time() {
  local line
  line="$(adb -s "$1" logcat -d -v epoch | tr -d '\r' | grep -E "$2" | sed -n 1p || true)"
  [[ -n "$line" ]] || return 1
  awk '{ split($1, t, "."); printf "%d%03d\n", t[1], substr(t[2] "000", 1, 3) }' <<< "$line"
}

# wait_log <device> <pattern> <seconds>: waits for a log line; echoes its time.
wait_log() {
  local deadline=$(($(date +%s) + $3))
  while [[ "$(date +%s)" -lt "$deadline" ]]; do
    if log_time "$1" "$2"; then return 0; fi
    sleep 0.5
  done
  return 1
}

dump() { adb -s "$1" shell uiautomator dump /sdcard/window.xml > /dev/null 2>&1 && adb -s "$1" shell cat /sdcard/window.xml; }

# tap <device> <label>: taps the first node whose text or content description is <label>.
tap() {
  local window bounds x1 y1 x2 y2
  window="$(dump "$1")" || true
  bounds="$(grep -oE "(text|content-desc)=\"$2\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" <<< "$window" |
    sed -n 1p | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' || true)"
  [[ -n "$bounds" ]] || return 1
  read -r x1 y1 x2 y2 <<< "$(sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/' <<< "$bounds")"
  adb -s "$1" shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

tap_retry() {
  for _ in $(seq 1 "${3:-20}"); do
    if tap "$1" "$2"; then return 0; fi
    sleep 1
  done
  return 1
}

screenshot() { adb -s "$1" exec-out screencap -p > "$out/$2.png" || true; }

pushes() { grep -c . "$sink_log" || true; }

# wait_push <count>: waits until the sink holds <count> pushes; echoes the last one.
wait_push() {
  for _ in $(seq 1 40); do
    if [[ "$(pushes)" -ge "$1" ]]; then
      sed -n "${1}p" "$sink_log"
      return 0
    fi
    sleep 0.5
  done
  return 1
}

# check_push <case> <json> <ttl> <urgency>: the push is Web Push ciphertext and carries nothing readable.
check_push() {
  local tc="$1" push="$2" summary
  summary="$(python3 -c '
import json, sys
p = json.loads(sys.argv[1]); h = p["headers"]
print(h.get("Content-Encoding"), h.get("TTL"), h.get("Urgency"), h.get("Authorization"), p["length"], p["plain"] is None)
' "$push")"
  read -r encoding ttl urgency auth length opaque <<< "$summary"
  if [[ "$encoding" == aes128gcm && "$ttl" == "$3" && "$urgency" == "$4" && "$auth" == vapid && "$opaque" == True && "$length" -gt 86 ]]; then
    pass "$tc" "push to the callee's endpoint: aes128gcm ciphertext ($length bytes), TTL $ttl, urgency $urgency, VAPID"
  else
    fail "$tc" "push to the callee's endpoint looked wrong: $push"
  fi
}

diagnostics() {
  for device in "$device_a" "$device_b"; do
    adb -s "$device" logcat -d -v threadtime > "$out/logcat-$device.txt" 2> /dev/null || true
    echo "--- $device: the app's call lines, LiveKit and crashes"
    grep -E ' OpenCaller: |LiveKit|livekit|FATAL EXCEPTION| E AndroidRuntime|UnifiedPush' "$out/logcat-$device.txt" |
      tail -n 60 || true
    echo "--- $device: Telecom (last lines)"
    grep -E ' Telecom ' "$out/logcat-$device.txt" | grep -vE 'CallerInfo|carmode|CarMode|isUserKeyPresent|PhoneAccountRegistrar|\tat ' |
      tail -n 25 | cut -c1-240 || true
  done
  compose logs --no-color livekit > "$out/livekit.log" 2>&1 || true
  echo "--- LiveKit"
  tail -n 40 "$out/livekit.log" || true
  echo "--- pushes received by the sink"
  cat "$sink_log" || true
}

# --- Set-up -------------------------------------------------------------------------------------------------------
server_id="$(compose ps -q server)"
gateway="$(docker inspect "$server_id" -f '{{range .NetworkSettings.Networks}}{{.Gateway}} {{end}}' | awk '{print $1}')"
[[ -n "$gateway" ]] || die "no gateway address for the server's network"
python3 scripts/e2e/push_sink.py "$sink_port" "$sink_log" &
sink_pid=$!
trap 'kill "$sink_pid" 2> /dev/null || true' EXIT
# Bounded: without a time limit one probe can wait ~2 minutes for a dropped connection, and 20 of them took the
# job's whole hour (PR #20 run 42776465919).
sink_up=""
for _ in $(seq 1 20); do
  if compose exec -T server sh -c "curl -s -o /dev/null --connect-timeout 2 -m 5 -X POST http://$gateway:$sink_port/probe"; then
    sink_up=1
    break
  fi
  sleep 0.5
done
[[ -n "$sink_up" ]] || die "the server can't reach the push sink at $gateway:$sink_port"
: > "$sink_log"

for device in "$device_a" "$device_b"; do
  adb -s "$device" shell pm grant "$app_id" android.permission.RECORD_AUDIO
  adb -s "$device" logcat -c
done
sign_in "$device_a" "$caller"
sign_in "$device_b" "$callee"
foreground "$device_a"
foreground "$device_b"
features="$(qa_ok "$device_b" voip-features)"
[[ "$features" == "ok appCalls=true flash=true webPush=true" ]] || die "the server doesn't offer app calls: $features"
qa_ok "$device_b" push-endpoint --es url "http://$gateway:$sink_port/push/callee" > /dev/null
# B's default network decides whether clips download (Wi-Fi only unless the user allows mobile data).
connectivity="$(adb -s "$device_b" shell dumpsys connectivity | tr -d '\r')"
active="$(grep -oE 'Active default network: [0-9]+' <<< "$connectivity" | grep -oE '[0-9]+$' | sed -n 1p)"
# The dump differs between versions ("ni{[type: WIFI[]…" on API 29, "ni{WIFI CONNECTED…" on API 36).
b_network="$(grep -E "NetworkAgentInfo\{.*network\{$active\}" <<< "$connectivity" | grep -oE 'WIFI|MOBILE' |
  sed -n 1p | tr 'A-Z' 'a-z' || true)"
[[ -n "$b_network" ]] || b_network=wifi
echo "A = $device_a ($caller), B = $device_b ($callee, on $b_network); B's push endpoint is the sink at $gateway:$sink_port"

# --- TC-VOIP-003 (upload): A's clip goes through the real transcoder ---------------------------------------------
ffmpeg -loglevel error -y -f lavfi -i testsrc=size=640x480:rate=30 -t 4 -pix_fmt yuv420p -c:v libx264 "$out/clip.mp4"
adb -s "$device_a" shell -T "run-as $app_id sh -c 'cat > files/qa-clip.mp4'" < "$out/clip.mp4"
qa_ok "$device_a" clip-upload --es file qa-clip.mp4
video=""
for _ in $(seq 1 60); do
  status="$(qa_ok "$device_a" clip-status)"
  video="$(sed -n 's/^ok video=\([0-9a-f-]\{36\}\) .*/\1/p' <<< "$status")"
  if [[ -n "$video" ]]; then break; fi
  sleep 1
done
if [[ -n "$video" ]]; then
  pass TC-VOIP-003 "A's clip was uploaded and transcoded ($video)"
else
  fail TC-VOIP-003 "A's clip wasn't published: $status"
fi

# --- TC-VOIP-001: an app call -------------------------------------------------------------------------------------
for device in "$device_a" "$device_b"; do adb -s "$device" logcat -c; done
foreground "$device_a"
adb -s "$device_b" shell input keyevent KEYCODE_HOME
qa_ok "$device_a" app-call --es number "$callee" > /dev/null
t_out="$(wait_log "$device_a" 'App call (RINGING_OUT|ENDED)' 20)" || die "A's call didn't start"
if adb -s "$device_a" logcat -d | grep -q 'App call ENDED'; then die "A's call ended at once"; fi
t_dial="$(log_time "$device_a" 'App call CONNECTING')"
pass TC-VOIP-001 "A called: invite created and A joined the room in $((t_out - t_dial)) ms"
screenshot "$device_a" voip-a-ringing-out

push="$(wait_push 1)" || die "the server sent B no push for the invite"
check_push TC-VOIP-001 "$push" 45 high

# B opens the app (a missed push): the invite comes from GET /v1/notifications.
foreground "$device_b"
wait_log "$device_b" 'App call RINGING_IN' 20 > /dev/null || die "B didn't ring after opening the app"
pass TC-VOIP-001 "B rang after opening the app"
sleep 2
screenshot "$device_b" voip-b-ringing
notifications="$(adb -s "$device_b" shell dumpsys notification --noredact | tr -d '\r')"
if grep -q "channel=app_call_incoming" <<< "$notifications" || grep -q "app_call_incoming" <<< "$notifications"; then
  pass TC-VOIP-001 "B shows the incoming app-call notification"
else
  fail TC-VOIP-001 "no incoming app-call notification on B"
fi
telecom="$(adb -s "$device_b" shell dumpsys telecom | tr -d '\r')"
if grep -qiE "$app_id.*(SELF_MANAGED|selfManaged)|isSelfManaged.*true|SelfManaged" <<< "$telecom"; then
  pass TC-VOIP-001 "Telecom holds B's call as a self-managed call"
else
  fail TC-VOIP-001 "Telecom shows no self-managed call on B"
  grep -iE "call|$app_id" <<< "$telecom" | head -n 40 || true
fi
if [[ -n "$video" && "$b_network" == wifi ]]; then
  if wait_log "$device_b" 'App call clip shown' 15 > /dev/null; then
    pass TC-VOIP-003 "B shows A's clip while the app call rings (Wi-Fi)"
  else
    fail TC-VOIP-003 "B didn't show A's clip while ringing"
  fi
elif [[ -n "$video" ]]; then
  if wait_log "$device_b" 'App call clip (shown|not downloaded)' 15 > /dev/null &&
    adb -s "$device_b" logcat -d | grep -q 'App call clip not downloaded'; then
    pass TC-VOIP-003 "on mobile data B didn't download A's clip (Wi-Fi only by default)"
  else
    fail TC-VOIP-003 "on mobile data B downloaded A's clip although clips are Wi-Fi only by default"
  fi
fi

# The heads-up notification isn't the active window, which is all uiautomator dumps: answer from the shade.
adb -s "$device_b" shell cmd statusbar expand-notifications
if ! tap_retry "$device_b" Answer 8; then
  dump "$device_b" > "$out/b-no-answer.xml" || true
  echo "B's screen: $(grep -oE '(text|content-desc)="[^"]+"' "$out/b-no-answer.xml" | sort -u | tr '\n' ' ')"
  die "no Answer button on B"
fi
wait_log "$device_b" 'App call (ACTIVE|ENDED)' 30 > /dev/null || true
wait_log "$device_a" 'App call (ACTIVE|ENDED)' 30 > /dev/null || true
if adb -s "$device_b" logcat -d | grep -q 'App call ACTIVE' && adb -s "$device_a" logcat -d | grep -q 'App call ACTIVE'; then
  # Each phone's own clock; A's figure compares the two emulators' clocks (both set from the host).
  t_answer="$(log_time "$device_b" 'App call ANSWERING')"
  t_active_b="$(log_time "$device_b" 'App call ACTIVE')"
  t_active_a="$(log_time "$device_a" 'App call ACTIVE')"
  pass TC-VOIP-001 "connected: B $((t_active_b - t_answer)) ms and A about $((t_active_a - t_answer)) ms after Answer"
else
  fail TC-VOIP-001 "the call didn't connect on both phones"
fi
screenshot "$device_b" voip-b-active
screenshot "$device_a" voip-a-active
compose logs --no-color livekit > "$out/livekit.log" 2>&1 || true
tracks="$(grep -ciE 'mediaTrack published|track published' "$out/livekit.log" || true)"
if [[ "$tracks" -ge 2 ]]; then
  pass TC-VOIP-001 "LiveKit: both phones published their microphone ($tracks published-track lines)"
else
  fail TC-VOIP-001 "LiveKit logged $tracks published tracks (expected 2)"
fi
state="$(sql "select state from call_invites order by created_at desc limit 1")"
[[ "$state" == accepted ]] && pass TC-VOIP-001 "server: invite accepted" || fail TC-VOIP-001 "server: invite state $state"

sleep 5
if adb -s "$device_a" logcat -d | grep -q 'App call ENDED' || adb -s "$device_b" logcat -d | grep -q 'App call ENDED'; then
  fail TC-VOIP-001 "the call dropped within 5 seconds"
else
  pass TC-VOIP-001 "the call stayed up for 5 seconds"
fi

tap_retry "$device_a" "Hang up" || fail TC-VOIP-001 "no Hang up button on A"
if wait_log "$device_b" 'App call ENDED' 15 > /dev/null; then
  pass TC-VOIP-001 "A hung up and B's call ended ($(adb -s "$device_b" logcat -d | grep -o 'App call ENDED.*' | sed -n 1p | tr -d '\r'))"
else
  fail TC-VOIP-001 "B's call didn't end after A hung up"
fi
sleep 2
state="$(sql "select state from call_invites order by created_at desc limit 1")"
[[ "$state" == ended ]] && pass TC-VOIP-001 "server: invite ended" || fail TC-VOIP-001 "server: invite state $state after hang-up"

# --- TC-VOIP-002: a Flash message ---------------------------------------------------------------------------------
before="$(pushes)"
adb -s "$device_b" shell input keyevent KEYCODE_HOME
qa_ok "$device_a" flash --es number "$callee" --es kind on_my_way > /dev/null
push="$(wait_push $((before + 1)))" || die "the server sent B no push for the Flash message"
check_push TC-VOIP-002 "$push" 86400 normal
foreground "$device_b"
received=""
for _ in $(seq 1 20); do
  received="$(qa_ok "$device_b" flash-count)"
  if [[ "$received" == "ok received=1"* ]]; then break; fi
  sleep 0.5
done
[[ "$received" == "ok received=1"* ]] && pass TC-VOIP-002 "B kept the Flash message ($received)" || fail TC-VOIP-002 "B: $received"
sleep 1
notifications="$(adb -s "$device_b" shell dumpsys notification --noredact | tr -d '\r')"
if grep -q "On my way" <<< "$notifications"; then
  pass TC-VOIP-002 "B shows the Flash notification (\"On my way\")"
else
  fail TC-VOIP-002 "no Flash notification with \"On my way\" on B"
fi
screenshot "$device_b" voip-b-flash
left="$(sql "select count(*) from flash_messages")"
[[ "$left" == 0 ]] && pass TC-VOIP-002 "server: the message was deleted once fetched" || fail TC-VOIP-002 "server still holds $left Flash messages"

# --- TC-VOIP-003 (normal call): A's number rings B; the private lookup brings A's clip -----------------------------
if [[ -n "$video" ]]; then
  if [[ "$b_network" != wifi ]]; then qa_ok "$device_b" clips-mobile --ez allowed true > /dev/null; fi
  adb -s "$device_b" shell run-as "$app_id" rm -rf cache/video-clips
  # A lookup that runs past its 800 ms budget is cancelled and brings no clip (issue 139); on a slow emulator the
  # first call can hit that, so a second call follows once, and the output says so.
  found=""
  for attempt in 1 2; do
    adb -s "$device_b" logcat -c
    adb -s "$device_b" emu gsm call "$caller" > /dev/null
    for _ in $(seq 1 30); do
      if adb -s "$device_b" shell run-as "$app_id" ls cache/video-clips 2> /dev/null | tr -d '\r' | grep -q "^$video.mp4$"; then
        found=yes
        break
      fi
      sleep 0.5
    done
    sleep 1
    adb -s "$device_b" emu gsm cancel "$caller" > /dev/null || true
    sleep 2
    if [[ -n "$found" ]] || ! adb -s "$device_b" logcat -d | grep -q 'Screening exceeded'; then break; fi
    echo "B's screening ran past its budget (the lookup was cancelled); calling once more"
  done
  if [[ -n "$found" ]]; then
    pass TC-VOIP-003 "a normal call from A's number brought A's clip to B through the bucket lookup ($b_network, call $attempt)"
  else
    fail TC-VOIP-003 "B didn't fetch A's clip for a normal call from A's number"
  fi
fi

if [[ "$failures" -gt 0 ]]; then
  diagnostics
  echo "TC-VOIP (emulators): $failures check(s) failed"
  exit 1
fi
diagnostics > /dev/null
# Nothing failed; still worth reading: the app's warnings and errors, and LiveKit's.
for device in "$device_a" "$device_b"; do
  echo "--- $device: the app's warnings and errors"
  grep -E ' [WE] OpenCaller: | E AndroidRuntime' "$out/logcat-$device.txt" | sort -u -k5 | tail -n 30 || true
done
echo "--- LiveKit warnings and errors"
grep -E 'WARN|ERROR' "$out/livekit.log" | cut -c1-300 | tail -n 20 || true
echo "TC-VOIP (emulators): all checks passed"
