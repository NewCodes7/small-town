#!/usr/bin/env python3
"""agent-review.sh 의 claude 응답(JSON)을 파싱해 게이트 판정.

stdin: claude -p 응답 텍스트 (JSON 포함, 코드펜스 등이 섞일 수 있음)
exit 0: 통과 (pass=true 이고 high 이슈 없음, 또는 파싱 불가 → 안전 통과).
        이때 low 이슈가 있으면 'WARN:' 접두사로 stdout 에 출력(비차단 경고).
exit 1: high 이슈(또는 pass=false) 존재 → 차단 목록을 stdout 에 출력.
"""
import json
import re
import sys

text = sys.stdin.read()
m = re.search(r"\{.*\}", text, re.S)
if not m:
    sys.exit(0)  # 파싱 불가 → 결정론적 게이트가 1차 방어이므로 막지 않음
try:
    data = json.loads(m.group(0))
except json.JSONDecodeError:
    sys.exit(0)

issues = data.get("issues", []) or []
highs = [i for i in issues if str(i.get("severity", "")).lower() == "high"]
lows = [i for i in issues if str(i.get("severity", "")).lower() != "high"]


def fmt(issue):
    return f"{issue.get('file', '')}: {issue.get('detail', '')}"


# 통과: high 없고 pass=true. low 이슈는 비차단 경고(WARN:)로만 표면화.
if data.get("pass") and not highs:
    for i in lows:
        print(f"WARN: {fmt(i)}")
    sys.exit(0)

# 차단: high(또는 pass=false). high 가 있으면 high 만, 없으면 전체를 차단 목록으로.
blocking = highs or issues
print(f"에이전트 리뷰 — 반드시 수정해야 할 항목 {len(blocking)}건:")
for i in blocking:
    print(f"  [{i.get('severity', '?')}] {fmt(i)}")
sys.exit(1)
