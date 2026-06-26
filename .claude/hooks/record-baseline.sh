#!/usr/bin/env bash
# SessionStart 훅 — 세션 시작 시점에 "이미 더럽던" src 파일 목록을 스냅샷한다.
# 검증 게이트는 이 목록의 파일을 제외하고, 세션 중 Claude 가 만든 변경만 검사한다.
# (커밋 전 기존 WIP 에 게이트가 막히지 않도록)
set -uo pipefail

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}"
[ -d "$PROJECT_DIR" ] && cd "$PROJECT_DIR" || exit 0

CACHE_DIR="$PROJECT_DIR/.claude/.cache"
mkdir -p "$CACHE_DIR"

# 추적-수정 + 추적안됨 모두 포함, src/ 하위만. 마지막 필드 = 파일 경로(rename 은 new 이름).
git status --porcelain -uall -- src/ 2>/dev/null | awk '{print $NF}' | sort -u \
  > "$CACHE_DIR/baseline-files"

exit 0
