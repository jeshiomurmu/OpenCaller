#!/usr/bin/env bash
# Loads backend/load/seed.sql (1M synthetic numbers) into the compose PostgreSQL. Development stacks only.
#   backend/load/seed.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
compose_file="${COMPOSE_FILE:-infra/docker/compose.yml}"
# shellcheck disable=SC2016 # expanded inside the container
docker compose -f "$compose_file" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < backend/load/seed.sql
