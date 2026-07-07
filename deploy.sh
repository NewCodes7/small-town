#!/bin/bash

# Blue-Green 무중단 배포 스크립트
set -euo pipefail

# 색상 출력용
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 로그 함수
log() {
    echo -e "${GREEN}[$(date '+%Y-%m-%d %H:%M:%S')] $1${NC}"
}

warn() {
    echo -e "${YELLOW}[$(date '+%Y-%m-%d %H:%M:%S')] WARNING: $1${NC}"
}

error() {
    echo -e "${RED}[$(date '+%Y-%m-%d %H:%M:%S')] ERROR: $1${NC}"
    exit 1
}

# 컨테이너 실행 여부 확인
is_container_running() {
    local container_name=$1
    docker ps --format "{{.Names}}" | grep -Fxq "$container_name"
}

# nginx 컨테이너 내부에서 백엔드 DNS 해석 가능 여부 확인
can_nginx_resolve_backend() {
    local backend_name=$1
    docker exec newcodes-nginx getent hosts "$backend_name" >/dev/null 2>&1
}

# default.conf의 upstream server 라인을 같은 inode로 갱신
update_backend_line_in_place() {
    local backend_name=$1
    local nginx_config="nginx/default.conf"
    local tmp_file

    tmp_file=$(mktemp)

    if ! awk -v target="$backend_name" '
        BEGIN { updated = 0 }
        {
            if ($0 ~ /^[[:space:]]*server[[:space:]]+newcodes-backend-(blue|green):8080[[:space:]]+resolve;/) {
                print "    server " target ":8080 resolve;";
                updated = 1;
            } else {
                print $0;
            }
        }
        END {
            if (!updated) {
                exit 42;
            }
        }
    ' "$nginx_config" > "$tmp_file"; then
        rm -f "$tmp_file"
        error "nginx/default.conf에서 'server newcodes-backend-*:8080 resolve;' 라인을 찾지 못해 전환을 중단합니다"
    fi

    # sudo tee로 파일 소유자와 무관하게 동일 inode 갱신 (bind mount stale 방지)
    if ! sudo tee "$nginx_config" < "$tmp_file" > /dev/null; then
        rm -f "$tmp_file"
        error "쓰기 권한이 없어 nginx 설정을 갱신할 수 없습니다: $nginx_config"
    fi
    rm -f "$tmp_file"
}

# 컨테이너 내부 설정에 원하는 backend가 반영되었는지 확인
is_runtime_backend_applied() {
    local backend_name=$1
    docker exec newcodes-nginx sh -c "grep -Fq 'server ${backend_name}:8080 resolve;' /etc/nginx/conf.d/default.conf"
}

# nginx 컨테이너 재시작 후 설정 확인
restart_nginx_and_verify() {
    local backend_name=$1

    warn "Nginx 컨테이너 설정 불일치 감지: 컨테이너 재시작으로 bind mount 재동기화를 시도합니다"
    docker restart newcodes-nginx >/dev/null
    docker exec newcodes-nginx nginx -t

    if ! is_runtime_backend_applied "$backend_name"; then
        error "Nginx 재시작 후에도 backend 설정이 반영되지 않았습니다: $backend_name"
    fi
}

# 현재 활성 서버 확인
get_active_server() {
    if is_container_running "newcodes-backend-blue"; then
        container_ip=$(docker inspect --format='{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' newcodes-backend-blue 2>/dev/null)
        if [ -n "$container_ip" ] && curl -f -s "http://$container_ip:8080/actuator/health" >/dev/null 2>&1; then
            echo "blue"
        else
            echo "green"
        fi
    else
        echo "green"
    fi
}

# 헬스체크 함수
health_check() {
    local container_name=$1
    local max_attempts=30
    local attempt=1

    log "헬스체크 시작: $container_name"

    while [ $attempt -le $max_attempts ]; do
        # 컨테이너 IP 주소 가져오기
        container_ip=$(docker inspect --format='{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$container_name" 2>/dev/null)

        if [ -n "$container_ip" ] && curl -f -s "http://$container_ip:8080/actuator/health" >/dev/null 2>&1; then
            log "헬스체크 성공: $container_name (${attempt}/${max_attempts})"
            return 0
        fi

        warn "헬스체크 대기 중: $container_name (${attempt}/${max_attempts})"
        sleep 10
        ((attempt++))
    done

    warn "헬스체크 실패: $container_name"
    warn "$container_name 최근 로그(마지막 100줄):"
    docker logs --tail 100 "$container_name" || true
    return 1
}

# nginx 설정 업데이트
update_nginx_upstream() {
    local active_server=$1
    local backend_name

    log "nginx 업스트림 설정 업데이트: $active_server로 전환"

    if [ "$active_server" = "blue" ]; then
        backend_name="newcodes-backend-blue"
    else
        backend_name="newcodes-backend-green"
    fi

    # 존재하지 않는 백엔드로 전환되는 실수를 사전 차단
    if ! is_container_running "$backend_name"; then
        error "전환 대상 컨테이너가 실행 중이 아닙니다: $backend_name"
    fi

    update_backend_line_in_place "$backend_name"

    if ! grep -Fq "server ${backend_name}:8080 resolve;" nginx/default.conf; then
        error "호스트 nginx/default.conf 반영 검증 실패: $backend_name"
    fi

    if ! can_nginx_resolve_backend "$backend_name"; then
        error "Nginx 컨테이너 내부 DNS에서 backend를 해석할 수 없습니다: $backend_name"
    fi

    # 설정 검증 후 무중단 리로드
    docker exec newcodes-nginx nginx -t
    docker exec newcodes-nginx nginx -s reload

    # stale bind mount 등으로 컨테이너 내부 파일이 다른 경우 자동 복구 시도
    if ! is_runtime_backend_applied "$backend_name"; then
        restart_nginx_and_verify "$backend_name"
    fi

    log "nginx 리로드 완료"
}

# 이전 버전 컨테이너 정리
cleanup_old_container() {
    local container_name=$1

    if docker ps -a --format "{{.Names}}" | grep -Fxq "$container_name"; then
        log "이전 컨테이너 정리: $container_name"
        docker stop --time=40 "$container_name" || true
        docker rm "$container_name" || true
    fi
}

# swap 메모리 재할당 함수
reset_swap() {
    log "=== swap 메모리 재할당 시작 ==="

    log "swap 해제 중..."
    sudo swapoff -a

    log "swap 재할당 중..."
    sudo swapon -a

    log "swap 메모리 재할당 완료"

    # swap 상태 확인
    log "현재 swap 상태:"
    free -h | grep -i swap
}

# grafana 컨테이너의 데이터 볼륨 백업 (실제 마운트에서 볼륨명 조회 — 프로젝트명 하드코딩 방지)
backup_grafana_data() {
    local volume_name
    local backup_dir="grafana-backups"
    local backup_file

    volume_name=$(docker inspect grafana --format '{{ range .Mounts }}{{ if eq .Destination "/var/lib/grafana" }}{{ .Name }}{{ end }}{{ end }}' 2>/dev/null || true)

    if [ -z "$volume_name" ]; then
        error "grafana 컨테이너의 데이터 볼륨을 찾을 수 없습니다 (컨테이너가 실행 중인지 확인)"
    fi

    mkdir -p "$backup_dir"
    backup_file="${backup_dir}/grafana-data-$(date '+%Y%m%d_%H%M%S').tar.gz"

    log "grafana-data 볼륨 백업 중: $volume_name -> $backup_file"
    docker run --rm -v "${volume_name}:/data:ro" -v "$(pwd)/${backup_dir}:/backup" alpine \
        tar czf "/backup/$(basename "$backup_file")" -C /data .

    log "백업 완료: $backup_file"
}

# grafana 재배포 함수 (이미지 업데이트 + 재생성, 배포 전 데이터 볼륨 자동 백업)
redeploy_grafana() {
    log "=== git 변경 내용 가져오기 ==="
    git pull origin main

    log "=== Grafana 재배포 시작 ==="

    if is_container_running "grafana"; then
        backup_grafana_data
    else
        warn "grafana 컨테이너가 실행 중이지 않아 백업을 건너뜁니다"
    fi

    log "새 이미지 pull"
    docker compose pull grafana

    log "grafana 컨테이너 재생성"
    docker compose up -d grafana

    log "헬스체크 대기"
    local max_attempts=18
    local attempt=1

    while [ $attempt -le $max_attempts ]; do
        if docker exec grafana wget -q -O- http://localhost:3000/api/health >/dev/null 2>&1; then
            log "grafana 헬스체크 성공 (${attempt}/${max_attempts})"
            log "=== Grafana 재배포 완료 ==="
            docker exec grafana grafana-cli --version || true
            return 0
        fi
        warn "grafana 헬스체크 대기 중 (${attempt}/${max_attempts})"
        sleep 5
        ((attempt++))
    done

    warn "grafana 헬스체크 실패 — 최근 로그(마지막 100줄):"
    docker logs --tail 100 grafana || true
    error "grafana 재배포 실패: 헬스체크 통과 못함"
}

# 메인 배포 함수
deploy() {
    local target=${1-}
    local CURRENT_ACTIVE
    local NEW_ACTIVE
    local NEW_CONTAINER
    local OLD_CONTAINER

    log "=== git 변경 내용 가져오기 ==="
    git restore nginx/default.conf
    git pull origin main

    log "=== Blue-Green 무중단 배포 시작 ==="

    CURRENT_ACTIVE=$(get_active_server)

    if [ -n "$target" ]; then
        if [ "$target" != "blue" ] && [ "$target" != "green" ]; then
            error "배포 대상은 'blue' 또는 'green'만 가능합니다: $target"
        fi
        NEW_ACTIVE="$target"
    else
        # 대상 미지정 시 현재 활성 서버의 반대쪽 자동 선택
        if [ "$CURRENT_ACTIVE" = "blue" ]; then
            NEW_ACTIVE="green"
        else
            NEW_ACTIVE="blue"
        fi
    fi

    if [ "$NEW_ACTIVE" = "blue" ]; then
        NEW_CONTAINER="newcodes-backend-blue"
        OLD_CONTAINER="newcodes-backend-green"
    else
        NEW_CONTAINER="newcodes-backend-green"
        OLD_CONTAINER="newcodes-backend-blue"
    fi

    log "현재 활성 서버: $CURRENT_ACTIVE"
    log "새로 배포할 서버: $NEW_ACTIVE"

    # 1. 이미지 pull 및 새 컨테이너 시작
    log "GHCR에서 이미지 pull"
    docker pull ghcr.io/newcodes7/small-town:latest

    log "새 컨테이너 시작: $NEW_CONTAINER"
    if [ "$NEW_ACTIVE" = "green" ]; then
        docker compose --profile green up -d newcodes-backend-green
    else
        docker compose up -d newcodes-backend-blue
    fi

    # 2. 헬스체크 대기
    if ! health_check "$NEW_CONTAINER"; then
        warn "헬스체크 실패로 새 컨테이너를 정리합니다: $NEW_CONTAINER"
        cleanup_old_container "$NEW_CONTAINER"
        error "배포 실패: $NEW_CONTAINER 헬스체크 통과 못함"
    fi

    # 3. nginx 업스트림 전환
    update_nginx_upstream "$NEW_ACTIVE"

    # 4. 이전 컨테이너 정리 (옵션)
    log "이전 컨테이너($OLD_CONTAINER)를 제거하겠습니다." 
    cleanup_old_container "$OLD_CONTAINER"
    log "이전 컨테이너 제거 완료"

    log "=== 배포 완료 ==="
    log "활성 서버: $NEW_ACTIVE ($NEW_CONTAINER)"

    log "=== docker 미사용 리소스 정리 ==="
    docker container prune -f
    docker image prune -f

    # swap 메모리 재할당 (비활성화)
    # reset_swap
}

# 롤백 함수
rollback() {
    local CURRENT_ACTIVE
    local ROLLBACK_TO

    log "=== 롤백 시작 ==="

    CURRENT_ACTIVE=$(get_active_server)

    if [ "$CURRENT_ACTIVE" = "blue" ]; then
        ROLLBACK_TO="green"
    else
        ROLLBACK_TO="blue"
    fi

    log "롤백 대상: $ROLLBACK_TO"

    # 이전 컨테이너가 실행 중인지 확인
    if ! is_container_running "newcodes-backend-$ROLLBACK_TO"; then
        error "롤백할 컨테이너(newcodes-backend-$ROLLBACK_TO)가 실행 중이지 않습니다"
    fi

    # nginx 업스트림 전환
    update_nginx_upstream "$ROLLBACK_TO"

    log "=== 롤백 완료 ==="
    log "활성 서버: $ROLLBACK_TO"
}

# 상태 확인 함수
status() {
    local CURRENT_ACTIVE
    local container
    local container_ip
    local health_status

    log "=== 현재 상태 ==="

    CURRENT_ACTIVE=$(get_active_server)
    log "현재 활성 서버: $CURRENT_ACTIVE"

    # 컨테이너 상태 확인
    echo ""
    echo "컨테이너 상태:"
    docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | grep "newcodes-backend" || echo "실행 중인 백엔드 컨테이너가 없습니다"

    # 헬스체크 상태
    echo ""
    echo "헬스체크 상태:"
    for container in "newcodes-backend-blue" "newcodes-backend-green"; do
        if is_container_running "$container"; then
            container_ip=$(docker inspect --format='{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$container" 2>/dev/null)
            if [ -n "$container_ip" ] && curl -f -s "http://$container_ip:8080/actuator/health" >/dev/null 2>&1; then
                health_status="healthy"
            else
                health_status="unhealthy"
            fi
            echo "$container: $health_status"
        else
            echo "$container: not running"
        fi
    done
}

# 로그 확인 함수
logs() {
    local target=${1-}
    local container_name

    if [ -n "$target" ]; then
        if [ "$target" != "blue" ] && [ "$target" != "green" ]; then
            error "로그 대상은 'blue' 또는 'green'만 가능합니다: $target"
        fi
        container_name="newcodes-backend-$target"
    else
        container_name=$(docker ps --format "{{.Names}}" | grep -E "^newcodes-backend-(blue|green)$" | head -n 1 || true)
    fi

    if [ -z "$container_name" ]; then
        error "실행 중인 백엔드 컨테이너가 없습니다"
    fi

    log "로그 확인 대상: $container_name"
    docker logs -f --tail 200 "$container_name"
}

# 사용법 출력
usage() {
    echo "Usage: $0 {deploy [blue|green]|rollback|status|logs [blue|green]|redeploy-grafana}"
    echo ""
    echo "Commands:"
    echo "  deploy [blue|green] - 새 버전으로 무중단 배포 (대상 미지정 시 자동 선택)"
    echo "  rollback - 이전 버전으로 롤백"
    echo "  status   - 현재 배포 상태 확인"
    echo "  logs [blue|green] - 백엔드 컨테이너 로그 확인(-f)"
    echo "  redeploy-grafana - grafana-data 볼륨 백업 후 grafana 이미지 재배포 (다운타임 있음)"
    exit 1
}

# 메인 실행부
case "$1" in
    deploy)
        deploy "${2-}"
        ;;
    rollback)
        rollback
        ;;
    status)
        status
        ;;
    logs)
        logs "${2-}"
        ;;
    redeploy-grafana)
        redeploy_grafana
        ;;
    *)
        usage
        ;;
esac
