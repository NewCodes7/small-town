#!/bin/bash
set -e

# Codespaces가 생성하는 실제 DB 컨테이너 이름을 추적합니다.
# 일반적으로 '프로젝트명-postgres-1' 형태가 됩니다.
TARGET_CONTAINER=$(docker ps --filter "label=com.docker.compose.service=postgres" --format "{{.Names}}" | head -n 1)

# 만약 라벨로 못 찾으면 폴백(Fallback)으로 기본 조합 사용
if [ -z "$TARGET_CONTAINER" ]; then
    TARGET_CONTAINER="small-town_devcontainer-postgres-1"
fi

echo "[poststart] Found postgres container: $TARGET_CONTAINER"
echo "[poststart] Waiting for postgres to be healthy..."

# pg_isready를 이용해 DB가 완전히 켜질 때까지 대기 (기존 의도 유지)
until docker exec "$TARGET_CONTAINER" pg_isready -U newcodes -d small_town 2>/dev/null; do
    echo "Database is starting up... sleeping 2s"
    sleep 2
done

echo "[poststart] Registering postgres hostname in /etc/hosts..."
# 해당 컨테이너의 내부 IP를 가져옵니다.
POSTGRES_IP=$(docker inspect "$TARGET_CONTAINER" --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')

if [ -z "$POSTGRES_IP" ]; then
    # 네트워크 모드에 따라 다를 수 있으므로 다른 경로로 재시도
    POSTGRES_IP=$(docker inspect "$TARGET_CONTAINER" --format '{{.NetworkSettings.IPAddress}}')
fi

# /etc/hosts에 IP 등록
sudo bash -c "grep -v '[[:space:]]postgres$' /etc/hosts > /tmp/hosts.new && cat /tmp/hosts.new > /etc/hosts && echo '$POSTGRES_IP postgres' >> /etc/hosts"

echo "[poststart] Done! PostgreSQL ready at postgres:5432"