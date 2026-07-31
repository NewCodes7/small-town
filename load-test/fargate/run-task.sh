#!/usr/bin/env bash
# Fargate에서 k6 task를 병렬 실행한다.
#
# 사용법:
#   ./run-task.sh -s <scenario> [-n <task수>] [-r <총RPS>] [-v <총VUS>] [-d <duration>] \
#                 [-e KEY=VAL]... [--public] [--dry-run]
#
# 예:
#   ./run-task.sh -s search-hybrid -n 4 -r 40 -d 10m          # 4개 task가 각 10 RPS
#   ./run-task.sh -s rag-answer -n 2 -v 10 -e MODE=cache-miss # 2개 task가 각 VU 5
#   ./run-task.sh -s rate-limit-check --public                # 임의 public IP (bypass 미등록)
#   ./run-task.sh -s spike --dry-run                          # aws cli 커맨드만 출력
#
# 설정: fargate/env 파일(env.example 참고)에서 LT_* 값을 읽는다.
#  - 기본 네트워크: private 서브넷 + NAT (고정 EIP → nginx geo bypass 등록 대상)
#  - --public: public 서브넷 + assignPublicIp (rate-limit-check 전용)
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
PUBLIC=0
DRY_RUN=0
EXTRA_ENVS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s) SCENARIO="$2"; shift 2 ;;
    -n) COUNT="$2"; shift 2 ;;
    -r) TOTAL_RATE="$2"; shift 2 ;;
    -v) TOTAL_VUS="$2"; shift 2 ;;
    -d) DURATION="$2"; shift 2 ;;
    -e) EXTRA_ENVS+=("$2"); shift 2 ;;
    --public) PUBLIC=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done

[[ -n "$SCENARIO" ]] || { echo "-s <scenario> 필수" >&2; exit 1; }
: "${LT_CLUSTER:?fargate/env에 LT_CLUSTER 설정 필요}"
: "${LT_SG:?fargate/env에 LT_SG 설정 필요}"
: "${LT_BASE_URL:?fargate/env에 LT_BASE_URL 설정 필요}"

if [[ "$PUBLIC" == "1" ]]; then
  : "${LT_SUBNETS_PUBLIC:?--public 모드는 LT_SUBNETS_PUBLIC 필요}"
  SUBNETS="$LT_SUBNETS_PUBLIC"
  ASSIGN_IP="ENABLED"
else
  : "${LT_SUBNETS_PRIVATE:?LT_SUBNETS_PRIVATE 설정 필요}"
  SUBNETS="$LT_SUBNETS_PRIVATE"
  ASSIGN_IP="DISABLED"
fi

TEST_RUN_ID="$(date +%Y%m%d-%H%M%S)"
TASK_ARNS=()

echo "TEST_RUN_ID=$TEST_RUN_ID scenario=$SCENARIO tasks=$COUNT public=$PUBLIC"

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

  cmd_entries='"run"'
  if [[ -n "${LT_PROM_RW_URL:-}" ]]; then
    env_entries+=",{\"name\":\"K6_PROMETHEUS_RW_SERVER_URL\",\"value\":\"$LT_PROM_RW_URL\"}"
    env_entries+=",{\"name\":\"K6_PROMETHEUS_RW_TREND_STATS\",\"value\":\"p(50),p(95),p(99),avg,min,max\"}"
    cmd_entries+=',"--out","experimental-prometheus-rw"'
  fi
  cmd_entries+=",\"--tag\",\"testid=$TEST_RUN_ID\""
  cmd_entries+=",\"scenarios/$SCENARIO.js\""

  for kv in ${EXTRA_ENVS[@]+"${EXTRA_ENVS[@]}"}; do
    env_entries+=",{\"name\":\"${kv%%=*}\",\"value\":\"${kv#*=}\"}"
  done

  overrides="{\"containerOverrides\":[{\"name\":\"k6\",\"command\":[$cmd_entries],\"environment\":[$env_entries]}]}"
  netconf="awsvpcConfiguration={subnets=[$SUBNETS],securityGroups=[$LT_SG],assignPublicIp=$ASSIGN_IP}"

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
fi
