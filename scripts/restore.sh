#!/usr/bin/env bash
# Restores a backup made by backup.sh, replacing the current database contents.
# Usage: scripts/restore.sh backups/ordermanager-YYYYMMDD-HHMMSS.dump [--yes]
set -euo pipefail
cd "$(dirname "$0")/.."

if [ $# -lt 1 ] || [ ! -f "$1" ]; then
  echo "usage: scripts/restore.sh <backup file> [--yes]" >&2
  exit 2
fi
if [ "${2:-}" != "--yes" ]; then
  read -r -p "This overwrites the current database with $1. Type RESTORE to continue: " answer
  [ "$answer" = "RESTORE" ] || { echo "Aborted."; exit 1; }
fi

# stop the app so no connection writes while tables are replaced
docker compose stop app
docker compose exec -T db pg_restore -U ordermanager -d ordermanager --clean --if-exists --no-owner < "$1"
docker compose start app
echo "Restored $1"
