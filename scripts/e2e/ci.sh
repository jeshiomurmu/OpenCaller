#!/usr/bin/env bash
# CI wrapper for TC-E2E-001 and the emulator run of TC-VOIP-001…003: android-emulator-runner has started device A
# (emulator-5554); this starts device B (emulator-5556) from the same system image, runs
# scripts/e2e/report_to_block.sh and scripts/e2e/voip.sh, and stops B again. Both emulators have audio (app calls
# record from the emulated microphone).
#
# Usage: scripts/e2e/ci.sh <api-level> <debug-apk> <applicationId> <out-dir>
set -euo pipefail

api="${1:?usage: ci.sh <api-level> <debug-apk> <applicationId> <out-dir>}"
apk="${2:?missing apk}"
app_id="${3:?missing applicationId}"
out="${4:?missing out dir}"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?ANDROID_HOME is not set}}"
image="system-images;android-$api;google_apis;x86_64"
device_b="emulator-5556"
mkdir -p "$out"

avdmanager="$(find "$sdk/cmdline-tools" -path '*/bin/avdmanager' | sort -V | tail -1)"
[[ -x "$avdmanager" ]] || { echo "::error::avdmanager not found under $sdk/cmdline-tools"; exit 1; }
echo no | "$avdmanager" create avd --force -n e2e-b -k "$image" > /dev/null

"$sdk/emulator/emulator" -avd e2e-b -port 5556 -no-window -gpu swiftshader_indirect -no-snapshot -no-boot-anim \
  -memory 4096 -camera-back none > "$out/emulator-b.log" 2>&1 &
emulator_pid=$!
trap 'adb -s "$device_b" emu kill > /dev/null 2>&1 || kill "$emulator_pid" 2> /dev/null || true' EXIT

adb -s "$device_b" wait-for-device
booted=""
for _ in $(seq 1 120); do
  booted="$(adb -s "$device_b" shell getprop sys.boot_completed 2> /dev/null | tr -d '\r' || true)"
  if [[ "$booted" == 1 ]]; then break; fi
  sleep 5
done
[[ "$booted" == 1 ]] || { echo "::error::device B didn't boot"; tail -n 100 "$out/emulator-b.log"; exit 1; }
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb -s "$device_b" shell settings put global "$scale" 0
done
adb devices

# On a failure: can each emulator reach the host, and why did the app's requests fail?
network_report() {
  for device in emulator-5554 "$device_b"; do
    echo "--- $device: network"
    adb -s "$device" shell ip route 2> /dev/null | tr -d '\r' || true
    adb -s "$device" shell ping -c 1 -W 2 10.0.2.2 2> /dev/null | tr -d '\r' | grep -E 'bytes from|packet loss' || true
    for target in 10.0.2.2:8080 10.0.2.2:8097; do
      adb -s "$device" shell "nc -z -w 3 ${target%:*} ${target#*:} && echo '$target open' || echo '$target not reachable'" 2> /dev/null |
        tr -d '\r' || true
    done
    adb -s "$device" shell dumpsys connectivity 2> /dev/null | tr -d '\r' | grep -E 'Active default network|NetworkAgentInfo\{' | head -n 3 || true
    echo "--- $device: power and firewall"
    adb -s "$device" shell dumpsys deviceidle 2> /dev/null | tr -d '\r' | grep -E 'mState=|mLightState=|mScreenOn|mCharging' | head -n 5 || true
    adb -s "$device" shell dumpsys netpolicy 2> /dev/null | tr -d '\r' | grep -iE 'restrict|power save|device idle' | head -n 8 || true
    adb -s "$device" root > /dev/null 2>&1 && sleep 2
    adb -s "$device" shell iptables -S 2> /dev/null | tr -d '\r' | grep -E ' -j (DROP|REJECT)|^-P' | head -n 20 || true
    adb -s "$device" shell iptables -L -v -n 2> /dev/null | tr -d '\r' | grep -E '^Chain (fw_|bw_|INPUT|OUTPUT)|DROP|REJECT' | grep -vE ' 0 +0 ' | head -n 25 || true
    echo "--- $device: the app's network warnings"
    adb -s "$device" logcat -d -v threadtime 2> /dev/null | tr -d '\r' | grep -E ' [WE] OpenCaller' | grep -A 12 'Request failed' | head -n 40 || true
  done
}

status=0
scripts/e2e/report_to_block.sh "$apk" "$app_id" emulator-5554 "$device_b" || status=1
scripts/e2e/voip.sh "$app_id" emulator-5554 "$device_b" "$out" || status=1
if [[ "$status" -ne 0 ]]; then network_report; fi
exit "$status"
