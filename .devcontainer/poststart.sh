#!/bin/bash
set -e

echo "[poststart] Starting postgres container..."
docker compose -f /workspaces/small-town/.devcontainer/docker-compose.yml up -d postgres

echo "[poststart] Waiting for postgres to be healthy..."
until docker exec postgres pg_isready -U newcodes -d small_town 2>/dev/null; do
    sleep 2
done

echo "[poststart] Registering postgres hostname in /etc/hosts..."
POSTGRES_IP=$(docker inspect postgres --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')
sudo bash -c "grep -v '[[:space:]]postgres$' /etc/hosts > /tmp/hosts.new && cat /tmp/hosts.new > /etc/hosts && echo '$POSTGRES_IP postgres' >> /etc/hosts"

echo "[poststart] Done! PostgreSQL ready at postgres:5432"
