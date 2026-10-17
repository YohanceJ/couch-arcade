#!/usr/bin/env bash
# RomM setup for Raspberry Pi 5 (library on the SD card)
# Usage:  bash setup-romm.sh
# Re-running is safe: it won't overwrite an existing setup.

set -euo pipefail

ROMM_DIR="$HOME/romm"
PORT=8080

echo "==> Checking Docker..."
command -v docker >/dev/null || { echo "Docker not found."; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "Docker Compose plugin not found. Install with: sudo apt install docker-compose-plugin"; exit 1; }

echo "==> Checking port $PORT is free..."
if ss -ltn | awk '{print $4}' | grep -q ":$PORT\$"; then
  echo "Port $PORT is already used by something on this Pi."
  echo "Edit PORT at the top of this script (e.g. 8085) and run again."
  exit 1
fi

if [ -f "$ROMM_DIR/docker-compose.yml" ]; then
  echo "RomM already set up in $ROMM_DIR - starting it."
  cd "$ROMM_DIR" && docker compose up -d
  exit 0
fi

echo "==> Creating folders in $ROMM_DIR ..."
# Folder names follow Batocera conventions, which RomM recognises.
mkdir -p "$ROMM_DIR"/{config,assets}
for p in nes snes n64 gb gbc gba megadrive mastersystem psx psp dreamcast mame; do
  mkdir -p "$ROMM_DIR/library/roms/$p"
done
mkdir -p "$ROMM_DIR/library/bios/psx" "$ROMM_DIR/library/bios/dreamcast"

echo "==> Generating passwords and secret key..."
DB_PASS=$(openssl rand -hex 24)
DB_ROOT_PASS=$(openssl rand -hex 24)
AUTH_KEY=$(openssl rand -hex 32)

cat > "$ROMM_DIR/docker-compose.yml" <<EOF
volumes:
  mysql_data:
  romm_resources:
  romm_redis_data:

services:
  romm:
    image: rommapp/romm:latest
    container_name: romm
    restart: unless-stopped
    environment:
      - DB_HOST=romm-db
      - DB_NAME=romm
      - DB_USER=romm-user
      - DB_PASSWD=${DB_PASS}
      - ROMM_AUTH_SECRET_KEY=${AUTH_KEY}
      - HASHEOUS_API_ENABLED=true   # free, no key needed - gives basic game matching
      # Optional, for better box art. Fill in later, then: docker compose up -d
      - SCREENSCRAPER_USER=
      - SCREENSCRAPER_PASSWORD=
      - STEAMGRIDDB_API_KEY=
      - RETROACHIEVEMENTS_API_KEY=
    volumes:
      - romm_resources:/romm/resources
      - romm_redis_data:/redis-data
      - ./library:/romm/library
      - ./assets:/romm/assets
      - ./config:/romm/config
    ports:
      - ${PORT}:8080
    depends_on:
      romm-db:
        condition: service_healthy
        restart: true

  romm-db:
    image: mariadb:latest
    container_name: romm-db
    restart: unless-stopped
    environment:
      - MARIADB_ROOT_PASSWORD=${DB_ROOT_PASS}
      - MARIADB_DATABASE=romm
      - MARIADB_USER=romm-user
      - MARIADB_PASSWORD=${DB_PASS}
    volumes:
      - mysql_data:/var/lib/mysql
    healthcheck:
      test: [CMD, healthcheck.sh, --connect, --innodb_initialized]
      start_period: 30s
      start_interval: 10s
      interval: 10s
      timeout: 5s
      retries: 5
EOF
chmod 600 "$ROMM_DIR/docker-compose.yml"

echo "==> Starting RomM (first run downloads images, takes a few minutes)..."
cd "$ROMM_DIR"
docker compose up -d

IP=$(hostname -I | awk '{print $1}')
echo
echo "Done! Open this on your phone:  http://$IP:$PORT"
echo "Create your admin account in the setup wizard."
echo "Put games in: $ROMM_DIR/library/roms/<console>/  then run a scan in RomM."
