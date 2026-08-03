#!/usr/bin/env bash
# Fargate에서 k6 task를 병렬 실행한다.
#
# 사용법:
#   ./run-task.sh -s <scenario> [-n <task수>] [-r <총RPS>] [-v <총VUS>] [-d <duration>] \
#                 [-e KEY=VAL]... [--no-bypass] [--dry-run] [--wait]
#
# 예:
#   ./run-task.sh -s search-hybrid -n 4 -r 40 -d 10m          # 4개 task가 각 10 RPS
#   ./run-task.sh -s rag-answer -n 2 -v 10 -e MODE=cache-miss # 2개 task가 각 VU 5
#   ./run-task.sh -s rate-limit-check --no-bypass             # bypass 토큰 없이 (rate limit 검증용)
#   ./run-task.sh -s spike --dry-run                          # aws cli 커맨드만 출력
#   ./run-task.sh -s rag-answer -v 5 --wait                   # task 종료까지 블로킹 대기
#
# 설정: fargate/env 파일(env.example 참고)에서 LT_* 값을 읽는다.
#  - 네트워크: 항상 LT_SUBNETS_PUBLIC + assignPublicIp=ENABLED (고정 IP 불필요 — NAT Gateway 없음)
#  - LT_BYPASS_TOKEN이 설정돼 있으면 LOADTEST_BYPASS_TOKEN env로 태스크에 주입 → k6가
#    X-LoadTest-Token 헤더에 실어 보내 nginx rate limit을 우회한다 (README "Rate limit 예외" 참고)
#  - -s rate-limit-check 또는 --no-bypass 지정 시 토큰 주입을 생략한다(의도적으로 제한을 받게 함)
# 총 RPS(-r)/총 VUS(-v)는 task 수로 나눠 task별 RATE/VUS로 주입한다 (나머지는 앞 task에 배분).
set -euo pipefail

cd "$(dirname "$0")"

if [[ -f env ]]; then
  set -a
  # shellcheck disable=SC1091
  . ./env
  set +a
fi

SCENARIO=""
COUNT=1
TOTAL_RATE=""
TOTAL_VUS=""
DURATION=""
NO_BYPASS=0
DRY_RUN=0
WAIT=0
EXTRA_ENVS=()
BYPASS_TOKEN_SET=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s) SCENARIO="$2"; shift 2 ;;
    -n) COUNT="$2"; shift 2 ;;
    -r) TOTAL_RATE="$2"; shift 2 ;;
    -v) TOTAL_VUS="$2"; shift 2 ;;
    -d) DURATION="$2"; shift 2 ;;
    -e)
      EXTRA_ENVS+=("$2")
      [[ "$2" == LOADTEST_BYPASS_TOKEN=* ]] && BYPASS_TOKEN_SET=1
      shift 2 ;;
    --no-bypass) NO_BYPASS=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --wait) WAIT=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done

[[ -n "$SCENARIO" ]] || { echo "-s <scenario> 필수" >&2; exit 1; }
: "${LT_CLUSTER:?fargate/env에 LT_CLUSTER 설정 필요}"
: "${LT_SG:?fargate/env에 LT_SG 설정 필요}"
: "${LT_BASE_URL:?fargate/env에 LT_BASE_URL 설정 필요}"
: "${LT_SUBNETS_PUBLIC:?fargate/env에 LT_SUBNETS_PUBLIC 설정 필요}"

TEST_RUN_ID="$(date +%Y%m%d-%H%M%S)"
TASK_ARNS=()

echo "TEST_RUN_ID=$TEST_RUN_ID scenario=$SCENARIO tasks=$COUNT"

for ((i = 1; i <= COUNT; i++)); do
  env_entries="{\"name\":\"BASE_URL\",\"value\":\"$LT_BASE_URL\"}"
  env_entries+=",{\"name\":\"TEST_RUN_ID\",\"value\":\"$TEST_RUN_ID\"}"
  env_entries+=",{\"name\":\"INSTANCE_ID\",\"value\":\"$i\"}"

  if [[ -n "$TOTAL_RATE" ]]; then
    rate_i=$((TOTAL_RATE / COUNT + (i <= TOTAL_RATE % COUNT ? 1 : 0)))
    env_entries+=",{\"name\":\"RATE\",\"value\":\"$rate_i\"}"
  fi
  if [[ -n "$TOTAL_VUS" ]]; then
    vus_i=$((TOTAL_VUS / COUNT + (i <= TOTAL_VUS % COUNT ? 1 : 0)))
    env_entries+=",{\"name\":\"VUS\",\"value\":\"$vus_i\"}"
  fi
  [[ -n "$DURATION" ]] && env_entries+=",{\"name\":\"DURATION\",\"value\":\"$DURATION\"}"

  if [[ "$SCENARIO" != "rate-limit-check" && "$NO_BYPASS" == "0" && "$BYPASS_TOKEN_SET" == "0" \
        && -n "${LT_BYPASS_TOKEN:-}" ]]; then
    env_entries+=",{\"name\":\"LOADTEST_BYPASS_TOKEN\",\"value\":\"$LT_BYPASS_TOKEN\"}"
  fi

  cmd_entries='"run"'
  if [[ -n "${LT_PROM_RW_URL:-}" ]]; then
    env_entries+=",{\"name\":\"K6_PROMETHEUS_RW_SERVER_URL\",\"value\":\"$LT_PROM_RW_URL\"}"
    env_entries+=",{\"name\":\"K6_PROMETHEUS_RW_TREND_STATS\",\"value\":\"p(50),p(95),p(99),avg,min,max\"}"
    # nginx /loadtest-prom/ 프록시가 같은 토큰으로 게이트함 (LT_PROM_RW_URL이 그 경로를 가리킬 때만 의미 있음)
    if [[ -n "${LT_BYPASS_TOKEN:-}" ]]; then
      env_entries+=",{\"name\":\"K6_PROMETHEUS_RW_HTTP_HEADERS\",\"value\":\"X-LoadTest-Token:$LT_BYPASS_TOKEN\"}"
    fi
    cmd_entries+=',"--out","experimental-prometheus-rw"'
  fi
  cmd_entries+=",\"--tag\",\"testid=$TEST_RUN_ID\""
  cmd_entries+=",\"scenarios/$SCENARIO.js\""

  for kv in ${EXTRA_ENVS[@]+"${EXTRA_ENVS[@]}"}; do
    env_entries+=",{\"name\":\"${kv%%=*}\",\"value\":\"${kv#*=}\"}"
  done

  overrides="{\"containerOverrides\":[{\"name\":\"k6\",\"command\":[$cmd_entries],\"environment\":[$env_entries]}]}"
  netconf="awsvpcConfiguration={subnets=[$LT_SUBNETS_PUBLIC],securityGroups=[$LT_SG],assignPublicIp=ENABLED}"

  if [[ "$DRY_RUN" == "1" ]]; then
    echo "--- task $i/$COUNT (dry-run) ---"
    echo aws ecs run-task --cluster "$LT_CLUSTER" --launch-type FARGATE --count 1 \
      --task-definition newcodes-loadtest \
      --network-configuration "'$netconf'" \
      --overrides "'$overrides'"
    continue
  fi

  arn=$(aws ecs run-task \
    --cluster "$LT_CLUSTER" \
    --launch-type FARGATE \
    --count 1 \
    --task-definition newcodes-loadtest \
    --network-configuration "$netconf" \
    --overrides "$overrides" \
    --query 'tasks[0].taskArn' --output text)
  echo "task $i/$COUNT started: $arn"
  TASK_ARNS+=("$arn")
done

if [[ "$DRY_RUN" == "0" && ${#TASK_ARNS[@]} -gt 0 ]]; then
  echo ""
  echo "로그 확인:  aws logs tail /ecs/newcodes-loadtest --follow"
  echo "종료 대기:  aws ecs wait tasks-stopped --cluster $LT_CLUSTER --tasks ${TASK_ARNS[*]}"
  echo "Grafana:   testid=$TEST_RUN_ID 라벨로 필터"

  if [[ "$WAIT" == "1" ]]; then
    echo "task 종료 대기 중..."
    aws ecs wait tasks-stopped --cluster "$LT_CLUSTER" --tasks "${TASK_ARNS[@]}"
    echo "task 전체 종료됨"
  fi
fi
