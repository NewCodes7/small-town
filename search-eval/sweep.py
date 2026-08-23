#!/usr/bin/env python3
"""T7 — NSF 가중치 오프라인 스윕.

설계: docs/testing/SEARCH_ACCURACY_EVAL_DESIGN.md §4 T7, §2-3

응답이 아티클마다 normalizedBm25Score / normalizedVectorScore 를 실어 주므로,
NSF 를 다른 가중치로 다시 계산해 재정렬하면 **추가 API 호출 없이** 가중치를 바꿔볼 수 있다.
계산은 HybridSearchScorer.calculateNSFScores 와 같다:

    nsf = (wb * normBm25?  +  wv * normVector?) / (wb + wv)      (없는 항은 더하지 않는다)

가중치는 **앱의 복잡도 분류(appTier)** 단위로 걸린다 — 평가 층이 아니다.
이 쿼리 세트에서는 appTier SIMPLE 10 / MODERATE 10 / COMPLEX 30 이다.

⚠️ **이 스윕으로 바꿀 수 없는 것**: titleMultiplier 는 BM25 **쿼리 자체**를 바꾸므로
(`buildBM25SearchQuery`) 오프라인 재채점으로는 다룰 수 없다. cross-scoring on/off 도 마찬가지다.
둘 다 프로퍼티를 바꿔 재수집해야 한다.

### 판정되지 않은 문서 문제

가중치를 바꾸면 지금 판정 풀(세 랭킹의 top-10 합집합) 밖에 있던 문서가 top-10 으로 올라온다.
그 문서에는 등급이 없다. 두 가지로 모두 계산해 결론이 처리 방식에 흔들리는지 본다.

  condensed  판정 없는 문서를 랭킹에서 **빼고** 판정된 것만으로 순위를 만든다.
             불완전 판정에서 권장되는 방식이고(Sakai), 어느 가중치든 똑같이 적용되어 공평하다. **주 지표**
  unjudged0  판정 없는 문서를 등급 0 으로 본다. 비관적 하한 — 풀을 만든 현행 가중치에 유리하다

둘이 같은 방향이면 결론을 믿을 수 있다. 갈리면 그 자체를 보고한다.
"""
import argparse, importlib.util, io, json, os, pathlib
from collections import defaultdict

RUN_ID = os.environ.get("RUN_ID", "2026-08-22c")
BASE = os.path.join("search-eval", "runs", RUN_ID)
K = 10

_spec = importlib.util.spec_from_file_location("score", pathlib.Path(__file__).with_name("score.py"))
_score = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_score)

PROD = {"SIMPLE": (0.5, 0.5), "MODERATE": (0.5, 0.5), "COMPLEX": (0.35, 0.65)}
CODE_DEFAULT = {"SIMPLE": (0.6, 0.4), "MODERATE": (0.5, 0.5), "COMPLEX": (0.4, 0.6)}


def nsf(article, wb, wv):
    s, nb, nv = 0.0, article.get("normalizedBm25Score"), article.get("normalizedVectorScore")
    if nb is not None:
        s += wb * nb
    if nv is not None:
        s += wv * nv
    return s / (wb + wv) if (wb + wv) > 0 else 0.0


def rerank(content, wb, wv):
    """동점은 원 응답 순서를 유지한다(sorted 는 stable) — 앱의 동점 처리와 같은 성질."""
    return sorted(content, key=lambda a: -nsf(a, wb, wv))


def evaluate(queries, grades, wb, wv, mode):
    """mode: 'condensed' | 'unjudged0'.  → 쿼리별 NDCG@10 리스트"""
    out = []
    for q in queries:
        kw = q["keyword"]
        ranked = rerank(q["content"], wb, wv)
        if mode == "condensed":
            rg = [grades[(kw, a["id"])] for a in ranked if (kw, a["id"]) in grades][:K]
        else:
            rg = [grades.get((kw, a["id"]), 0) for a in ranked][:K]
        all_g = [g for (k, _), g in grades.items() if k == kw]
        v = _score.ndcg_at_k(rg, all_g, _score.gain_linear)
        if v is not None:
            out.append(v)
    return out


def mean(v):
    return sum(v) / len(v) if v else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--step", type=float, default=0.05)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    raw = [json.loads(l) for l in io.open(f"{BASE}/raw.jsonl", encoding="utf-8")]
    qset = {q["keyword"]: q for q in json.load(io.open("search-eval/queries.json", encoding="utf-8"))["queries"]}
    grades = {}
    for line in io.open(f"{BASE}/judgments.jsonl", encoding="utf-8"):
        d = json.loads(line)
        if d.get("mode") == "passages" and d.get("trial", 1) == 1:
            grades[(d["keyword"], d["articleId"])] = d["relevance"]

    by_app = defaultdict(list)
    for r in raw:
        kw = r["keyword"]
        by_app[qset[kw]["appTier"]].append({"keyword": kw, "content": r["body"]["content"],
                                            "tier": qset[kw]["tier"]})

    # 현행 prod 값과 코드 기본값은 그리드에 반드시 있어야 한다 — 비교 기준점이기 때문이다.
    anchors = {w for t in PROD for w in (PROD[t][0], CODE_DEFAULT[t][0])}
    grid = sorted({round(i * args.step, 4) for i in range(int(1 / args.step) + 1)} | anchors)
    result = {"runId": RUN_ID, "note": "titleMultiplier·cross-scoring 은 오프라인으로 바꿀 수 없다", "appTiers": {}}

    for app_tier in ("SIMPLE", "MODERATE", "COMPLEX"):
        qs = by_app[app_tier]
        rows = []
        for wb in grid:
            wv = round(1 - wb, 4)
            rows.append({
                "bm25": wb, "vector": wv,
                "condensed": round(mean(evaluate(qs, grades, wb, wv, "condensed")), 4),
                "unjudged0": round(mean(evaluate(qs, grades, wb, wv, "unjudged0")), 4),
            })
        prod_wb, prod_wv = PROD[app_tier]
        cur = next(r for r in rows if abs(r["bm25"] - prod_wb) < 1e-9)
        best_c = max(rows, key=lambda r: r["condensed"])
        best_u = max(rows, key=lambda r: r["unjudged0"])
        code = next(r for r in rows if abs(r["bm25"] - CODE_DEFAULT[app_tier][0]) < 1e-9)
        # 두 처리 방식이 **같은 방향**을 가리키는지가 핵심이다 — 최적점이 한 칸 차이라도
        # 한쪽은 "올려라" 다른 쪽은 "내려라" 면 결론을 낼 수 없다.
        dir_c = (best_c["bm25"] > prod_wb) - (best_c["bm25"] < prod_wb)
        dir_u = (best_u["bm25"] > prod_wb) - (best_u["bm25"] < prod_wb)
        result["appTiers"][app_tier] = {
            "n": len(qs), "prod": [prod_wb, prod_wv], "codeDefault": list(CODE_DEFAULT[app_tier]),
            "current": cur, "codeDefaultRow": code,
            "bestCondensed": best_c, "bestUnjudged0": best_u,
            "gainCondensed": round(best_c["condensed"] - cur["condensed"], 4),
            "gainCodeDefault": round(code["condensed"] - cur["condensed"], 4),
            "sameDirection": dir_c == dir_u,
            "grid": rows,
        }

        print(f"\n=== appTier {app_tier}  (쿼리 {len(qs)}건) ===")
        print(f"  현행 prod   bm25={prod_wb} vector={prod_wv}   "
              f"condensed {cur['condensed']:.4f} / unjudged0 {cur['unjudged0']:.4f}")
        print(f"  코드 기본값 bm25={CODE_DEFAULT[app_tier][0]} vector={CODE_DEFAULT[app_tier][1]}")
        print(f"  최적(condensed) bm25={best_c['bm25']} → {best_c['condensed']:.4f} "
              f"(현행 대비 {best_c['condensed']-cur['condensed']:+.4f})")
        print(f"  최적(unjudged0) bm25={best_u['bm25']} → {best_u['unjudged0']:.4f}"
              f"   두 방식이 같은 방향: {'예' if result['appTiers'][app_tier]['sameDirection'] else '아니오'}")
        print(f"  코드 기본값 적용 시 condensed {code['condensed']:.4f} "
              f"(현행 대비 {code['condensed']-cur['condensed']:+.4f})")
        print(f"  {'bm25':>6}{'vector':>8}{'condensed':>12}{'unjudged0':>12}")
        for r in rows:
            mark = "  ← 현행" if abs(r["bm25"] - prod_wb) < 1e-9 else ""
            mark += "  ★최적" if r is best_c else ""
            print(f"  {r['bm25']:>6}{r['vector']:>8}{r['condensed']:>12.4f}{r['unjudged0']:>12.4f}{mark}")

    out = args.out or f"{BASE}/sweep.json"
    io.open(out, "w", encoding="utf-8").write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(f"\n→ {out}")


if __name__ == "__main__":
    main()
