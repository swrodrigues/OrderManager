#!/usr/bin/env bash
# Builds and starts the app and database, then waits until the app answers (runs on every codespace start).
set -euo pipefail
cd "$(dirname "$0")/.."

echo "Waiting for Docker..."
for _ in $(seq 1 60); do docker info >/dev/null 2>&1 && break; sleep 2; done

echo "Building and starting OrderManager (the first start takes a few minutes)..."
docker compose up -d --build

for _ in $(seq 1 120); do
  if curl -fsS http://localhost:8080/actuator/health 2>/dev/null | grep -q '"UP"'; then
    echo
    echo "OrderManager is running: open the Ports tab, port 8080 (it opens in the browser automatically)."
    echo "Log in with  admin / reviewer-demo-2026"
    exit 0
  fi
  sleep 3
done
echo "The app did not report healthy yet; see: docker compose logs app" >&2
exit 1
