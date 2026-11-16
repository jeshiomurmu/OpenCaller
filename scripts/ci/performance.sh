#!/usr/bin/env bash
# Phase 14 performance run on one emulator (API 33+; CI: API 36). Prints everything into the log, because the agent
# sandbox can't download workflow artifacts (docs/phase.md issue 111):
#   1. :app:generateBaselineProfile (benchmark/BaselineProfileGenerator) and the resulting profile, gzip + base64
#      between "== BASELINE PROFILE" markers, to be committed to app/src/main/generated/baselineProfiles;
#   2. Macrobenchmark (cold start without compilation and with the profile; one screening decision) and the
#      Microbenchmark of the screening engine, their JSON results;
#   3. a battery proxy: the release build installed fresh, the emulator unplugged and idle with the screen off for
#      IDLE_MINUTES, then the app's wakelocks, jobs, alarms and network from batterystats.
# Emulator numbers are indicative only; the reference numbers come from a phone (docs/testing.md, Performance).
set -uo pipefail

out=${1:?usage: performance.sh <output dir>}
idle_minutes=${IDLE_MINUTES:-30}
package=io.github.jeshiomurmu.opencaller
mkdir -p "$out"
cd android
failed=()

# What the device showed and said when a step failed: test results, the screen's view tree, the app's log.
diagnostics() {
  echo "::group::Diagnostics for $1"
  find benchmark/build/outputs/androidTest-results microbenchmark/build/outputs/androidTest-results \
    -name '*.xml' -exec cat {} + 2> /dev/null | grep -vE '^\s*<property ' | tail -n 200
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && adb shell cat /sdcard/ui.xml | \
    grep -oE '(text|resource-id|content-desc)="[^"]+"' | head -n 80
  adb logcat -d -b crash | tail -n 80
  adb logcat -d | grep -iE 'opencaller|TestRunner|Benchmark|AndroidRuntime' | tail -n 150
  echo "::endgroup::"
}

# Runs one step; a failure is reported and the next steps still run.
step() {
  local name=$1
  shift
  echo "== $name"
  if ! "$@"; then
    echo "::error::$name failed"
    failed+=("$name")
    diagnostics "$name"
  fi
}

step "1. Baseline Profile" ./gradlew --no-configuration-cache :app:generateBaselineProfile
profiles=app/src/main/generated/baselineProfiles
ls -l "$profiles" 2> /dev/null
for f in "$profiles"/*.txt; do
  [ -f "$f" ] || continue
  echo "$(basename "$f"): $(wc -l < "$f") rules"
  echo "== BASELINE PROFILE $(basename "$f") BEGIN"
  gzip -9c "$f" | base64 -w 120
  echo "== BASELINE PROFILE $(basename "$f") END"
done
[ -d "$profiles" ] && cp -r "$profiles" "../$out/"

step "2. Macrobenchmark (cold start, screening)" ./gradlew --no-configuration-cache \
  :benchmark:connectedFossBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
find benchmark/build/outputs -name '*benchmarkData.json' -print -exec cat {} \; -exec cp {} "../$out/" \;

# The Microbenchmark refuses to measure an APK that isn't `speed` compiled (NOT-AOT-COMPILED), and Gradle's
# connected test installs and runs in one go; so: build, install, compile, then run it with `am instrument`.
microbenchmark() {
  local test_package=io.github.jeshiomurmu.opencaller.microbenchmark.test
  local runner=androidx.benchmark.junit4.AndroidBenchmarkRunner
  ./gradlew --no-configuration-cache :microbenchmark:assembleReleaseAndroidTest || return 1
  local test_apk
  test_apk=$(find microbenchmark/build/outputs/apk/androidTest -name '*.apk' | head -n 1)
  [ -n "$test_apk" ] || { echo "no microbenchmark test APK"; return 1; }
  adb install -r -t "$test_apk" || return 1
  adb shell cmd package compile -m speed -f "$test_package"
  adb shell am instrument -w -e androidx.benchmark.suppressErrors EMULATOR "$test_package/$runner" |
    tr -d '\r' | tee "../$out/micro-instrument.txt"
  adb pull "/sdcard/Android/media/$test_package/" "../$out/micro-results" > /dev/null 2>&1 || true
  find "../$out/micro-results" -name '*benchmarkData.json' -print -exec cat {} \; 2> /dev/null
  grep -q '^OK (' "../$out/micro-instrument.txt"
}
step "2b. Microbenchmark (screening engine)" microbenchmark

echo "== 3. Battery proxy: fresh release install, idle ${idle_minutes} min, screen off, unplugged"
apk=$(find app/build/outputs/apk/foss/benchmarkRelease -name '*.apk' | head -n 1)
if [ -z "$apk" ]; then
  echo "::error::no benchmarkRelease APK"
  exit 1
fi
adb uninstall "$package" > /dev/null 2>&1 || true
adb install -r "$apk"
adb shell am start -W -n "$package/.MainActivity" > /dev/null
sleep 5
adb shell input keyevent KEYCODE_HOME
adb shell dumpsys battery unplug
adb shell dumpsys batterystats --reset
adb shell input keyevent KEYCODE_SLEEP
sleep $((idle_minutes * 60))
adb shell input keyevent KEYCODE_WAKEUP
# `cmd package list packages -U` prints "package:<name> uid:<uid>" (dumpsys package's userId line wasn't found on API 36).
uid=$(adb shell cmd package list packages -U "$package" | tr -d '\r' | sed -n "s/^package:$package uid:\([0-9]*\).*/\1/p" | head -n 1)
adb shell dumpsys batterystats --checkin | tr -d '\r' > "../$out/batterystats-checkin.txt"
adb shell dumpsys batterystats "$package" | tr -d '\r' > "../$out/batterystats-app.txt"
echo "app uid: $uid"
if [ -n "$uid" ] && [ "$uid" -ge 10000 ]; then
  # The app's own line in the estimated power use ("UID u0a<uid - 10000>: <mAh> …"); absent = nothing measurable.
  label="u0a$((uid - 10000))"
  echo "app power ($label):"
  grep -A 3 "UID $label:" "../$out/batterystats-app.txt" || echo "  no estimated power use for $label"
fi
# Checkin rows for the app's uid: wakelocks (wl), jobs (jb), alarms/wakeups (wua), network (nt), process (pr), cpu.
grep -E "^9,${uid}," "../$out/batterystats-checkin.txt" | grep -E ",(wl|jb|wua|nt|pr|cpu|awl|sr)," || echo "no checkin rows for uid $uid"
sed -n '1,120p' "../$out/batterystats-app.txt"
adb shell dumpsys battery reset

if [ ${#failed[@]} -gt 0 ]; then
  echo "::error::Failed steps: ${failed[*]}"
  exit 1
fi
