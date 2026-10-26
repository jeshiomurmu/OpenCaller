#!/usr/bin/env bash
# CI wrapper for TC-E2E-001: android-emulator-runner has started device A (emulator-5554); this starts device B
# (emulator-5556) from the same system image, runs scripts/e2e/report_to_block.sh and stops B again.
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

"$sdk/emulator/emulator" -avd e2e-b -port 5556 -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim \
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

scripts/e2e/report_to_block.sh "$apk" "$app_id" emulator-5554 "$device_b"
