#!/usr/bin/env bash
# Gives the emulator's Java processes a real heap before the tests (issue 32). The API 29 google_apis image boots with
# dalvik.vm.heapsize = 16m whatever the AVD's heap-size says; system_server lifts its growth limit, so 16 MB is its
# whole heap, and it died of OutOfMemoryError mid-test twice (runs 38891529407 and 42628889411, both while a system
# service read a file into memory), taking the instrumented tests with it.
#
# When the limit is below 192 MB this sets dalvik.vm.heapsize = 512m and heapgrowthlimit = 256m as root (the
# google_apis images allow `adb root`) and restarts the Android framework, so system_server and every app start with
# the new limit. Images that already have a larger heap are left alone.
#
# Usage: scripts/ci/emulator-heap.sh   (with one emulator attached)
set -euo pipefail

megabytes() {
  local value="${1%[mM]}"
  [[ "$value" =~ ^[0-9]+$ ]] && echo "$value" || echo 0
}

heap="$(adb shell getprop dalvik.vm.heapsize | tr -d '\r')"
growth="$(adb shell getprop dalvik.vm.heapgrowthlimit | tr -d '\r')"
echo "Emulator heap before: dalvik.vm.heapsize=${heap:-unset} dalvik.vm.heapgrowthlimit=${growth:-unset}"
if [[ "$(megabytes "$heap")" -ge 192 ]]; then
  echo "Heap is large enough; nothing to change"
  exit 0
fi

adb root > /dev/null
adb wait-for-device
adb shell setprop dalvik.vm.heapsize 512m
adb shell setprop dalvik.vm.heapgrowthlimit 256m
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
