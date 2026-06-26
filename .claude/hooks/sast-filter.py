#!/usr/bin/env python3
"""SpotBugs 리포트를 "base 이후 변경된 라인의 SECURITY 결함"으로 필터링한다.

레거시 결함에 게이트가 막히지 않도록, git diff 로 바뀐 라인 위에 위치한
find-sec-bugs(SECURITY 카테고리) 결함만 출력한다.

usage: sast-filter.py <spotbugs-main.xml> [base-ref]
  base-ref 기본값: HEAD (커밋 전 작업분). 추적되지 않은 새 파일은 전체 라인을 변경분으로 본다.

exit 0: 새 결함 없음
exit 1: 새 결함 존재 (목록을 stdout 으로 출력)
exit 2: 입력 오류
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def resolve_base(ref):
    for candidate in ([ref] if ref else ["HEAD"]):
        r = subprocess.run(["git", "rev-parse", "--verify", "--quiet", candidate],
                           capture_output=True, text=True)
        if r.returncode == 0:
            return candidate
    return None


def changed_lines(base):
    """{파일경로: set(라인) | True} 반환. True = 전체 라인이 변경분(추적 안 된 새 파일)."""
    result = {}
    # 추적 파일의 변경 라인
    out = subprocess.run(
        ["git", "diff", "--unified=0", "--no-color", base, "--", "src/main"],
        capture_output=True, text=True).stdout
    cur = None
    hunk = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")
    for line in out.splitlines():
        if line.startswith("+++ b/"):
            path = line[6:].strip()
            cur = path if path.endswith(".java") else None
            if cur:
                result.setdefault(cur, set())
        elif cur and line.startswith("@@"):
            m = hunk.match(line)
            if not m:
                continue
            start = int(m.group(1))
            count = int(m.group(2)) if m.group(2) is not None else 1
            for ln in range(start, start + max(count, 1)):
                result[cur].add(ln)
    # 추적되지 않은 새 파일 → 전체 라인을 변경분으로 간주
    others = subprocess.run(
        ["git", "ls-files", "--others", "--exclude-standard", "--", "src/main"],
        capture_output=True, text=True).stdout
    for path in others.splitlines():
        if path.endswith(".java"):
            result[path] = True
    return result


def load_baseline():
    """세션 시작 시점에 이미 더럽던 파일(기존 WIP) 집합 — 게이트 제외 대상."""
    try:
        with open(".claude/.cache/baseline-files", encoding="utf-8") as fh:
            return {ln.strip() for ln in fh if ln.strip()}
    except FileNotFoundError:
        return set()


def primary_sourceline(bug):
    lines = bug.findall("SourceLine")
    for sl in lines:
        if sl.get("primary") == "true" and sl.get("start"):
            return sl
    for sl in lines:
        if sl.get("start"):
            return sl
    return None


def main():
    if len(sys.argv) < 2:
        print("usage: sast-filter.py <spotbugs-main.xml> [base-ref]", file=sys.stderr)
        return 2
    report = sys.argv[1]
    base = resolve_base(sys.argv[2] if len(sys.argv) > 2 else None)
    if not base:
        print("[sast-filter] git base ref 를 찾지 못함", file=sys.stderr)
        return 2

    try:
        root = ET.parse(report).getroot()
    except (ET.ParseError, FileNotFoundError) as e:
        print(f"[sast-filter] 리포트 파싱 실패: {e}", file=sys.stderr)
        return 2

    changed = changed_lines(base)
    baseline = load_baseline()
    for f in baseline:
        changed.pop(f, None)  # 기존 WIP 파일 제외
    if not changed:
        return 0

    findings = []
    for bug in root.findall("BugInstance"):
        # SAST 게이트는 보안(SECURITY = find-sec-bugs) 결함만 대상으로 한다.
        # (EI_EXPOSE 등 MALICIOUS_CODE/STYLE/CORRECTNESS 는 Spotless/리뷰 영역)
        if bug.get("category") != "SECURITY":
            continue
        sl = primary_sourceline(bug)
        if sl is None:
            continue
        rel = sl.get("relSourcepath")  # ex) java/com/newcodes7/.../Foo.java
        if not rel:
            continue
        path = "src/main/" + rel
        touched = changed.get(path)
        if not touched:
            continue
        start = int(sl.get("start"))
        end = int(sl.get("end") or start)
        if touched is True or any(start <= ln <= end for ln in touched):
            short = bug.findtext("ShortMessage") or ""
            findings.append(
                f"  [{bug.get('category')}/{bug.get('type')}] {path}:{start} — {short}")

    if findings:
        print(f"변경된 코드에서 SAST(SpotBugs+find-sec-bugs) 결함 {len(findings)}건 발견:")
        print("\n".join(findings))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
