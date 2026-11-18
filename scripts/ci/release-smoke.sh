#!/usr/bin/env bash
# Runs the minified (R8) foss release build on the emulator (docs/phase.md issue 59): signs the unsigned release APK
# with the debug key, installs it next to the debug build, goes through onboarding with Not now, walks the five tabs and
# fails on any crash. R8 problems (a stripped class, a renamed field read by reflection) show up as a crash here.
#
# Usage: release-smoke.sh <unsigned release APK> <screenshot dir>
set -euo pipefail

unsigned="${1:?usage: release-smoke.sh <unsigned release APK> <screenshot dir>}"
out="${2:?missing screenshot dir}"
app_id="io.github.jeshiomurmu.opencaller"
activity="io.github.jeshiomurmu.opencaller.MainActivity"
mkdir -p "$out"

apksigner="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)/apksigner"
signed="$(mktemp --suffix=.apk)"
# The debug keystore Gradle created for the debug build; a release build signed with it is fine for a test install.
"$apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --ks-key-alias androiddebugkey \
  --key-pass pass:android --out "$signed" "$unsigned"

dump() {
  adb shell uiautomator dump /sdcard/window.xml > /dev/null 2>&1 && adb shell cat /sdcard/window.xml
}

on_screen() {
  dump | grep -qF "text=\"$1\""
}

wait_for_text() {
  for _ in $(seq 1 30); do
    if on_screen "$1"; then return 0; fi
    assert_no_crash
    sleep 1
  done
  echo "::error::release build: '$1' not found on screen"
  dump || true
  exit 1
}

tap_text() {
  local bounds
  bounds="$(dump | grep -oE "text=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' || true)"
  if [[ -z "$bounds" ]]; then
    echo "::error::release build: no on-screen node with text '$1'"
    exit 1
  fi
  read -r x1 y1 x2 y2 <<< "$(sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/' <<< "$bounds")"
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

assert_no_crash() {
  local crash
  crash="$(adb logcat -d -b crash | grep -A 30 "Process: $app_id" || true)"
  if [[ -n "$crash" ]]; then
    echo "::error::the release (R8) build crashed:"
    echo "$crash"
    exit 1
  fi
}

adb uninstall "$app_id" > /dev/null 2>&1 || true
adb install -r "$signed"
adb logcat -c
adb logcat -b crash -c
adb shell am start -W -n "$app_id/$activity" > /dev/null

wait_for_text "Get started"
adb exec-out screencap -p > "$out/release-welcome.png"
tap_text "Get started"
wait_for_text "Your phone, your data"
tap_text "Continue"

# Every permission and role step offers Not now; the last one offers Finish.
for _ in $(seq 1 12); do
  if on_screen "Finish"; then break; fi
  assert_no_crash
  if on_screen "Not now"; then tap_text "Not now"; fi
  sleep 1
done
tap_text "Finish"
wait_for_text "Calls"

for tab in Calls Messages Contacts Protection Me; do
  tap_text "$tab"
  sleep 2
  assert_no_crash
  adb exec-out screencap -p > "$out/release-$tab.png"
done

if ! adb shell pidof "$app_id" > /dev/null; then
  echo "::error::the release (R8) build is not running after the walk"
  exit 1
fi
assert_no_crash
echo "OK: the minified foss release build went through onboarding and all five tabs without a crash"
adb uninstall "$app_id" > /dev/null
