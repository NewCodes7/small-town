#!/usr/bin/env bash
# 운영 서버(newcodes.net) 대상 Fargate 부하테스트 원커맨드 진입점.
# 전제: load-test/fargate/MOCK_SERVICE_SETUP.md의 1회 세팅(llm-mock ECS 서비스 등록,
# RAG_CHAT_LOADTEST_ENABLED 상시 활성, nginx X-LoadTest-Token bypass 등록)이 끝나 있어야 한다.
#
# run-task.sh의 래퍼 — llm-mock은 평시 desired-count 0(미기동)이 기본이다. 이 스크립트가
# 꺼져 있는 걸 감지하면 직접 desired-count 1로 올려 기동을 기다린 뒤 테스트를 진행하고,
# k6 태스크 종료 후(trap) 이 실행이 직접 올린 경우에만 다시 0으로 내린다 — 이미 떠 있었다면
# (수동 기동/동시 실행 등) 건드리지 않는다. rag-answer/search-journey 시나리오(둘 다 RAG를
# 호출)는 RAG_PATH를 mock 전용 엔드포인트로 기본 지정한 뒤 나머지 인자를 그대로 넘긴다.
#
# 사용법: run-task.sh와 동일
#   ./run-prod-test.sh -s rag-answer -v 10 -d 10m -e MODE=cache-miss
#   ./run-prod-test.sh -s search-hybrid -r 40 -d 10m
#   ./run-prod-test.sh -s spike --dry-run
# 오류 발생 시 즉시 종료(-e), 미정의 변수 참조 시 에러(-u), 파이프 중간 실패도 감지(-o pipefail)
set -euo pipefail

# 스크립트 파일 위치(fargate/) 기준으로 상대경로(env, run-task.sh)가 항상 동작하도록 이동
cd "$(dirname "$0")"

# fargate/env 파일이 있으면 그 안의 변수들(LT_CLUSTER 등)을 현재 쉘 환경변수로 로드
if [[ -f env ]]; then
  set -a
  # shellcheck disable=SC1091
  . ./env
  set +a
fi

# LT_CLUSTER는 필수값 — 없으면 에러 메시지와 함께 종료
: "${LT_CLUSTER:?fargate/env에 LT_CLUSTER 설정 필요 (MOCK_SERVICE_SETUP.md 9번 참고)}"
# mock 전용 클러스터/서비스가 별도로 지정 안 됐으면 부하테스트용 클러스터와 기본 서비스명을 재사용
MOCK_CLUSTER="${LT_MOCK_CLUSTER:-$LT_CLUSTER}"
MOCK_SERVICE="${LT_MOCK_SERVICE:-llm-mock}"

SCENARIO=""      # -s로 전달된 시나리오 이름 (예: rag-answer, search-hybrid)
RAG_PATH_SET=0    # 사용자가 -e RAG_PATH=... 를 이미 직접 지정했는지 여부
DRY_RUN=0         # --dry-run 여부 (실제 mock 기동/종료 없이 run-task.sh만 dry-run 호출)
ARGS=()           # run-task.sh에 그대로 전달할 원본 인자 목록

# -s 값, -e RAG_PATH= 지정 여부, --dry-run만 미리 살펴보고, 원본 인자는 그대로 run-task.sh에 전달한다.
while [[ $# -gt 0 ]]; do
  case "$1" in
    # -s <시나리오>: 시나리오 이름을 SCENARIO에 기억해두면서 원본 인자도 ARGS에 그대로 보존
    -s) SCENARIO="$2"; ARGS+=("$1" "$2"); shift 2 ;;
    # -e KEY=VALUE: 사용자가 이미 RAG_PATH를 직접 지정했으면 뒤에서 기본값을 덧붙이지 않도록 플래그만 세팅
    -e)
      ARGS+=("$1" "$2")
      [[ "$2" == RAG_PATH=* ]] && RAG_PATH_SET=1
      shift 2 ;;
    # --dry-run: mock 기동/종료 로직을 건너뛰기 위한 플래그
    --dry-run) DRY_RUN=1; ARGS+=("$1"); shift ;;
    # 그 외 나머지 인자(-v, -r, -d 등)는 해석하지 않고 그대로 통과
    *) ARGS+=("$1"); shift ;;
  esac
done

# 이 스크립트가 직접 desired-count를 0→1로 올렸는지 여부 (cleanup에서 되돌릴지 판단하는 기준)
SCALED_UP_BY_US=0

# 스크립트가 어떤 이유로 종료되든(정상/에러/Ctrl+C) 항상 실행되는 정리 함수.
# 우리가 기동시킨 경우에만 다시 0으로 내려서, 원래 떠 있던 서비스는 건드리지 않는다.
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
  # dry-run에서는 실제 AWS 호출(기동/대기/확인) 없이 바로 run-task.sh의 dry-run으로 넘어간다
  echo "(dry-run) mock 기동/종료는 건너뛰고 run-task.sh만 dry-run으로 호출합니다."
else
  # 현재 desiredCount(=ECS가 유지하려는 태스크 수) 조회. 서비스가 없으면 "0"으로 취급
  DESIRED=$(aws ecs describe-services --cluster "$MOCK_CLUSTER" --services "$MOCK_SERVICE" \
    --query 'services[0].desiredCount' --output text 2>/dev/null || echo "0")

  if [[ "$DESIRED" == "None" || "$DESIRED" -lt 1 ]]; then
    # 평시 미기동 상태 → 이 테스트를 위해 1개로 올리고, services-stable로 기동 완료까지 블로킹 대기
    echo "llm-mock 평시 미기동 상태(desiredCount=$DESIRED) — 이 실행을 위해 기동합니다."
    aws ecs update-service --cluster "$MOCK_CLUSTER" --service "$MOCK_SERVICE" \
      --desired-count 1 >/dev/null
    SCALED_UP_BY_US=1
    echo "서비스 안정화 대기 중..."
    aws ecs wait services-stable --cluster "$MOCK_CLUSTER" --services "$MOCK_SERVICE"
  fi

  # desiredCount와 별개로 실제 실행 중인 태스크 수(runningCount)를 재확인 —
  # stable 대기 후에도 비정상 상태일 수 있으므로 테스트 진행 전 최종 검증
  RUNNING=$(aws ecs describe-services --cluster "$MOCK_CLUSTER" --services "$MOCK_SERVICE" \
    --query 'services[0].runningCount' --output text 2>/dev/null || echo "0")

  if [[ "$RUNNING" == "None" || "$RUNNING" -lt 1 ]]; then
    # 기동 실패/서비스 미존재 등으로 태스크가 안 떠 있으면 테스트를 진행하지 않고 즉시 종료
    echo "llm-mock 서비스가 떠 있지 않습니다 (runningCount=$RUNNING)." >&2
    echo "MOCK_SERVICE_SETUP.md 절차가 끝났는지, cluster/service 이름이 맞는지 확인하세요." >&2
    echo "  aws ecs describe-services --cluster $MOCK_CLUSTER --services $MOCK_SERVICE" >&2
    exit 1
  fi
  echo "llm-mock 정상 (runningCount=$RUNNING)"
fi

# RAG를 호출하는 시나리오(rag-answer, search-journey, ramp-limit-finder-rag)인데 사용자가
# RAG_PATH를 직접 지정하지 않았으면, mock 서버 전용 loadtest 엔드포인트를 기본값으로 추가한다
if [[ ( "$SCENARIO" == "rag-answer" || "$SCENARIO" == "search-journey" || "$SCENARIO" == "ramp-limit-finder-rag" ) \
      && "$RAG_PATH_SET" == "0" ]]; then
  ARGS+=(-e "RAG_PATH=/api/rag/answer/loadtest")
fi

# 실제 k6 부하테스트 실행 — 원본 인자를 그대로 넘기고 --wait로 태스크 종료까지 대기(이후 trap의 cleanup 실행)
./run-task.sh "${ARGS[@]}" --wait
