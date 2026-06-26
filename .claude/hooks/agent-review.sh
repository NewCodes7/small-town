#!/usr/bin/env bash
# ② agent hook — claude -p(Sonnet) 로 변경 diff 의 로직/요구사항 충족을 검토.
# exit 0: 통과(또는 리뷰 불가 시 안전하게 통과)
# exit 1: high 이슈 존재 → stdout 에 목록 출력
set -uo pipefail

# 하위 claude 가 프로젝트 훅을 재트리거하지 않도록 보장
export IN_VERIFY_GATE=1

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}"
[ -d "$PROJECT_DIR" ] && cd "$PROJECT_DIR" || exit 0

BASE="${1:-HEAD}"
git rev-parse --verify --quiet "$BASE" >/dev/null 2>&1 || BASE=HEAD

# claude 미설치 → 결정론적 게이트가 1차 방어이므로 통과
command -v claude >/dev/null 2>&1 || exit 0

# 세션 시작 시점 기존 WIP 은 제외 (pathspec exclude)
excludes=()
if [ -f .claude/.cache/baseline-files ]; then
  while IFS= read -r f; do [ -n "$f" ] && excludes+=(":(exclude)$f"); done < .claude/.cache/baseline-files
fi
is_baseline() { [ -f .claude/.cache/baseline-files ] && grep -qxF "$1" .claude/.cache/baseline-files; }

# 추적 변경분 + 추적되지 않은 새 파일(전체를 추가로 표시) 합산, 기존 WIP 제외
diff=$( {
  git diff "$BASE" -- src/ "${excludes[@]}"
  for f in $(git ls-files --others --exclude-standard -- src/ | grep '\.java$'); do
    is_baseline "$f" && continue
    git diff --no-index -- /dev/null "$f" 2>/dev/null
  done
} | head -c 60000)
[ -z "$diff" ] && exit 0

prompt="당신은 꼼꼼한 코드 리뷰어입니다. 아래 git diff 를 검토하세요.
컴파일/포맷/SAST/테스트(결정론적 도구)는 이미 통과했으니, 그것으로 못 잡는 것에 집중하세요:
- 요구사항/의도 충족 여부, 로직 오류, 놓친 엣지 케이스
- 프로젝트 규칙 위반: 엔티티 직접 노출 금지(DTO 사용), N+1 방지(fetch join/entity graph),
  파라미터화 쿼리(SQL injection 방지), CSS/JS 인라인 금지, 모듈별 예외 처리 패턴
반드시 아래 JSON 만 출력하세요(다른 설명/마크다운 금지):
{\"pass\": true|false, \"issues\":[{\"severity\":\"high|low\",\"file\":\"\",\"detail\":\"\"}]}
high 는 '반드시 고쳐야 하는' 문제에만 사용하세요. 사소한 개선은 low.

=== git diff ===
$diff"

# 최대 4분. 초과/오류/빈 응답 → 게이트를 막지 않음(결정론적 게이트가 1차 방어)
resp=$(printf '%s' "$prompt" | timeout 240 claude -p --model sonnet 2>/dev/null || true)
[ -z "$resp" ] && exit 0

printf '%s' "$resp" | python3 "$PROJECT_DIR/.claude/hooks/review-parse.py"
