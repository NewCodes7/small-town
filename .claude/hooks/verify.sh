#!/usr/bin/env bash
# 수동 검증 러너 — 검증 게이트(verify-gate.sh)와 같은 단계를 사람이 직접 돌려본다.
#
#   .claude/hooks/verify.sh            # 컴파일 + Spotless + SAST (빠름, 비용 없음)
#   .claude/hooks/verify.sh --fix      # 위와 동일하되 Spotless 자동 수정(spotlessApply) 먼저
#   .claude/hooks/verify.sh -t         # + 통합 테스트 (DB 필요, .env 자동 로드)
#   .claude/hooks/verify.sh -r         # + AI 리뷰 (claude -p sonnet, 토큰 비용)
#   .claude/hooks/verify.sh -a         # 전부 (= Stop 게이트와 동일 범위)
#   .claude/hooks/verify.sh --base <ref>   # 변경 기준 ref (기본 HEAD)
#
# 기존 WIP 제외(.claude/.cache/baseline-files)는 게이트와 동일하게 적용된다.
set -uo pipefail

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}"
cd "$PROJECT_DIR" || { echo "프로젝트 루트를 찾지 못했습니다"; exit 2; }

BASE=HEAD
DO_FIX=0 DO_TESTS=0 DO_REVIEW=0
while [ $# -gt 0 ]; do
  case "$1" in
    --fix) DO_FIX=1 ;;
    -t|--tests) DO_TESTS=1 ;;
    -r|--review) DO_REVIEW=1 ;;
    -a|--all) DO_TESTS=1; DO_REVIEW=1 ;;
    --base) shift; BASE="${1:-HEAD}" ;;
    -h|--help) awk 'NR>1 && /^#/{sub(/^# ?/,"");print;next} NR>1{exit}' "$0"; exit 0 ;;
    *) echo "알 수 없는 옵션: $1 (--help 참고)"; exit 2 ;;
  esac
  shift
done

GRADLE="./gradlew --console=plain -q"
fail_count=0
green="\033[32m"; red="\033[31m"; dim="\033[2m"; rst="\033[0m"
[ -t 1 ] || { green=""; red=""; dim=""; rst=""; }

pass() { printf "  ${green}✓${rst} %s\n" "$1"; }
miss() { printf "  ${red}✗ %s${rst}\n" "$1"; fail_count=$((fail_count+1)); }
skip() { printf "  ${dim}– %s${rst}\n" "$1"; }
section() { printf "\n%s\n" "$1"; }

# 1) 컴파일
section "① 컴파일"
if out=$($GRADLE compileJava compileTestJava 2>&1); then
  pass "compileJava / compileTestJava"
else
  miss "컴파일 실패"; printf '%s\n' "$out" | tail -n 30
fi

# 2) Spotless
section "② 포맷/린트 (Spotless)"
if [ "$DO_FIX" = 1 ]; then
  $GRADLE spotlessApply >/dev/null 2>&1 && skip "spotlessApply 적용함"
fi
if out=$($GRADLE spotlessCheck 2>&1); then
  pass "spotlessCheck"
else
  miss "포맷 위반 (고치기: ./gradlew spotlessApply)"; printf '%s\n' "$out" | grep -E '\.java|violation' | tail -n 20
fi

# 3) SAST (SpotBugs + find-sec-bugs, SECURITY 카테고리·변경 라인만)
section "③ SAST (SpotBugs + find-sec-bugs)"
$GRADLE spotbugsMain >/dev/null 2>&1 || true
if [ -f build/reports/spotbugs/main.xml ]; then
  if sast=$(python3 "$PROJECT_DIR/.claude/hooks/sast-filter.py" build/reports/spotbugs/main.xml "$BASE" 2>/dev/null); then
    pass "변경된 코드에 보안 결함 없음"
  else
    miss "보안 결함 발견"; printf '%s\n' "$sast"
  fi
else
  skip "SpotBugs 리포트 없음 (컴파일 실패?)"
fi

# 4) 테스트 (옵션)
section "④ 테스트 (통합)"
if [ "$DO_TESTS" = 1 ]; then
  [ -f "$PROJECT_DIR/.env" ] && { set -a; . "$PROJECT_DIR/.env"; set +a; }
  db_host="${POSTGRES_HOST:-postgres}"; db_port="${POSTGRES_PORT:-5432}"
  if pg_isready -h "$db_host" -p "$db_port" -t 5 >/dev/null 2>&1 \
     || pg_isready -h localhost -p "$db_port" -t 5 >/dev/null 2>&1; then
    if out=$(./gradlew test -x jacocoTestReport --console=plain 2>&1); then
      pass "전체 테스트 통과"
    else
      miss "테스트 실패"; printf '%s\n' "$out" | grep -E 'FAILED|Tests:|expected:|but was:' | tail -n 30
    fi
  else
    skip "DB($db_host:$db_port) 미가용 → 테스트 스킵"
  fi
else
  skip "건너뜀 (-t 로 활성화)"
fi

# 5) AI 리뷰 (옵션)
section "⑤ AI 리뷰 (claude -p sonnet)"
if [ "$DO_REVIEW" = 1 ]; then
  if review=$(IN_VERIFY_GATE=1 bash "$PROJECT_DIR/.claude/hooks/agent-review.sh" "$BASE" 2>/dev/null); then
    pass "로직/요구사항 이슈 없음"
  else
    miss "리뷰 지적 사항"; printf '%s\n' "$review"
  fi
else
  skip "건너뜀 (-r 로 활성화)"
fi

# 요약
printf "\n────────────────────────\n"
if [ "$fail_count" -eq 0 ]; then
  printf "${green}검증 통과${rst} (base=%s)\n" "$BASE"; exit 0
else
  printf "${red}검증 실패 — %d개 단계${rst} (base=%s)\n" "$fail_count" "$BASE"; exit 1
fi
