#!/usr/bin/env python3
"""T8 — 여러 수집 런에서 한 층만 뽑아 하나의 런으로 합친다.

설계: docs/testing/SEARCH_ACCURACY_EVAL_DESIGN.md §4 T8

왜 필요한가: T7 스윕의 유일한 권고가 "SIMPLE 층을 10 → 20~30 으로 늘려 재측정"이었다
(`results/2026-08-22-sweep.md` §4). 동결 세트(`queries.json`)는 고치지 않기로 했으므로 확장분을
별도 런으로 수집했고, 스윕을 돌리려면 두 런의 SIMPLE 을 한 런처럼 읽어야 한다.

합치는 것은 **원본 수집물과 판정뿐**이다. `pool.json` 은 합친 `raw.jsonl` 로 `build_pool.py` 가
다시 만든다 — 두 풀을 이어 붙이면 재구성 무결성 검사를 건너뛰게 되고, 그게 §7-5 가 겪은
"아무 데서도 에러가 안 나는" 실패의 모양이다.

**합치기 전에 확인할 것** (스크립트가 대신 못 해 준다):
  - 두 런의 `search_weight_config` 가 같은가 (§2-3 — admin 에서 동적으로 바뀐다)
  - 코퍼스 이동이 10% 미만인가 (§5-8)
둘 다 각 런의 쿼리 세트 `parameterSnapshot`/`corpusSnapshot` 에 적혀 있어야 한다.

사용:
  RUN_ID=2026-08-24-simple30 python3 search-eval/merge_runs.py \\
      --from 2026-08-22c --from 2026-08-24-simple-ext --tier SIMPLE
  RUN_ID=2026-08-24-simple30 python3 search-eval/build_pool.py
"""
import argparse, datetime, io, json, os
from collections import Counter

RUNS = os.path.join("search-eval", "runs")
MERGED = ("raw.jsonl", "judgments.jsonl")


def read_jsonl(path):
    if not os.path.exists(path):
        return None
    return [json.loads(l) for l in io.open(path, encoding="utf-8") if l.strip()]


def collect_raw(sources, tier):
    """층이 맞는 raw 줄만 모은다. 같은 키워드가 두 런에 있으면 중단한다 —
    어느 쪽을 쓸지는 스크립트가 정할 문제가 아니고, 대개는 --from 을 잘못 준 것이다."""
    rows, origin = [], {}
    for run in sources:
        path = f"{RUNS}/{run}/raw.jsonl"
        lines = read_jsonl(path)
        if lines is None:
            raise SystemExit(f"[중단] {path} 가 없다 (gitignore 대상이라 클론 직후엔 비어 있다)")
        for d in lines:
            if tier and d.get("tier") != tier:
                continue
            kw = d["keyword"]
            if kw in origin:
                raise SystemExit(
                    f"[중단] 키워드 '{kw}' 가 {origin[kw]} 와 {run} 양쪽에 있다. "
                    f"어느 수집본을 쓸지는 사람이 정해야 한다.")
            origin[kw] = run
            rows.append((run, d))
    if not rows:
        raise SystemExit(f"[중단] 층 '{tier}' 에 해당하는 줄이 하나도 없다.")
    return rows, origin


def collect_judgments(sources, keywords):
    """합친 키워드에 해당하는 판정만 모은다. trial 2(재판정)도 같이 가져온다 —
    자기 일치도 분석이 합친 런에서도 돌아야 한다."""
    rows, seen = [], set()
    for run in sources:
        for d in read_jsonl(f"{RUNS}/{run}/judgments.jsonl") or []:
            if d.get("keyword") not in keywords:
                continue
            key = (d["keyword"], d["articleId"], d.get("mode"), d.get("trial", 1))
            if key in seen:            # 같은 판정이 두 런에 있으면 첫 번째만 (캐시 재사용 흔적)
                continue
            seen.add(key)
            rows.append(d)
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--from", dest="sources", action="append", required=True,
                    help="합칠 런 ID (여러 번 줄 수 있다)")
    ap.add_argument("--tier", default=None, help="이 평가 층만 가져온다 (예: SIMPLE)")
    args = ap.parse_args()

    dest = os.environ.get("RUN_ID")
    if not dest:
        raise SystemExit("[중단] RUN_ID 로 만들 런 이름을 줘야 한다.")
    if dest in args.sources:
        raise SystemExit(f"[중단] RUN_ID '{dest}' 가 원본 런과 같다 — 원본을 덮어쓴다.")

    rows, origin = collect_raw(args.sources, args.tier)
    keywords = set(origin)
    judgments = collect_judgments(args.sources, keywords)

    out = f"{RUNS}/{dest}"
    os.makedirs(out, exist_ok=True)
    io.open(f"{out}/raw.jsonl", "w", encoding="utf-8").write(
        "".join(json.dumps(d, ensure_ascii=False) + "\n" for _, d in rows))
    if judgments:
        io.open(f"{out}/judgments.jsonl", "w", encoding="utf-8").write(
            "".join(json.dumps(d, ensure_ascii=False) + "\n" for d in judgments))

    meta = {
        "runId": dest, "mergedAt": datetime.datetime.now().astimezone().isoformat(),
        "sources": args.sources, "tier": args.tier,
        "queries": len(rows), "queriesBySource": dict(Counter(r for r, _ in rows)),
        "judgements": len(judgments),
        "judgementsByTrial": dict(Counter(d.get("trial", 1) for d in judgments)),
        "note": "pool.json 은 이 raw.jsonl 로 build_pool.py 가 다시 만든다 — 풀을 이어 붙이지 않는다",
        "caveat": ("두 수집 시점의 동점 순서는 재현되지 않는다(§7-7). 1회 측정에는 영향이 없지만 "
                   "회귀 게이트로 쓰면 흔들림이 섞인다."),
    }
    io.open(f"{out}/merge_meta.json", "w", encoding="utf-8").write(
        json.dumps(meta, ensure_ascii=False, indent=2) + "\n")

    print(f"=== 병합  {' + '.join(args.sources)}  →  {dest} ===")
    print(f"  층          {args.tier or '(전부)'}")
    print(f"  쿼리        {len(rows)}건  {meta['queriesBySource']}")
    print(f"  판정        {len(judgments)}건  trial별 {meta['judgementsByTrial'] or '(없음)'}")
    print(f"\n→ {out}/raw.jsonl" + (f" · judgments.jsonl" if judgments else " (판정은 아직 없다)"))
    print(f"\n다음: RUN_ID={dest} python3 search-eval/build_pool.py")


if __name__ == "__main__":
    main()
