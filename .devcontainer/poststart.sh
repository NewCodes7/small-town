#!/bin/bash
set -e

# Codespaces가 생성하는 실제 DB 컨테이너 이름을 추적합니다.
# 일반적으로 '프로젝트명-postgres-1' 형태가 됩니다.
TARGET_CONTAINER=$(docker ps -a --filter "label=com.docker.compose.service=postgres" --format "{{.Names}}" | head -n 1)

# 만약 라벨로 못 찾으면 폴백(Fallback)으로 기본 조합 사용
if [ -z "$TARGET_CONTAINER" ]; then
    TARGET_CONTAINER="small-town_devcontainer-postgres-1"
fi

echo "[poststart] Found postgres container: $TARGET_CONTAINER"

# WAL 손상 감지: 컨테이너가 종료 상태인 경우 pg_resetwal로 복구
CONTAINER_STATUS=$(docker inspect "$TARGET_CONTAINER" --format '{{.State.Status}}' 2>/dev/null || echo "missing")
if [ "$CONTAINER_STATUS" = "exited" ]; then
    EXIT_CODE=$(docker inspect "$TARGET_CONTAINER" --format '{{.State.ExitCode}}' 2>/dev/null || echo "1")
    echo "[poststart] Postgres container exited (code $EXIT_CODE). Checking for WAL corruption..."

    # 컨테이너 이미지로 pg_resetwal 실행
    DATA_DIR=$(docker inspect "$TARGET_CONTAINER" --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Source}}{{end}}{{end}}')
    IMAGE=$(docker inspect "$TARGET_CONTAINER" --format '{{.Config.Image}}')

    if [ -n "$DATA_DIR" ] && [ -n "$IMAGE" ]; then
        echo "[poststart] Running pg_resetwal on $DATA_DIR ..."
        docker run --rm -v "$DATA_DIR:/var/lib/postgresql/data" --user postgres "$IMAGE" \
            pg_resetwal -f /var/lib/postgresql/data 2>&1 && echo "[poststart] WAL reset successful."
    fi

    echo "[poststart] Restarting postgres container..."
    docker start "$TARGET_CONTAINER"
fi

echo "[poststart] Waiting for postgres to be healthy..."

# pg_isready를 이용해 DB가 완전히 켜질 때까지 대기
ATTEMPTS=0
until docker exec "$TARGET_CONTAINER" pg_isready -U newcodes -d small_town 2>/dev/null; do
    ATTEMPTS=$((ATTEMPTS + 1))
    if [ $ATTEMPTS -ge 30 ]; then
        echo "[poststart] ERROR: Postgres did not become ready after 60s"
        docker logs "$TARGET_CONTAINER" --tail 20
        exit 1
    fi
    echo "Database is starting up... sleeping 2s"
    sleep 2
done

echo "[poststart] Registering postgres hostname in /etc/hosts..."

# devcontainer와 같은 네트워크의 IP를 우선 사용
DEVCONTAINER_NETWORK=$(docker inspect small-town-dev --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' 2>/dev/null | head -n 1)

if [ -n "$DEVCONTAINER_NETWORK" ]; then
    # postgres 컨테이너가 같은 네트워크에 연결되어 있는지 확인, 없으면 연결
    ALREADY_CONNECTED=$(docker inspect "$TARGET_CONTAINER" --format "{{index .NetworkSettings.Networks \"$DEVCONTAINER_NETWORK\"}}" 2>/dev/null)
    if [ -z "$ALREADY_CONNECTED" ] || [ "$ALREADY_CONNECTED" = "<no value>" ]; then
        echo "[poststart] Connecting postgres to network: $DEVCONTAINER_NETWORK"
        docker network connect "$DEVCONTAINER_NETWORK" "$TARGET_CONTAINER" 2>/dev/null || true
    fi
    POSTGRES_IP=$(docker inspect "$TARGET_CONTAINER" --format "{{(index .NetworkSettings.Networks \"$DEVCONTAINER_NETWORK\").IPAddress}}" 2>/dev/null)
fi

# 폴백: 첫 번째 네트워크 IP 사용
if [ -z "$POSTGRES_IP" ]; then
    POSTGRES_IP=$(docker inspect "$TARGET_CONTAINER" --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' | awk '{print $1}')
fi

if [ -z "$POSTGRES_IP" ]; then
    POSTGRES_IP=$(docker inspect "$TARGET_CONTAINER" --format '{{.NetworkSettings.IPAddress}}')
fi

# /etc/hosts에 IP 등록
sudo bash -c "grep -v '[[:space:]]postgres$' /etc/hosts > /tmp/hosts.new && cat /tmp/hosts.new > /etc/hosts && echo '$POSTGRES_IP postgres' >> /etc/hosts"

echo "[poststart] Done! PostgreSQL ready at postgres($POSTGRES_IP):5432"
