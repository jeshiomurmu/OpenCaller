#!/usr/bin/env bash
# Installs a debug APK on the running emulator and drives onboarding from outside the app (so it survives the
# app process being killed, which Android does when the call screening role changes on recent versions):
#   TC-ONB-008  kill the process at step 3; onboarding resumes at step 3
#   TC-ONB-001  grant the call screening role in the system dialog; onboarding moves on
#   TC-ONB-003  skip the optional default phone app; screening-only mode, Calls tab offers the phone app
#   TC-ONB-005  revoke the role from the shell; the degraded-mode banner appears when the app comes back
# Then, in light and in dark mode, it opens every bottom-bar tab, checks the tab's text and takes a screenshot.
# Fails on a crash, a missing text, or if the screenshots don't look light/dark. Used by the `emulator` job in
# .github/workflows/ci.yml.
#
# Usage: scripts/ci/emulator-smoke.sh <apk> <applicationId> <screenshot-dir>
set -euo pipefail

apk="${1:?usage: emulator-smoke.sh <apk> <applicationId> <screenshot-dir>}"
app_id="${2:?missing applicationId}"
out="${3:?missing screenshot dir}"
activity="io.github.jeshiomurmu.opencaller.MainActivity"
here="$(cd "$(dirname "$0")" && pwd)"

# label|text that must show on the tab, in bottom-bar order (app/src/main/res/values/strings.xml), escaped the
# way uiautomator dumps it (& becomes &amp;; apostrophes stay as they are).
tabs=(
  "Calls|Your call history will appear here."
  "Messages|Your messages will appear here."
  "Contacts|Your contacts will appear here."
  "Protection|Your rules"
  "Me|Permissions &amp; roles"
)
# docs/design.md §8 "Role lost" (app/src/main/res/values/strings.xml: banner_screening_off).
banner="OpenCaller can't screen calls right now. Turn call screening back on."
role="android.app.role.CALL_SCREENING"

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

assert_no_crash() {
  if adb logcat -d -b crash | grep -q "$app_id"; then
    echo "::error::$app_id crashed"
    adb logcat -d -b crash
    exit 1
  fi
}

assert_alive() {
  assert_no_crash
  if ! adb shell pidof "$app_id" > /dev/null; then
    echo "::error::$app_id is not running"
    exit 1
  fi
}

on_screen() {
  dump | grep -qF "text=\"$1\""
}

# wait_quietly <text> <seconds>: like wait_for_text, but only returns non-zero (no error, no dump).
wait_quietly() {
  for _ in $(seq 1 "$2"); do
    if on_screen "$1"; then return 0; fi
    sleep 1
  done
  return 1
}

# What was going on when the script failed: crashes, the window in front, and the app's, the role controller's and
# the activity manager's log lines.
report_failure() {
  local status=$?
  if [[ "$status" -ne 0 ]]; then
    echo "::group::Diagnostics (smoke script exited with $status)"
    adb logcat -d -b crash || true
    adb shell dumpsys window | tr -d '\r' | grep -E "mCurrentFocus|mFocusedApp" || true
    adb logcat -d | tr -d '\r' | grep -iE "$app_id|RoleController|RequestRole|ActivityTaskManager|AndroidRuntime|lowmemorykiller|Killing" |
      tail -n 200 || true
    echo "::endgroup::"
  fi
}
trap report_failure EXIT

relaunch() {
  adb shell input keyevent KEYCODE_HOME
  sleep 2
  adb shell am start -W -n "$app_id/$activity" > /dev/null
}

# TC-ONB-008: at step 3 the app goes to the background and its process is killed; relaunching must come back
# to step 3 (the step is stored in user settings).
check_process_death() {
  adb shell am start -W -n "$app_id/$activity" > /dev/null
  tap_text "Get started"
  wait_for_text "What leaves your phone"
  tap_text "Continue"
  wait_for_text "Step 3 of 5"
  adb shell input keyevent KEYCODE_HOME
  sleep 2
  adb shell am kill "$app_id"
  if adb shell pidof "$app_id" > /dev/null; then
    echo "::error::$app_id still running after am kill"
    exit 1
  fi
  adb shell am start -W -n "$app_id/$activity" > /dev/null
  wait_for_text "Step 3 of 5"
  echo "TC-ONB-008: resumed at step 3 after process death"
}

# TC-ONB-001: Continue on the explainer, then the system dialog. Newer dialogs list the candidate apps, so pick
# OpenCaller first. If Android restarts the app on the role change, onboarding resumes and Continue moves on.
grant_call_screening() {
  wait_for_text "Let OpenCaller check incoming calls"
  tap_text "Continue"
  # Once (run 38903662245, API 29) the app left the screen after this tap and the dialog never came; nothing was
  # logged then. A crash still fails here; otherwise say what was in front and ask once more from the app.
  if ! wait_quietly "Set as default" 15; then
    assert_no_crash
    echo "::warning::TC-ONB-001: no role dialog 15 s after Continue; in front: $(adb shell dumpsys window | tr -d '\r' | grep -m1 mCurrentFocus)"
    adb shell am start -W -n "$app_id/$activity" > /dev/null
    wait_for_text "Let OpenCaller check incoming calls"
    tap_text "Continue"
  fi
  wait_for_text "Set as default"
  if on_screen "OpenCaller"; then tap_text "OpenCaller"; fi
  tap_text "Set as default"
  for _ in $(seq 1 30); do
    if on_screen "Use your own names for your contacts"; then break; fi
    if on_screen "Let OpenCaller check incoming calls"; then tap_text "Continue" || true; fi
    sleep 1
  done
  wait_for_text "Use your own names for your contacts"
  echo "role holders: $(adb shell cmd role get-role-holders "$role" | tr -d '\r')"
  echo "TC-ONB-001: call screening role granted, onboarding moved on to contacts"
}

finish_with_not_now() {
  for _ in 1 2 3 4; do
    if on_screen "Finish"; then break; fi
    tap_text "Not now"
    sleep 1
  done
  tap_text "Finish"
  wait_for_text "Calls"
}

# TC-ONB-003: with call screening but no default phone app, the app runs in screening-only mode.
check_screening_only_mode() {
  wait_for_text "Set as default phone app"
  if on_screen "$banner"; then
    echo "::error::degraded-mode banner shown although OpenCaller holds the call screening role"
    exit 1
  fi
  echo "TC-ONB-003: screening-only mode, Calls tab offers the default phone app, no banner"
}

# TC-ONB-005: the user takes the role away in system settings; the banner shows when the app comes back.
check_role_revoked() {
  adb shell cmd role remove-role-holder "$role" "$app_id"
  relaunch
  wait_for_text "$banner"
  echo "TC-ONB-005: degraded-mode banner shown after the role was revoked"
}

mkdir -p "$out"
adb wait-for-device
adb install -r "$apk"
adb shell cmd role remove-role-holder "$role" "$app_id" || true
adb logcat -c
check_process_death
grant_call_screening
finish_with_not_now
check_screening_only_mode
check_role_revoked

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
