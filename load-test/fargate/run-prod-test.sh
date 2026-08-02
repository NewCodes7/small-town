#!/usr/bin/env bash
# 운영 서버(newcodes.net) 대상 Fargate 부하테스트 원커맨드 진입점.
# 전제: load-test/fargate/MOCK_SERVICE_SETUP.md의 1회 세팅(상시 llm-mock ECS 서비스,
# RAG_CHAT_LOADTEST_ENABLED 상시 활성, nginx X-LoadTest-Token bypass 등록)이 끝나 있어야 한다.
#
# run-task.sh의 얇은 래퍼 — mock 서비스 헬스체크만 먼저 하고, rag-answer 시나리오는
# RAG_PATH를 mock 전용 엔드포인트로 기본 지정한 뒤 나머지 인자를 그대로 넘긴다.
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
ARGS=()

# -s 값과 -e RAG_PATH= 지정 여부만 미리 살펴보고, 원본 인자는 그대로 run-task.sh에 전달한다.
while [[ $# -gt 0 ]]; do
  case "$1" in
    -s) SCENARIO="$2"; ARGS+=("$1" "$2"); shift 2 ;;
    -e)
      ARGS+=("$1" "$2")
      [[ "$2" == RAG_PATH=* ]] && RAG_PATH_SET=1
      shift 2 ;;
    *) ARGS+=("$1"); shift ;;
  esac
done

echo "mock 서비스 확인 중: cluster=$MOCK_CLUSTER service=$MOCK_SERVICE"
RUNNING=$(aws ecs describe-services --cluster "$MOCK_CLUSTER" --services "$MOCK_SERVICE" \
  --query 'services[0].runningCount' --output text 2>/dev/null || echo "0")

if [[ "$RUNNING" == "None" || "$RUNNING" -lt 1 ]]; then
  echo "llm-mock 서비스가 떠 있지 않습니다 (runningCount=$RUNNING)." >&2
  echo "MOCK_SERVICE_SETUP.md 절차가 끝났는지, cluster/service 이름이 맞는지 확인하세요." >&2
  echo "  aws ecs describe-services --cluster $MOCK_CLUSTER --services $MOCK_SERVICE" >&2
  exit 1
fi
echo "llm-mock 정상 (runningCount=$RUNNING)"

if [[ "$SCENARIO" == "rag-answer" && "$RAG_PATH_SET" == "0" ]]; then
  ARGS+=(-e "RAG_PATH=/api/rag/answer/loadtest")
fi

exec ./run-task.sh "${ARGS[@]}"
