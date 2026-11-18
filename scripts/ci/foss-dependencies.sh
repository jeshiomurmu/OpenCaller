#!/usr/bin/env bash
# The foss flavor (F-Droid, GitHub releases) must not ship Google Play services, Firebase, Play Billing, Play Core or
# any other proprietary library (docs/release/fdroid.md). Resolves the app's fossReleaseRuntimeClasspath, prints a
# summary (artifacts per group) and fails when a forbidden group appears. Run from the repository root.
set -euo pipefail

cd "$(dirname "$0")/../../android"

check() {
  local label="$1"
  shift
  out="$(mktemp)"
  ./gradlew -q :app:dependencies --configuration fossReleaseRuntimeClasspath "$@" > "$out"

  # "+--- group:artifact:version" or "\--- group:artifact:requested -> resolved"; project modules are "project :x".
  # A grep without a match must not end the script silently (set -e with pipefail), so each one tolerates none.
  artifacts="$({ grep -oE -- '--- [A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+' "$out" || true; } | sed -E 's/^--- //' | sort -u)"
  modules="$({ grep -oE -- '--- project :?[A-Za-z0-9:_-]+' "$out" || true; } | sed 's/--- project //' | sort -u)"
  if [[ -z "$artifacts" ]]; then
    echo "::error::no dependencies parsed from the report; its first lines:"
    head -40 "$out"
    exit 1
  fi

  local forbidden='^(com\.google\.firebase|com\.google\.android\.gms|com\.android\.billingclient|com\.google\.android\.play|com\.google\.mlkit|com\.crashlytics|io\.fabric|com\.google\.android\.datatransport):'

  echo "fossReleaseRuntimeClasspath ($label): $(wc -l <<< "$artifacts") external artifacts in $(cut -d: -f1 <<< "$artifacts" | sort -u | wc -l) groups, $(grep -c . <<< "$modules" || true) project modules"
  echo "Artifacts per group:"
  cut -d: -f1 <<< "$artifacts" | sort | uniq -c | sort -rn | sed 's/^/  /'

  bad="$(grep -E "$forbidden" <<< "$artifacts" || true)"
  if [[ -n "$bad" ]]; then
    echo "::error::the foss flavor depends on proprietary libraries:"
    echo "$bad"
    exit 1
  fi
  echo "OK ($label): no Google Play services, Firebase, Play Billing, Play Core, ML Kit or Crashlytics"
}

# The GitHub release's foss APK (app calls included) and F-Droid's build (-Popencaller.voip=false, no LiveKit).
check "GitHub release"
check "F-Droid, opencaller.voip=false" -Popencaller.voip=false
