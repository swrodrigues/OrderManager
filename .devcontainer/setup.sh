#!/usr/bin/env bash
# Creates .env with demo settings for this codespace (runs once, when the codespace is created).
# Fresh random secrets per codespace; a fixed, documented login so reviewers can sign in.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f .env ]; then
  echo ".env already exists, keeping it."
  exit 0
fi

cat > .env <<EOF
POSTGRES_PASSWORD=$(openssl rand -hex 16)
APP_ENC_KEY=$(openssl rand -base64 32)
APP_ADMIN_USERNAME=admin
APP_ADMIN_PASSWORD=reviewer-demo-2026
SPRING_PROFILES_ACTIVE=demo
APP_TIME_ZONE=Europe/Amsterdam
# Codespaces serves the app over HTTPS through GitHub's proxy
APP_COOKIE_SECURE=true
SERVER_FORWARD_HEADERS_STRATEGY=native
EOF
echo "Created .env with demo settings for this codespace."
