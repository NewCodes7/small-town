#!/usr/bin/env bash
# PostToolUse(Edit|Write|MultiEdit) — 빠른 컴파일 피드백 (비차단).
# 강제(차단)는 Stop 훅 verify-gate.sh 에서 수행한다.
set -uo pipefail

# 재귀 가드: agent-review 가 띄운 claude -p 안에서는 동작하지 않음
[ -n "${IN_VERIFY_GATE:-}" ] && exit 0

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}"
[ -d "$PROJECT_DIR" ] && cd "$PROJECT_DIR" || exit 0

input=$(cat)
file=$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty' 2>/dev/null)
case "$file" in
  *.java) ;;
  *) exit 0 ;;   # Java 외 파일은 스킵
esac

# 증분 컴파일 (Gradle 데몬 캐시로 빠름)
if out=$(./gradlew compileJava compileTestJava --console=plain -q 2>&1); then
  exit 0
fi

# 실패해도 차단하지 않고 컨텍스트로 알림만 (리팩터링 중간 상태 고려)
msg=$(printf '%s' "$out" | tail -n 40)
jq -n --arg c "컴파일 오류가 있습니다(턴 종료 전 수정 필요):
$msg" '{hookSpecificOutput:{hookEventName:"PostToolUse", additionalContext:$c}}'
exit 0
