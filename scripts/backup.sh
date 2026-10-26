#!/usr/bin/env bash
# Online backup of the OrderManager database (pg_dump custom format, compressed).
# Usage: scripts/backup.sh            -> backups/ordermanager-YYYYMMDD-HHMMSS.dump
set -euo pipefail
cd "$(dirname "$0")/.."

mkdir -p backups
file="backups/ordermanager-$(date +%Y%m%d-%H%M%S).dump"
docker compose exec -T db pg_dump -U ordermanager -d ordermanager -Fc > "$file"
echo "Backup written: $file ($(wc -c < "$file") bytes)"
