#!/usr/bin/env bash
# Gives the emulator's Java processes a real heap before the tests (issue 32). On the API 29 google_apis image
# system_server died of OutOfMemoryError mid-test twice (runs 38891529407 and 42628889411) with "growth limit
# 16777216", while `getprop dalvik.vm.heapsize` reports the AVD's heap-size (576m in run 42647136223): the emulator
# sets that property after zygote has started, so system_server keeps the image's 16 MB default.
#
# So on API 29 and lower this restarts the Android framework once (as root; the google_apis images allow
# `adb root`), and zygote and system_server start again with the current properties. It also raises
# dalvik.vm.heapsize to 512m and dalvik.vm.heapgrowthlimit to 256m where they are smaller or unset. Newer images are
# restarted only when they report a heap below 192 MB.
#
# Usage: scripts/ci/emulator-heap.sh   (with one emulator attached)
set -euo pipefail

megabytes() {
  local value="${1%[mM]}"
  [[ "$value" =~ ^[0-9]+$ ]] && echo "$value" || echo 0
}

heap="$(adb shell getprop dalvik.vm.heapsize | tr -d '\r')"
growth="$(adb shell getprop dalvik.vm.heapgrowthlimit | tr -d '\r')"
sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
echo "Emulator heap before: API $sdk, dalvik.vm.heapsize=${heap:-unset} dalvik.vm.heapgrowthlimit=${growth:-unset}"
if [[ "$sdk" -gt 29 && "$(megabytes "$heap")" -ge 192 ]]; then
  echo "Heap is large enough; nothing to change"
  exit 0
fi

# Right after boot adbd can still be restarting, and `adb root` then fails with "unable to connect for root: closed"
# (run 42790056580). Retry until the shell is root.
root=""
for _ in $(seq 1 10); do
  adb root > /dev/null 2>&1 || true
  adb wait-for-device
  root="$(adb shell id -u 2> /dev/null | tr -d '\r' || true)"
  if [[ "$root" == 0 ]]; then break; fi
  sleep 2
done
[[ "$root" == 0 ]] || { echo "::error::adb root didn't take on the emulator"; exit 1; }
if [[ "$(megabytes "$heap")" -lt 512 ]]; then adb shell setprop dalvik.vm.heapsize 512m; fi
if [[ "$(megabytes "$growth")" -lt 256 ]]; then adb shell setprop dalvik.vm.heapgrowthlimit 256m; fi
# Restart the framework (zygote and system_server) so the new limits apply; boot completes again afterwards.
adb shell setprop sys.boot_completed 0 || true
adb shell stop
# Wait until system_server is gone, so the check below can't see the old one.
for _ in $(seq 1 30); do
  adb shell pidof system_server > /dev/null 2>&1 || break
  sleep 1
done
adb shell start
booted=""
for _ in $(seq 1 90); do
  booted="$(adb shell getprop sys.boot_completed 2> /dev/null | tr -d '\r' || true)"
  if [[ "$booted" == 1 ]] && adb shell pm path android > /dev/null 2>&1; then break; fi
  booted=""
  sleep 2
done
[[ "$booted" == 1 ]] || { echo "::error::the framework didn't come back after the heap change"; exit 1; }
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true
adb unroot > /dev/null 2>&1 || true
adb wait-for-device
echo "Emulator heap after: dalvik.vm.heapsize=$(adb shell getprop dalvik.vm.heapsize | tr -d '\r')" \
  "dalvik.vm.heapgrowthlimit=$(adb shell getprop dalvik.vm.heapgrowthlimit | tr -d '\r')"
