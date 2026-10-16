#!/usr/bin/env bash
# Installs a debug APK on the running emulator, launches MainActivity and checks that the app
# stays alive and shows the "OpenCaller" text. Used by the `emulator` job in .github/workflows/ci.yml.
#
# Usage: scripts/ci/emulator-smoke.sh <apk> <applicationId> <screenshot.png>
set -euo pipefail

apk="${1:?usage: emulator-smoke.sh <apk> <applicationId> <screenshot.png>}"
app_id="${2:?missing applicationId}"
screenshot="${3:?missing screenshot path}"
activity="io.github.jeshiomurmu.opencaller.MainActivity"

adb wait-for-device
adb install -r "$apk"
adb logcat -c

echo "Launching $app_id/$activity"
adb shell am start -W -n "$app_id/$activity"

found=""
for _ in $(seq 1 30); do
  if adb shell uiautomator dump /sdcard/window.xml > /dev/null 2>&1 \
    && adb shell cat /sdcard/window.xml | grep -q 'text="OpenCaller"'; then
    found="yes"
    break
  fi
  sleep 2
done

mkdir -p "$(dirname "$screenshot")"
adb exec-out screencap -p > "$screenshot"

if adb logcat -d -b crash | grep -q "$app_id"; then
  echo "::error::$app_id crashed"
  adb logcat -d -b crash
  exit 1
fi
if ! adb shell pidof "$app_id" > /dev/null; then
  echo "::error::$app_id is not running after launch"
  exit 1
fi
if [[ -z "$found" ]]; then
  echo "::error::'OpenCaller' text not found on screen"
  adb shell cat /sdcard/window.xml || true
  exit 1
fi

adb shell dumpsys activity activities | grep -E "ResumedActivity" || true
echo "OK: $app_id installed, launched and shows 'OpenCaller'"
