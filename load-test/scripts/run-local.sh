#!/usr/bin/env bash
# 로컬에서 k6 커스텀 이미지(xk6-sse 포함)를 빌드하고 시나리오를 실행한다.
#
# 사용법:
#   ./scripts/run-local.sh <scenario> [-e KEY=VAL]... [--network <docker-net>] [--prom <remote-write-url>]
#
# 예:
#   ./scripts/run-local.sh baseline -e DURATION=30s
#   ./scripts/run-local.sh search-hybrid -e RATE=5 -e SEARCH_BASE_P95_MS=400
#   BASE_URL=http://host.docker.internal ./scripts/run-local.sh rate-limit-check   # nginx 경유
#   ./scripts/run-local.sh baseline --network small-town_app-network --prom http://prometheus:9090/api/v1/write
#
# BASE_URL 기본값: http://host.docker.internal:8080 (호스트에서 띄운 백엔드 직결)
set -euo pipefail

cd "$(dirname "$0")/.."

SCENARIO="${1:?usage: run-local.sh <scenario> [-e K=V]... [--network <net>] [--prom <rw-url>]}"
shift

if [[ ! -f "scenarios/${SCENARIO}.js" ]]; then
  echo "scenarios/${SCENARIO}.js 없음. 가능한 시나리오:" >&2
  ls scenarios/ | sed 's/\.js$//' >&2
  exit 1
fi

IMAGE=newcodes/k6-sse:local
ENV_ARGS=(-e "BASE_URL=${BASE_URL:-http://host.docker.internal:8080}")
DOCKER_ARGS=(--rm --add-host=host.docker.internal:host-gateway)
# TTY가 있으면 할당 — devcontainer(docker-outside-of-docker)에서는 TTY 없이 attach하면
# 출력 스트림이 유실되는 문제가 있다. 파이프 실행 시엔 detach + docker logs 사용 권장.
[[ -t 1 ]] && DOCKER_ARGS+=(-t)
K6_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    -e)
      ENV_ARGS+=(-e "$2"); shift 2 ;;
    --network)
      DOCKER_ARGS+=(--network "$2"); shift 2 ;;
    --prom)
      ENV_ARGS+=(-e "K6_PROMETHEUS_RW_SERVER_URL=$2" -e "K6_PROMETHEUS_RW_TREND_STATS=p(50),p(95),p(99),avg,min,max")
      K6_ARGS+=(--out experimental-prometheus-rw)
      shift 2 ;;
    *)
      echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done

docker build -t "$IMAGE" -f docker/Dockerfile .
exec docker run "${DOCKER_ARGS[@]}" "${ENV_ARGS[@]}" "$IMAGE" run "${K6_ARGS[@]}" "scenarios/${SCENARIO}.js"
