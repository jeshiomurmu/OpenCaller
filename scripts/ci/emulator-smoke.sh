#!/usr/bin/env bash
# Installs a debug APK on the running emulator and walks the app shell: in light and in dark mode it opens
# every bottom-bar tab, checks the tab's placeholder text is shown and takes a screenshot. Fails on a crash,
# a missing tab/text, or if the screenshots don't look light/dark. Used by the `emulator` job in
# .github/workflows/ci.yml.
#
# Usage: scripts/ci/emulator-smoke.sh <apk> <applicationId> <screenshot-dir>
set -euo pipefail

apk="${1:?usage: emulator-smoke.sh <apk> <applicationId> <screenshot-dir>}"
app_id="${2:?missing applicationId}"
out="${3:?missing screenshot dir}"
activity="io.github.jeshiomurmu.opencaller.MainActivity"
here="$(cd "$(dirname "$0")" && pwd)"

# label|placeholder text, in bottom-bar order (app/src/main/res/values/strings.xml).
tabs=(
  "Calls|Your call history will appear here."
  "Messages|Your messages will appear here."
  "Contacts|Your contacts will appear here."
  "Protection|Spam protection and blocking rules will appear here."
  "Me|Your profile and settings will appear here."
)

dump() {
  adb shell uiautomator dump /sdcard/window.xml > /dev/null 2>&1 && adb shell cat /sdcard/window.xml
}

wait_for_text() {
  for _ in $(seq 1 30); do
    if dump | grep -qF "text=\"$1\""; then return 0; fi
    sleep 1
  done
  echo "::error::'$1' not found on screen"
  dump || true
  return 1
}

tap_text() {
  local bounds
  bounds="$(dump | grep -oE "text=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' || true)"
  if [[ -z "$bounds" ]]; then
    echo "::error::no on-screen node with text '$1'"
    return 1
  fi
  read -r x1 y1 x2 y2 <<< "$(sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/' <<< "$bounds")"
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

night_mode_is() {
  adb shell cmd uimode night | grep -q "Night mode: $1"
}

wait_for_boot() {
  adb wait-for-device
  until [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == "1" ]]; do sleep 2; done
}

# Switches system night mode to yes|no. On the API 29 emulator `cmd uimode night` from the unprivileged shell
# was observed to have no effect, and a single settings write + reboot only worked in some runs, so the
# script tries, in order: the plain shell command, the same command as root (google_apis images allow `adb root`), and
# finally the persisted ui_night_mode setting plus a reboot, retrying the command once the system has booted.
set_night_mode() {
  local setting
  setting="$([[ "$1" == "yes" ]] && echo 2 || echo 1)"
  adb shell cmd uimode night "$1" > /dev/null
  if night_mode_is "$1"; then return 0; fi

  echo "cmd uimode night $1 had no effect as shell; retrying as root"
  if adb root; then
    sleep 2
    wait_for_boot
    adb shell cmd uimode night "$1" > /dev/null
    if night_mode_is "$1"; then return 0; fi
  fi

  echo "cmd uimode night $1 had no effect as root; setting ui_night_mode and rebooting"
  adb shell settings put secure ui_night_mode "$setting"
  adb shell settings get secure ui_night_mode
  adb shell sync
  sleep 3
  adb reboot
  wait_for_boot
  adb root || true
  sleep 2
  wait_for_boot
  adb shell input keyevent KEYCODE_WAKEUP
  adb shell wm dismiss-keyguard || true
  for _ in $(seq 1 10); do
    if night_mode_is "$1"; then return 0; fi
    adb shell cmd uimode night "$1" > /dev/null || true
    sleep 3
  done
  echo "::error::could not switch system night mode to $1"
  adb shell cmd uimode night || true
  adb shell settings get secure ui_night_mode || true
  exit 1
}

assert_alive() {
  if adb logcat -d -b crash | grep -q "$app_id"; then
    echo "::error::$app_id crashed"
    adb logcat -d -b crash
    exit 1
  fi
  if ! adb shell pidof "$app_id" > /dev/null; then
    echo "::error::$app_id is not running"
    exit 1
  fi
}

mkdir -p "$out"
adb wait-for-device
adb install -r "$apk"

for mode in no yes; do
  theme=$([[ "$mode" == "yes" ]] && echo dark || echo light)
  set_night_mode "$mode"
  adb shell am force-stop "$app_id"
  adb logcat -c
  adb shell am start -W -n "$app_id/$activity"
  wait_for_text "Calls"
  for tab in "${tabs[@]}"; do
    label="${tab%%|*}"
    placeholder="${tab#*|}"
    tap_text "$label"
    wait_for_text "$placeholder"
    shot="$out/$theme-$label.png"
    adb exec-out screencap -p > "$shot"
    luma="$(python3 "$here/png_luma.py" "$shot")"
    echo "$theme / $label: shown, mean luminance $luma"
    if [[ "$theme" == "light" ]]; then
      python3 -c "import sys; sys.exit(0 if $luma > 0.6 else 1)" || { echo "::error::$label does not look light ($luma)"; exit 1; }
    else
      python3 -c "import sys; sys.exit(0 if $luma < 0.4 else 1)" || { echo "::error::$label does not look dark ($luma)"; exit 1; }
    fi
  done
  assert_alive
done

echo "OK: $app_id walked all ${#tabs[@]} tabs in light and dark mode"
