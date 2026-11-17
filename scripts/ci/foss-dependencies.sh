#!/usr/bin/env bash
# The foss flavor (F-Droid, GitHub releases) must not ship Google Play services, Firebase, Play Billing, Play Core or
# any other proprietary library (docs/release/fdroid.md). Resolves the app's fossReleaseRuntimeClasspath, prints a
# summary (artifacts per group) and fails when a forbidden group appears. Run from the repository root.
set -euo pipefail

cd "$(dirname "$0")/../../android"

out="$(mktemp)"
./gradlew -q :app:dependencies --configuration fossReleaseRuntimeClasspath > "$out"

# "+--- group:artifact:version" or "\--- group:artifact:requested -> resolved"; project modules are "project :x".
artifacts="$(grep -oE '[-+\\]--- [A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+' "$out" | sed -E 's/^.--- //' | sort -u)"
modules="$(grep -oE -- '--- project :[A-Za-z0-9:_-]+' "$out" | sed 's/--- project //' | sort -u)"

forbidden='^(com\.google\.firebase|com\.google\.android\.gms|com\.android\.billingclient|com\.google\.android\.play|com\.google\.mlkit|com\.crashlytics|io\.fabric|com\.google\.android\.datatransport):'

echo "fossReleaseRuntimeClasspath: $(wc -l <<< "$artifacts") external artifacts in $(cut -d: -f1 <<< "$artifacts" | sort -u | wc -l) groups, $(wc -l <<< "$modules") project modules"
echo "Artifacts per group:"
cut -d: -f1 <<< "$artifacts" | sort | uniq -c | sort -rn | sed 's/^/  /'

bad="$(grep -E "$forbidden" <<< "$artifacts" || true)"
if [[ -n "$bad" ]]; then
  echo "::error::the foss flavor depends on proprietary libraries:"
  echo "$bad"
  exit 1
fi
echo "OK: no Google Play services, Firebase, Play Billing, Play Core, ML Kit or Crashlytics in the foss flavor"
