#!/usr/bin/env python3
"""T2 랭킹 재구성 + 판정 풀 구성.

설계: docs/testing/SEARCH_ACCURACY_EVAL_DESIGN.md §0-1, §4 T2

세 개의 랭킹을 같은 응답에서 재구성한다:
  hybrid  : **API 반환 순서 그대로** — 사용자가 실제로 보는 순서다.
            finalScore 로 재정렬하지 않는다: 전 쿼리에서 finalScore 단조 감소가 성립하지만
            동점 문서가 647건 있어 재정렬하면 시스템 고유의 동점 처리 순서가 깨진다.
  bm25    : sourceBm25Rank 오름차순 (cross-scoring 전 원본 BM25 후보)
  vector  : sourceVectorRank 오름차순 (cross-scoring 전 원본 Vector 후보)
판정 풀 = 세 랭킹의 top-K 합집합.
"""
import json, io, os, sys, datetime
from collections import Counter

TOP_K = 10

def validate_source_ranks(content, field):
    ranks = [a.get(field) for a in content if a.get(field) is not None]
    if any(type(rank) is not int or rank < 1 for rank in ranks):
        raise ValueError(f"{field}는 1 이상의 정수 또는 null이어야 합니다: {ranks[:20]}")
    if sorted(ranks) != list(range(1, len(ranks) + 1)):
        raise ValueError(f"{field}가 중복되거나 비연속입니다: {sorted(ranks)[:20]}")

def rankings(content):
    missing = [a.get("id") for a in content
               if "sourceBm25Rank" not in a or "sourceVectorRank" not in a]
    if missing:
        raise ValueError(
            "원본 검색 provenance 필드(sourceBm25Rank/sourceVectorRank)가 없는 응답입니다. "
            "기존 raw.jsonl로는 단독 랭킹을 복원할 수 없으므로 수정된 서버 배포 후 재수집하세요."
        )
    validate_source_ranks(content, "sourceBm25Rank")
    validate_source_ranks(content, "sourceVectorRank")
    hybrid = list(content)                       # API 반환 순서 유지
    bm25 = sorted([a for a in content if a.get("sourceBm25Rank") is not None],
                  key=lambda a: a["sourceBm25Rank"])
    vector = sorted([a for a in content if a.get("sourceVectorRank") is not None],
                    key=lambda a: a["sourceVectorRank"])
    return {"hybrid": hybrid, "bm25": bm25, "vector": vector}

def main():
    run_id = os.environ.get("RUN_ID", datetime.date.today().isoformat())
    d = f"search-eval/runs/{run_id}"
    rows = [json.loads(l) for l in io.open(f"{d}/raw.jsonl", encoding="utf-8")]

    pools, diag = [], []
    for r in rows:
        content = r["body"]["content"]
        rk = rankings(content)
        fs = [a.get("finalScore") or 0 for a in content]
        mono = all(fs[i] >= fs[i + 1] for i in range(len(fs) - 1))

        pool_ids, provenance = [], {}
        for name in ("hybrid", "bm25", "vector"):
            for pos, a in enumerate(rk[name][:TOP_K], 1):
                if a["id"] not in provenance:
                    provenance[a["id"]] = {}; pool_ids.append(a["id"])
                provenance[a["id"]][name] = pos

        by_id = {a["id"]: a for a in content}
        pools.append({
            "keyword": r["keyword"], "tier": r["tier"],
            "poolSize": len(pool_ids),
            "rankings": {n: [a["id"] for a in rk[n][:TOP_K]] for n in rk},
            "rankingDepth": {n: len(rk[n]) for n in rk},
            "pool": [{"articleId": i, "rankIn": provenance[i],
                      "title": by_id[i].get("title"),
                      "translatedTitle": by_id[i].get("translatedTitle"),
                      "summary": by_id[i].get("summary"),
                      "corporation": (by_id[i].get("corporation") or {}).get("name"),
                      "category": (by_id[i].get("category") or {}).get("name"),
                      "bm25Score": by_id[i].get("bm25Score"),
                      "vectorScore": by_id[i].get("vectorScore"),
                      "normalizedBm25Score": by_id[i].get("normalizedBm25Score"),
                      "normalizedVectorScore": by_id[i].get("normalizedVectorScore"),
                      "finalScore": by_id[i].get("finalScore"),
                      "foundByVector": by_id[i].get("foundByVector")} for i in pool_ids],
        })
        bm25_ranks = [a["sourceBm25Rank"] for a in rk["bm25"]]
        vector_ranks = [a["sourceVectorRank"] for a in rk["vector"]]
        diag.append({
            "keyword": r["keyword"], "tier": r["tier"],
            "finalScoreMonotonic": mono,
            "bm25MinRank": min(bm25_ranks) if bm25_ranks else None,
            "bm25Top10Contiguous": bm25_ranks[:10] == list(range(1, 11)) if len(bm25_ranks) >= 10 else None,
            "vectorMinRank": min(vector_ranks) if vector_ranks else None,
            "vectorTop10Contiguous": vector_ranks[:10] == list(range(1, 11)) if len(vector_ranks) >= 10 else None,
            "vectorDepth": len(rk["vector"]),
            "poolSize": len(pool_ids),
            "latencyMs": r["latencyMs"],
            "totalElements": r["body"]["totalElements"],
        })

    io.open(f"{d}/pool.json", "w", encoding="utf-8").write(
        json.dumps({"runId": run_id, "topK": TOP_K, "queries": pools}, ensure_ascii=False, indent=2) + "\n")
    io.open(f"{d}/pool_diagnostics.json", "w", encoding="utf-8").write(
        json.dumps(diag, ensure_ascii=False, indent=2) + "\n")

    # ── 진단 요약 ──
    print("=== 랭킹 재구성 무결성 ===")
    print("  API 순서에서 finalScore 단조 감소 :", sum(1 for x in diag if x["finalScoreMonotonic"]), "/", len(diag))
    print("  BM25 최소 rank 가 1 인 쿼리      :", sum(1 for x in diag if x["bm25MinRank"] == 1), "/", len(diag))
    print("  BM25 top10 rank 연속(1..10)     :", sum(1 for x in diag if x["bm25Top10Contiguous"]), "/", len(diag))
    print("  Vector 최소 rank 가 1 인 쿼리    :", sum(1 for x in diag if x["vectorMinRank"] == 1), "/", len(diag))
    print("  Vector top10 rank 연속(1..10)   :", sum(1 for x in diag if x["vectorTop10Contiguous"]), "/", len(diag))
    vshallow = [x for x in diag if x["vectorDepth"] < TOP_K]
    print(f"  벡터 랭킹 깊이 < {TOP_K} 인 쿼리     :", len(vshallow), "/", len(diag))

    sizes = [x["poolSize"] for x in diag]
    print(f"\n=== 판정 풀 크기 === 총 {sum(sizes)}건 | 평균 {sum(sizes)/len(sizes):.1f} "
          f"| 범위 {min(sizes)}~{max(sizes)}")
    print(f"\n{'층':12} {'풀평균':>6} {'풀범위':>9} {'벡터깊이평균':>12} {'지연중앙':>8}")
    for t in ("SIMPLE","MODERATE","COMPLEX","SPECIFIC","CORPORATION"):
        ds = [x for x in diag if x["tier"] == t]
        ps = [x["poolSize"] for x in ds]; lat = sorted(x["latencyMs"] for x in ds)
        print(f"{t:12} {sum(ps)/len(ps):>6.1f} {f'{min(ps)}~{max(ps)}':>9} "
              f"{sum(x['vectorDepth'] for x in ds)/len(ds):>12.1f} {lat[len(lat)//2]:>6}ms")
    if vshallow:
        print("\n  ⚠ 벡터 랭킹이 얕은 쿼리:", [(x["keyword"], x["vectorDepth"]) for x in vshallow])

if __name__ == "__main__":
    main()
