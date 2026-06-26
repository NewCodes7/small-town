#!/usr/bin/env python3
"""agent-review.sh 의 claude 응답(JSON)을 파싱해 게이트 판정.

stdin: claude -p 응답 텍스트 (JSON 포함, 코드펜스 등이 섞일 수 있음)
exit 0: 통과 (pass=true 이고 high 이슈 없음, 또는 파싱 불가 → 안전 통과)
exit 1: high 이슈 존재 → stdout 에 목록 출력
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

if data.get("pass") and not highs:
    sys.exit(0)

blocking = highs or issues
print(f"에이전트 리뷰 — 반드시 수정해야 할 항목 {len(blocking)}건:")
for i in blocking:
    sev = i.get("severity", "?")
    f = i.get("file", "")
    d = i.get("detail", "")
    print(f"  [{sev}] {f}: {d}")
sys.exit(1)
