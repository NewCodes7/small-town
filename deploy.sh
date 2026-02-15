#!/bin/bash

# Blue-Green 무중단 배포 스크립트
set -e

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

# 현재 활성 서버 확인
get_active_server() {
    if docker ps --format "table {{.Names}}" | grep -q "newcodes-backend-blue"; then
        container_ip=$(docker inspect --format='{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' newcodes-backend-blue 2>/dev/null)
        if [ -n "$container_ip" ] && curl -f -s http://$container_ip:8080/actuator/health >/dev/null 2>&1; then
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
        container_ip=$(docker inspect --format='{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $container_name 2>/dev/null)

        if [ -n "$container_ip" ] && curl -f -s http://$container_ip:8080/actuator/health >/dev/null 2>&1; then
            log "헬스체크 성공: $container_name (${attempt}/${max_attempts})"
            return 0
        fi

        warn "헬스체크 대기 중: $container_name (${attempt}/${max_attempts})"
        sleep 10
        ((attempt++))
    done

    error "헬스체크 실패: $container_name"
}

# nginx 설정 업데이트
update_nginx_upstream() {
    local active_server=$1
    local nginx_config="nginx/default.conf"

    log "nginx 업스트림 설정 업데이트: $active_server로 전환"

    if [ "$active_server" = "blue" ]; then
        # Blue로 전환
        sed -i 's/# server newcodes-backend-blue:8080/server newcodes-backend-blue:8080/g' $nginx_config
        sed -i 's/server newcodes-backend-green:8080/# server newcodes-backend-green:8080/g' $nginx_config
    else
        # Green으로 전환
        sed -i 's/# server newcodes-backend-green:8080/server newcodes-backend-green:8080/g' $nginx_config
        sed -i 's/server newcodes-backend-blue:8080/# server newcodes-backend-blue:8080/g' $nginx_config
    fi

    # nginx 설정 리로드
    # docker exec newcodes-nginx nginx -t && docker exec newcodes-nginx nginx -s reload
    docker restart newcodes-nginx && docker exec newcodes-nginx nginx -t
    log "nginx 재시작 완료"
}

# 이전 버전 컨테이너 정리
cleanup_old_container() {
    local container_name=$1

    if docker ps -a --format "table {{.Names}}" | grep -q "$container_name"; then
        log "이전 컨테이너 정리: $container_name"
        docker stop $container_name || true
        docker rm $container_name || true
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

# 메인 배포 함수
deploy() {
    local target=$1

    log "=== git 변경 내용 가져오기 ==="
    sudo git restore nginx/default.conf
    git pull origin main

    log "=== Blue-Green 무중단 배포 시작 ==="

    if [ -n "$target" ]; then
        if [ "$target" != "blue" ] && [ "$target" != "green" ]; then
            error "배포 대상은 'blue' 또는 'green'만 가능합니다: $target"
        fi
        NEW_ACTIVE="$target"
    else
        # 대상 미지정 시 현재 활성 서버의 반대쪽 자동 선택
        CURRENT_ACTIVE=$(get_active_server)
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
    health_check $NEW_CONTAINER

    # 3. nginx 업스트림 전환
    update_nginx_upstream $NEW_ACTIVE

    # 4. 이전 컨테이너 정리 (옵션)
    log "이전 컨테이너($OLD_CONTAINER)를 제거하겠습니다." 
    cleanup_old_container $OLD_CONTAINER
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
    log "=== 롤백 시작 ==="

    CURRENT_ACTIVE=$(get_active_server)

    if [ "$CURRENT_ACTIVE" = "blue" ]; then
        ROLLBACK_TO="green"
    else
        ROLLBACK_TO="blue"
    fi

    log "롤백 대상: $ROLLBACK_TO"

    # 이전 컨테이너가 실행 중인지 확인
    if ! docker ps --format "table {{.Names}}" | grep -q "newcodes-backend-$ROLLBACK_TO"; then
        error "롤백할 컨테이너(newcodes-backend-$ROLLBACK_TO)가 실행 중이지 않습니다"
    fi

    # nginx 업스트림 전환
    update_nginx_upstream $ROLLBACK_TO

    log "=== 롤백 완료 ==="
    log "활성 서버: $ROLLBACK_TO"
}

# 상태 확인 함수
status() {
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
        if docker ps --format "table {{.Names}}" | grep -q "$container"; then
            container_ip=$(docker inspect --format='{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $container 2>/dev/null)
            if [ -n "$container_ip" ] && curl -f -s http://$container_ip:8080/actuator/health >/dev/null 2>&1; then
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

# 사용법 출력
usage() {
    echo "Usage: $0 {deploy [blue|green]|rollback|status}"
    echo ""
    echo "Commands:"
    echo "  deploy [blue|green] - 새 버전으로 무중단 배포 (대상 미지정 시 자동 선택)"
    echo "  rollback - 이전 버전으로 롤백"
    echo "  status   - 현재 배포 상태 확인"
    exit 1
}

# 메인 실행부
case "$1" in
    deploy)
        deploy "$2"
        ;;
    rollback)
        rollback
        ;;
    status)
        status
        ;;
    *)
        usage
        ;;
esac