#!/usr/bin/env bash
# Nightly backup (cron, README → Backups): a custom-format pg_dump checked with pg_restore --list, copied off-site with
# rclone to BACKUP_REMOTE (an encrypted "crypt" remote in backup/rclone.conf), pruned locally after 7 days and off-site
# after BACKUP_KEEP_DAYS. BACKUP_MEDIA=true also syncs the MinIO bucket. Pings BACKUP_PING_URL on success.
set -euo pipefail
cd "$(dirname "$0")/.."

set -a
# shellcheck disable=SC1091
. ./.env
set +a

compose=(docker compose -f compose.prod.yml)
rclone_image=rclone/rclone:1.76.0
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
file="opencaller-$stamp.dump"

"${compose[@]}" exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --file="/backups/$file"
"${compose[@]}" exec -T postgres pg_restore --list "/backups/$file" > /dev/null
(cd backups && sha256sum "$file" > "$file.sha256")
echo "dump: backups/$file ($(du -h "backups/$file" | cut -f1))"

rclone() {
  docker run --rm --network opencaller_backend \
    -v "$PWD/backups:/backups:ro" -v "$PWD/backup/rclone.conf:/config/rclone/rclone.conf:ro" \
    -e RCLONE_CONFIG_MINIO_TYPE=s3 -e RCLONE_CONFIG_MINIO_PROVIDER=Minio -e RCLONE_CONFIG_MINIO_ENDPOINT=http://minio:9000 \
    -e RCLONE_CONFIG_MINIO_ACCESS_KEY_ID="$MINIO_ROOT_USER" -e RCLONE_CONFIG_MINIO_SECRET_ACCESS_KEY="$MINIO_ROOT_PASSWORD" \
    "$rclone_image" "$@"
}

rclone copy "/backups/$file" "$BACKUP_REMOTE/db/"
rclone copy "/backups/$file.sha256" "$BACKUP_REMOTE/db/"
rclone delete --min-age "${BACKUP_KEEP_DAYS:-30}d" "$BACKUP_REMOTE/db/"
if [[ "${BACKUP_MEDIA:-false}" == "true" ]]; then
  rclone sync "minio:${MINIO_BUCKET:-opencaller}" "$BACKUP_REMOTE/media/"
fi
echo "off-site: $BACKUP_REMOTE/db/$file"

find backups -name 'opencaller-*.dump*' -mtime +7 -delete
if [[ -n "${BACKUP_PING_URL:-}" ]]; then curl -fsS -m 10 --retry 3 "$BACKUP_PING_URL" > /dev/null; fi
echo "backup done $stamp"
