#!/usr/bin/env bash
# 운영 서버(newcodes.net) 대상 Fargate 부하테스트 원커맨드 진입점.
# 전제: load-test/fargate/MOCK_SERVICE_SETUP.md의 1회 세팅(llm-mock ECS 서비스 등록,
# RAG_CHAT_LOADTEST_ENABLED 상시 활성, nginx X-LoadTest-Token bypass 등록)이 끝나 있어야 한다.
#
# run-task.sh의 래퍼 — llm-mock은 평시 desired-count 0(미기동)이 기본이다. 이 스크립트가
# 꺼져 있는 걸 감지하면 직접 desired-count 1로 올려 기동을 기다린 뒤 테스트를 진행하고,
# k6 태스크 종료 후(trap) 이 실행이 직접 올린 경우에만 다시 0으로 내린다 — 이미 떠 있었다면
# (수동 기동/동시 실행 등) 건드리지 않는다. rag-answer 시나리오는 RAG_PATH를 mock 전용
# 엔드포인트로 기본 지정한 뒤 나머지 인자를 그대로 넘긴다.
#
# 사용법: run-task.sh와 동일
#   ./run-prod-test.sh -s rag-answer -v 10 -d 10m -e MODE=cache-miss
#   ./run-prod-test.sh -s search-hybrid -r 40 -d 10m
#   ./run-prod-test.sh -s spike --dry-run
set -euo pipefail

cd "$(dirname "$0")"

if [[ -f env ]]; then
  set -a
  # shellcheck disable=SC1091
  . ./env
  set +a
fi

: "${LT_CLUSTER:?fargate/env에 LT_CLUSTER 설정 필요 (MOCK_SERVICE_SETUP.md 9번 참고)}"
MOCK_CLUSTER="${LT_MOCK_CLUSTER:-$LT_CLUSTER}"
MOCK_SERVICE="${LT_MOCK_SERVICE:-llm-mock}"

SCENARIO=""
RAG_PATH_SET=0
DRY_RUN=0
ARGS=()

# -s 값, -e RAG_PATH= 지정 여부, --dry-run만 미리 살펴보고, 원본 인자는 그대로 run-task.sh에 전달한다.
while [[ $# -gt 0 ]]; do
  case "$1" in
    -s) SCENARIO="$2"; ARGS+=("$1" "$2"); shift 2 ;;
    -e)
      ARGS+=("$1" "$2")
      [[ "$2" == RAG_PATH=* ]] && RAG_PATH_SET=1
      shift 2 ;;
    --dry-run) DRY_RUN=1; ARGS+=("$1"); shift ;;
    *) ARGS+=("$1"); shift ;;
  esac
done

SCALED_UP_BY_US=0

cleanup() {
  if [[ "$SCALED_UP_BY_US" == "1" ]]; then
    echo "llm-mock 서비스를 desired-count 0으로 되돌리는 중..."
    aws ecs update-service --cluster "$MOCK_CLUSTER" --service "$MOCK_SERVICE" \
      --desired-count 0 >/dev/null
  fi
}
trap cleanup EXIT

echo "mock 서비스 확인 중: cluster=$MOCK_CLUSTER service=$MOCK_SERVICE"

if [[ "$DRY_RUN" == "1" ]]; then
  echo "(dry-run) mock 기동/종료는 건너뛰고 run-task.sh만 dry-run으로 호출합니다."
else
  DESIRED=$(aws ecs describe-services --cluster "$MOCK_CLUSTER" --services "$MOCK_SERVICE" \
    --query 'services[0].desiredCount' --output text 2>/dev/null || echo "0")

  if [[ "$DESIRED" == "None" || "$DESIRED" -lt 1 ]]; then
    echo "llm-mock 평시 미기동 상태(desiredCount=$DESIRED) — 이 실행을 위해 기동합니다."
    aws ecs update-service --cluster "$MOCK_CLUSTER" --service "$MOCK_SERVICE" \
      --desired-count 1 >/dev/null
    SCALED_UP_BY_US=1
    echo "서비스 안정화 대기 중..."
    aws ecs wait services-stable --cluster "$MOCK_CLUSTER" --services "$MOCK_SERVICE"
  fi

  RUNNING=$(aws ecs describe-services --cluster "$MOCK_CLUSTER" --services "$MOCK_SERVICE" \
    --query 'services[0].runningCount' --output text 2>/dev/null || echo "0")

  if [[ "$RUNNING" == "None" || "$RUNNING" -lt 1 ]]; then
    echo "llm-mock 서비스가 떠 있지 않습니다 (runningCount=$RUNNING)." >&2
    echo "MOCK_SERVICE_SETUP.md 절차가 끝났는지, cluster/service 이름이 맞는지 확인하세요." >&2
    echo "  aws ecs describe-services --cluster $MOCK_CLUSTER --services $MOCK_SERVICE" >&2
    exit 1
  fi
  echo "llm-mock 정상 (runningCount=$RUNNING)"
fi

if [[ "$SCENARIO" == "rag-answer" && "$RAG_PATH_SET" == "0" ]]; then
  ARGS+=(-e "RAG_PATH=/api/rag/answer/loadtest")
fi

./run-task.sh "${ARGS[@]}" --wait
