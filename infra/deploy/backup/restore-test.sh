#!/usr/bin/env bash
# Restores the newest backup into a throwaway PostgreSQL container and compares it with the live database: the Flyway
# version and the row counts of the main tables. --remote takes the newest dump from BACKUP_REMOTE instead of the local
# copy (run it weekly, README → Backups). Leaves nothing behind; exits 1 when the restore or a check fails.
set -euo pipefail
cd "$(dirname "$0")/.."

set -a
# shellcheck disable=SC1091
. ./.env
set +a

work="$(mktemp -d)"
container="opencaller-restore-test-$$"
cleanup() { docker rm -f "$container" > /dev/null 2>&1 || true; rm -rf "$work"; }
trap cleanup EXIT

if [[ "${1:-}" == "--remote" ]]; then
  latest="$(docker run --rm -v "$PWD/backup/rclone.conf:/config/rclone/rclone.conf:ro" rclone/rclone:1.76.0 \
    lsf "$BACKUP_REMOTE/db/" --include 'opencaller-*.dump' | sort | tail -1)"
  [[ -n "$latest" ]] || { echo "no dump at $BACKUP_REMOTE/db/"; exit 1; }
  docker run --rm -v "$PWD/backup/rclone.conf:/config/rclone/rclone.conf:ro" -v "$work:/work" rclone/rclone:1.76.0 \
    copy "$BACKUP_REMOTE/db/$latest" /work/
  dump="$work/$latest"
else
  dump="$(ls -1 backups/opencaller-*.dump | sort | tail -1)"
fi
echo "restoring $dump"

docker run -d --name "$container" -e POSTGRES_PASSWORD=restore-test -v "$(realpath "$dump"):/restore.dump:ro" postgres:18.7 > /dev/null
for _ in $(seq 1 60); do
  docker exec "$container" pg_isready -U postgres > /dev/null 2>&1 && break
  sleep 1
done
docker exec "$container" createdb -U postgres restored
docker exec "$container" pg_restore -U postgres -d restored --no-owner --exit-on-error /restore.dump

query() { docker exec "$container" psql -U postgres -d restored -tAc "$1"; }
live() { docker compose -f compose.prod.yml exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "$1"; }

version_sql="select max(version::int) from flyway_schema_history where success"
restored_version="$(query "$version_sql")"
echo "Flyway version: restored $restored_version, live $(live "$version_sql" 2>/dev/null || echo '?')"
[[ -n "$restored_version" ]] || { echo "FAIL: no Flyway history in the dump"; exit 1; }
for table in users devices reports; do
  echo "$table: restored $(query "select count(*) from $table"), live $(live "select count(*) from $table" 2>/dev/null || echo '?')"
done
echo "Restore test passed ($(basename "$dump"))."
