#!/bin/bash
set -e

# =============================================================================
# Codespace postStart: PostgreSQL 안전 기동 + hostname 등록
#
# ⚠️ 절대 하지 말 것: 컨테이너가 exited라고 해서 pg_resetwal 자동 실행.
#    pg_resetwal -f 는 WAL을 "버리는" 명령이라, 정상적인 crash recovery(WAL
#    replay)를 막고 시스템 카탈로그를 손상시킨다("cache lookup failed for
#    relation 1259"). Codespace는 종료 시 컨테이너를 강제 종료할 수 있어
#    exited 상태가 흔하며, 그때 postgres는 다음 기동에서 WAL replay로 스스로
#    복구한다. 자동 resetwal은 복구 수단이 아니라 손상 원인이다.
#    손상이 의심되면 사람이 진단 후 최후수단으로만 수동 실행한다.
# =============================================================================

DATA_DIR_HINT="postgres_backup_data"   # data 디렉토리를 마운트한 컨테이너 식별용

# -----------------------------------------------------------------------------
# 0) 현재 compose 프로젝트 파악 (app 컨테이너 라벨 기준)
# -----------------------------------------------------------------------------
CUR_PROJECT=$(docker inspect small-town-dev \
    --format '{{index .Config.Labels "com.docker.compose.project"}}' 2>/dev/null || true)

# postgres 서비스 컨테이너 후보 전체
mapfile -t PG_CONTAINERS < <(docker ps -a \
    --filter "label=com.docker.compose.service=postgres" --format '{{.Names}}')

# -----------------------------------------------------------------------------
# 1) 같은 data 디렉토리를 마운트한 "중복" postgres 컨테이너 제거
#    이중 postmaster가 동일 데이터 디렉토리에 동시 기록하면 페이지가 찢어져
#    (torn page) 카탈로그가 손상된다. 현재 프로젝트 소속 1개만 남긴다.
# -----------------------------------------------------------------------------
TARGET_CONTAINER=""
for c in "${PG_CONTAINERS[@]}"; do
    MOUNTS_DATA=$(docker inspect "$c" \
        --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Source}}{{end}}{{end}}' 2>/dev/null || true)
    case "$MOUNTS_DATA" in
        *"$DATA_DIR_HINT"*) ;;          # 우리 data 디렉토리를 마운트한 컨테이너
        *) continue ;;                  # 무관한 컨테이너는 건너뜀
    esac

    C_PROJECT=$(docker inspect "$c" \
        --format '{{index .Config.Labels "com.docker.compose.project"}}' 2>/dev/null || true)

    if [ -n "$CUR_PROJECT" ] && [ "$C_PROJECT" = "$CUR_PROJECT" ] && [ -z "$TARGET_CONTAINER" ]; then
        TARGET_CONTAINER="$c"           # 현재 프로젝트 소속 → 유지
    else
        echo "[poststart] Removing duplicate/orphan postgres container: $c (project=$C_PROJECT)"
        docker update --restart=no "$c" >/dev/null 2>&1 || true
        docker rm -f "$c" >/dev/null 2>&1 || true
    fi
done

# 라벨로 못 찾았을 때 폴백
if [ -z "$TARGET_CONTAINER" ]; then
    TARGET_CONTAINER=$(docker ps -a \
        --filter "label=com.docker.compose.service=postgres" --format '{{.Names}}' | head -n 1)
fi
if [ -z "$TARGET_CONTAINER" ]; then
    TARGET_CONTAINER="small-town_devcontainer-postgres-1"
fi
echo "[poststart] Target postgres container: $TARGET_CONTAINER"

# -----------------------------------------------------------------------------
# 2) 컨테이너가 멈춰 있으면 그냥 start → postgres가 WAL replay로 스스로 복구
#    (resetwal 금지). 기동 실패가 반복되면 로그를 남기고 사람에게 위임한다.
# -----------------------------------------------------------------------------
CONTAINER_STATUS=$(docker inspect "$TARGET_CONTAINER" --format '{{.State.Status}}' 2>/dev/null || echo "missing")
if [ "$CONTAINER_STATUS" != "running" ]; then
    echo "[poststart] Postgres is '$CONTAINER_STATUS' → starting (WAL replay handles unclean shutdown)..."
    docker start "$TARGET_CONTAINER" >/dev/null 2>&1 || true
fi

# -----------------------------------------------------------------------------
# 3) 헬스 대기 (pg_isready)
# -----------------------------------------------------------------------------
echo "[poststart] Waiting for postgres to be ready..."
ATTEMPTS=0
until docker exec "$TARGET_CONTAINER" pg_isready -U newcodes -d small_town 2>/dev/null; do
    ATTEMPTS=$((ATTEMPTS + 1))
    if [ $ATTEMPTS -ge 30 ]; then
        echo "[poststart] ERROR: Postgres did not become ready after 60s."
        echo "[poststart] 손상이 의심되면 .devcontainer/RECOVERY.md 참고 — pg_resetwal은 자동 실행하지 않습니다."
        docker logs "$TARGET_CONTAINER" --tail 30 || true
        exit 1
    fi
    echo "Database is starting up... sleeping 2s"
    sleep 2
done

# -----------------------------------------------------------------------------
# 4) devcontainer가 'postgres' 호스트네임으로 접근할 수 있도록 /etc/hosts 등록
# -----------------------------------------------------------------------------
echo "[poststart] Registering postgres hostname in /etc/hosts..."
DEVCONTAINER_NETWORK=$(docker inspect small-town-dev \
    --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' 2>/dev/null | head -n 1)

POSTGRES_IP=""
if [ -n "$DEVCONTAINER_NETWORK" ]; then
    ALREADY_CONNECTED=$(docker inspect "$TARGET_CONTAINER" \
        --format "{{index .NetworkSettings.Networks \"$DEVCONTAINER_NETWORK\"}}" 2>/dev/null)
    if [ -z "$ALREADY_CONNECTED" ] || [ "$ALREADY_CONNECTED" = "<no value>" ]; then
        echo "[poststart] Connecting postgres to network: $DEVCONTAINER_NETWORK"
        docker network connect "$DEVCONTAINER_NETWORK" "$TARGET_CONTAINER" 2>/dev/null || true
    fi
    POSTGRES_IP=$(docker inspect "$TARGET_CONTAINER" \
        --format "{{(index .NetworkSettings.Networks \"$DEVCONTAINER_NETWORK\").IPAddress}}" 2>/dev/null)
fi
if [ -z "$POSTGRES_IP" ]; then
    POSTGRES_IP=$(docker inspect "$TARGET_CONTAINER" \
        --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' | awk '{print $1}')
fi
if [ -z "$POSTGRES_IP" ]; then
    POSTGRES_IP=$(docker inspect "$TARGET_CONTAINER" --format '{{.NetworkSettings.IPAddress}}')
fi

sudo bash -c "grep -v '[[:space:]]postgres\$' /etc/hosts > /tmp/hosts.new && cat /tmp/hosts.new > /etc/hosts && echo '$POSTGRES_IP postgres' >> /etc/hosts"

# -----------------------------------------------------------------------------
# JDK 26 (빌드 toolchain) — 컨테이너 리빌드 후에도 설치를 보장
# 없어도 settings.gradle의 foojay resolver가 자동 다운로드하지만,
# 매 빌드 재다운로드를 피하기 위해 SDKMAN에 설치해 둔다.
# Gradle 데몬 자체는 시스템 JDK 17로 돌고, toolchain으로 26을 쓴다.
# -----------------------------------------------------------------------------
if [ -d /usr/local/sdkman ] && [ ! -d /usr/local/sdkman/candidates/java/26.0.1-tem ]; then
    echo "[poststart] Installing JDK 26 (Temurin) via SDKMAN..."
    bash -c 'source /usr/local/sdkman/bin/sdkman-init.sh && yes n | sdk install java 26.0.1-tem' || \
        echo "[poststart] JDK 26 설치 실패 — 빌드는 foojay resolver 자동 다운로드로 동작함"
fi

echo "[poststart] Done! PostgreSQL ready at postgres($POSTGRES_IP):5432"
