#!/usr/bin/env bash
# Plants privacy canaries on a test phone before the e2e tests (docs/testing.md → Privacy): a contact, a call-log
# entry and a received SMS, each with text and a number used nowhere else. scripts/e2e/check_traffic.py then fails
# if any of them shows up in the phones' network traffic (docs/architecture.md §10: contacts, the call log and SMS
# bodies never leave the phone).
#
# Usage: scripts/e2e/canaries.sh <device> (the values are fixed; check_traffic.py is given the same ones in ci.sh)
set -euo pipefail
device="${1:?usage: canaries.sh <device>}"
contact_name="Canary Contact Qx7"
contact_number="+12025550177"
call_log_number="+12025550178"
sms_from="2025550179"
sms_body="CANARY-SMS-7d1f private text"

shell() { adb -s "$device" shell "$@" | tr -d '\r'; }

# A contact on the phone (no account: stored locally, never synced anywhere by the emulator).
shell content insert --uri content://com.android.contacts/raw_contacts --bind account_type:n: --bind account_name:n:
raw_id="$(shell content query --uri content://com.android.contacts/raw_contacts --projection _id | sed -n 's/.*_id=\([0-9]*\).*/\1/p' | sort -n | tail -n 1)"
if [[ -n "$raw_id" ]]; then
  shell content insert --uri content://com.android.contacts/data --bind "raw_contact_id:l:$raw_id" \
    --bind mimetype:s:vnd.android.cursor.item/name --bind "data1:s:'$contact_name'"
  shell content insert --uri content://com.android.contacts/data --bind "raw_contact_id:l:$raw_id" \
    --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind "data1:s:$contact_number" --bind data2:i:2
fi

# A missed call in the call log.
shell content insert --uri content://call_log/calls --bind "number:s:$call_log_number" --bind type:i:3 \
  --bind "date:l:$(date +%s)000" --bind duration:i:0 --bind new:i:1

# A received SMS through the emulated network (stored by the default SMS app, OpenCaller's pipeline when it is one).
adb -s "$device" emu sms send "$sms_from" "$sms_body" > /dev/null

sleep 3
contacts="$(shell content query --uri content://com.android.contacts/data/phones --projection data1 | grep -c "$contact_number" || true)"
calls="$(shell content query --uri content://call_log/calls --projection number | grep -c "$call_log_number" || true)"
sms="$(shell content query --uri content://sms/inbox --projection body | grep -c 'CANARY-SMS-7d1f' || true)"
echo "$device canaries: contact=$contacts call_log=$calls sms=$sms"
if [[ "$contacts" == 0 || "$calls" == 0 ]]; then
  echo "::warning::$device: a contact or call-log canary wasn't stored; the traffic check covers less"
fi
