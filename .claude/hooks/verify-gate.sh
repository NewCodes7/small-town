#!/usr/bin/env bash
# Stop 훅 — 결정론적 검증 게이트(① command hook) + 에이전트 리뷰(② agent hook).
# 단계별 fail-fast: 실패 시 stderr 에 요약 + exit 2 → Claude 가 고치도록 되먹임.
# 전부 통과 시 exit 0 → 턴 종료 허용. 무한 루프는 시도 카운터로 차단.
set -uo pipefail

# claude -p 재귀 차단 (agent-review.sh 가 IN_VERIFY_GATE=1 로 호출)
[ -n "${IN_VERIFY_GATE:-}" ] && exit 0

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}"
[ -d "$PROJECT_DIR" ] && cd "$PROJECT_DIR" || exit 0

input=$(cat)

CACHE_DIR="$PROJECT_DIR/.claude/.cache"
mkdir -p "$CACHE_DIR"
ATTEMPTS_FILE="$CACHE_DIR/verify-attempts"
MAX_ATTEMPTS=5
attempts=$(cat "$ATTEMPTS_FILE" 2>/dev/null || echo 0)
[[ "$attempts" =~ ^[0-9]+$ ]] || attempts=0

# 루프 가드: 연속 실패가 임계를 넘으면 사람 개입 요청하고 종료(무한 루프 방지)
if [ "$attempts" -ge "$MAX_ATTEMPTS" ]; then
  rm -f "$ATTEMPTS_FILE"
  jq -n --arg n "$MAX_ATTEMPTS" \
    '{systemMessage:("검증 게이트가 \($n)회 연속 실패했습니다. 자동 수정 한계 — 직접 확인이 필요합니다. (게이트 미통과 상태로 종료)")}'
  exit 0
fi

# 실패 처리: $1=단계명, $2=상세. 직접 호출(파이프 금지)해야 exit 2 가 스크립트를 종료시킴.
fail() {
  echo "$((attempts + 1))" > "$ATTEMPTS_FILE"
  {
    echo "❌ 검증 실패 [$1] — 수정 후 다시 시도하세요. (연속 실패 $((attempts + 1))/$MAX_ATTEMPTS)"
    echo "----------------------------------------"
    echo "$2"
  } >&2
  exit 2
}

# 변경 범위 판정 기준 ref (Spotless ratchetFrom 과 동일하게 HEAD = 커밋 전 작업분)
BASE=HEAD

# 세션 시작 시점 기존 WIP 목록(게이트 제외 대상)
BASELINE_FILE="$CACHE_DIR/baseline-files"

# src 변경 집합 = (작업트리 변경 ∪ base 대비 변경) − 기존 WIP
src_changes=$( { git status --porcelain -- src/ | awk '{print $NF}'; \
                 git diff --name-only "$BASE" -- src/; } 2>/dev/null | sort -u)
if [ -s "$BASELINE_FILE" ]; then
  src_changes=$(comm -23 <(printf '%s\n' "$src_changes") <(sort -u "$BASELINE_FILE"))
fi
# 순수 대화/조회 턴(세션 신규 src 변경 없음) → 게이트 스킵
if [ -z "$(printf '%s' "$src_changes" | tr -d '[:space:]')" ]; then
  rm -f "$ATTEMPTS_FILE"
  exit 0
fi

GRADLE="./gradlew --console=plain -q"

# 1) 컴파일 (main + test)
if ! out=$($GRADLE compileJava compileTestJava 2>&1); then
  fail "컴파일" "$(printf '%s' "$out" | tail -n 60)"
fi

# 2) 포맷/린트 — Spotless (ratchetFrom=origin/main → 변경 파일만)
if ! out=$($GRADLE spotlessCheck 2>&1); then
  fail "포맷/린트(Spotless)" "$(printf '%s' "$out" | tail -n 40)
→ ./gradlew spotlessApply 로 자동 포맷할 수 있습니다."
fi

# 3) SAST — SpotBugs + find-sec-bugs (ignoreFailures=true → 항상 리포트). 변경 라인만 판정.
$GRADLE spotbugsMain >/dev/null 2>&1 || true
if [ -f build/reports/spotbugs/main.xml ]; then
  if ! sast=$(python3 "$PROJECT_DIR/.claude/hooks/sast-filter.py" \
                build/reports/spotbugs/main.xml "$BASE" 2>/dev/null); then
    fail "SAST(SpotBugs/find-sec-bugs)" "$sast"
  fi
fi

# 4) 테스트 (통합 테스트 — DB 필요 → .env 로드)
[ -f "$PROJECT_DIR/.env" ] && { set -a; . "$PROJECT_DIR/.env"; set +a; }
warn=""
db_host="${POSTGRES_HOST:-postgres}"
db_port="${POSTGRES_PORT:-5432}"
# DB 미가용은 인프라 문제(코드 결함 아님) → 테스트 스킵하고 경고만 남김
if pg_isready -h "$db_host" -p "$db_port" -t 5 >/dev/null 2>&1 \
   || pg_isready -h localhost -p "$db_port" -t 5 >/dev/null 2>&1; then
  if ! out=$(./gradlew test -x jacocoTestReport --console=plain 2>&1); then
    detail=$(printf '%s' "$out" | grep -E 'FAILED|Tests:|> There were|expected:|but was:|Caused by:' | tail -n 60)
    [ -z "$detail" ] && detail=$(printf '%s' "$out" | tail -n 60)
    fail "테스트" "$detail"
  fi
else
  warn="⚠️ DB($db_host:$db_port) 미가용 → 통합 테스트를 건너뛰었습니다(코드 검증 미완)."
fi

# 5) 에이전트 리뷰 — 로직/요구사항/성능 충족 (결정론적 게이트가 못 잡는 것 보강)
if ! review=$(IN_VERIFY_GATE=1 bash "$PROJECT_DIR/.claude/hooks/agent-review.sh" "$BASE" 2>/dev/null); then
  fail "에이전트 리뷰(로직/요구사항/성능)" "$review"
fi
# 통과했어도 비차단 경고(WARN: …, 성능 등 심각도 low)는 systemMessage 로 노출
if [ -n "$review" ]; then
  review_block="🔎 에이전트 리뷰 경고(비차단):
$review"
  if [ -n "$warn" ]; then
    warn="$warn
$review_block"
  else
    warn="$review_block"
  fi
fi

# 전부 통과
rm -f "$ATTEMPTS_FILE"
[ -n "$warn" ] && jq -n --arg w "$warn" '{systemMessage:$w}'
exit 0
