#!/usr/bin/env bash
# docs/architecture.md §10 invariant 1, checked on the source tree (android CI job):
#   1. core:network (every server API and DTO) is a plain JVM module, so it can't read Android's contacts, call log
#      or SMS providers at all;
#   2. no app source file that reads those providers (android.provider.ContactsContract, CallLog, Telephony) also
#      uses the network layer (core.network, Retrofit, OkHttp), so provider rows can't be handed to an API call in
#      the same place they are read.
# The server side has no endpoint that accepts such lists (docs/api/openapi.yaml; reviewed in docs/security.md).
set -euo pipefail
cd "$(dirname "$0")/../../android"

status=0
if ! grep -q 'id("opencaller.jvm.library")' core/network/build.gradle.kts; then
  echo "::error::core:network must stay a JVM module (no Android provider access)"
  status=1
fi

providers='^import android\.provider\.(ContactsContract|CallLog|Telephony)\b'
network='^import (io\.github\.jeshiomurmu\.opencaller\.core\.network\.|retrofit2\.|okhttp3\.)'
while IFS= read -r file; do
  if grep -qE "$network" "$file"; then
    echo "::error file=android/$file::reads a contacts/call-log/SMS provider and imports the network layer"
    status=1
  fi
done < <(grep -rlE --include='*.kt' "$providers" app/src core feature | grep '/src/main/' || true)

[ "$status" -eq 0 ] && echo "Privacy imports OK: provider readers and the network layer are apart"
exit "$status"
