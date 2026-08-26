#!/usr/bin/env python3
"""T5 스코어러 — NDCG@10 / P@5 / pooled Recall@10 / MRR + paired bootstrap CI.

설계: docs/testing/SEARCH_ACCURACY_EVAL_DESIGN.md §2-1, §2-1-1, §5-1, §5-6, §5-6-1

세 시스템을 같은 판정으로 비교한다:
  hybrid  API 반환 순서 (사용자가 실제로 보는 것)
  bm25    sourceBm25Rank 오름차순   (cross-scoring 전 원본 BM25)
  vector  sourceVectorRank 오름차순 (cross-scoring 전 원본 Vector)

정의상 주의:
- **gain 은 선형(등급 0~3)** 이다(§2-1). TREC 관례인 2^g-1 도 함께 내어 결론이 gain 정의에
  흔들리지 않는지 본다 — 보고는 선형이 주(主)다.
- **Recall 은 pooled 다.** 분모가 "전체 관련 문서"가 아니라 "판정 풀 안에서 관련으로 판정된 것"
  이라 진짜 recall 이 아니다. 변수명·출력 라벨 모두 pooled 를 달고 다닌다(§5-1).
- **짧은 랭킹을 0 으로 패딩하지 않는다**(§5-6-1). 벡터 단독은 깊이가 10 미만인 쿼리가 있고,
  없는 자리를 0 으로 채우면 "낼 게 없었다"와 "틀렸다"가 섞인다. 깊이를 함께 보고한다.
- **팔이 통째로 빈 쿼리는 N/A 다**(§5-6). 0 점이 아니다 — 평균에서 제외하고 건수를 남긴다.
"""
import argparse, io, json, math, os, random, statistics as st
from collections import defaultdict

RUN_ID = os.environ.get("RUN_ID", "2026-08-22c")
BASE = os.path.join("search-eval", "runs", RUN_ID)
K = 10
P_AT = 5
REL = 2            # 등급 >= 2 를 '관련'으로 본다 (P@5 / pooled Recall / MRR 공통)
TIERS = ("SIMPLE", "MODERATE", "COMPLEX", "SPECIFIC", "CORPORATION")
SYSTEMS = ("hybrid", "bm25", "vector")


def gain_linear(g):
    return float(g)


def gain_exp(g):
    return float(2 ** g - 1)


def dcg(grades, gain):
    return sum(gain(g) / math.log2(i + 2) for i, g in enumerate(grades))


def ndcg_at_k(ranked_grades, all_grades, gain, k=K):
    """ranked_grades: 시스템이 낸 순서대로의 등급 (패딩 없음, 길이 <= k).
    all_grades: 그 쿼리에서 판정된 **모든** 등급 (이상적 순위의 재료)."""
    ideal = sorted(all_grades, reverse=True)[:k]
    idcg = dcg(ideal, gain)
    if idcg == 0:
        return None                     # 관련 문서가 하나도 없는 쿼리 — 정의 불가
    return dcg(ranked_grades[:k], gain) / idcg


def precision_at(ranked_grades, n=P_AT):
    """분모는 n 으로 고정한다(시스템 간 비교 가능성). 깊이는 따로 보고한다."""
    return sum(1 for g in ranked_grades[:n] if g >= REL) / n


def pooled_recall_at(ranked_grades, all_grades, k=K):
    total = sum(1 for g in all_grades if g >= REL)
    if total == 0:
        return None
    return sum(1 for g in ranked_grades[:k] if g >= REL) / total


def mrr(ranked_grades):
    for i, g in enumerate(ranked_grades, 1):
        if g >= REL:
            return 1.0 / i
    return 0.0


def load(base):
    pool = json.load(io.open(f"{base}/pool.json", encoding="utf-8"))
    grades = {}
    seen = set()
    for line in io.open(f"{base}/judgments.jsonl", encoding="utf-8"):
        d = json.loads(line)
        if d.get("mode") != "passages" or d.get("trial", 1) != 1:
            continue
        key = (d["keyword"], d["articleId"])
        if key in seen:                 # 같은 쌍이 두 번 있으면 뒤엣것이 이긴다 (재판정 덮어쓰기)
            pass
        seen.add(key)
        grades[key] = d["relevance"]
    diag = {d["keyword"]: d for d in
            json.load(io.open(f"{base}/pool_diagnostics.json", encoding="utf-8"))}
    return pool, grades, diag


def per_query(pool, grades):
    """쿼리 × 시스템 별 지표. 판정이 없는 문서가 있으면 그 사실을 그대로 드러낸다."""
    rows, missing = [], []
    for q in pool["queries"]:
        kw, tier = q["keyword"], q["tier"]
        all_g = []
        for it in q["pool"]:
            g = grades.get((kw, it["articleId"]))
            if g is None:
                missing.append((kw, it["articleId"]))
            else:
                all_g.append(g)
        rec = {"keyword": kw, "tier": tier, "poolSize": len(q["pool"]),
               "judged": len(all_g), "relevantInPool": sum(1 for g in all_g if g >= REL)}
        for sysname in SYSTEMS:
            ids = q["rankings"][sysname]
            rg = [grades[(kw, a)] for a in ids if (kw, a) in grades]
            depth = q["rankingDepth"][sysname]
            if not ids:                                   # 팔이 통째로 빔 → N/A (§5-6)
                rec[sysname] = {"empty": True, "depth": depth}
                continue
            rec[sysname] = {
                "empty": False, "depth": depth, "returned": len(rg),
                "ndcg10": ndcg_at_k(rg, all_g, gain_linear),
                "ndcg10Exp": ndcg_at_k(rg, all_g, gain_exp),
                "p5": precision_at(rg),
                "pooledRecall10": pooled_recall_at(rg, all_g),
                "mrr": mrr(rg),
            }
        rows.append(rec)
    return rows, missing


def mean(vals):
    vals = [v for v in vals if v is not None]
    return round(st.mean(vals), 4) if vals else None


def paired_bootstrap(rows, a, b, metric, n=10000, seed=20260822):
    """쿼리를 복원추출해 (a - b) 평균차의 백분위 신뢰구간. 둘 다 값이 있는 쿼리만 쓴다."""
    d = [r[a][metric] - r[b][metric] for r in rows
         if not r[a]["empty"] and not r[b]["empty"]
         and r[a].get(metric) is not None and r[b].get(metric) is not None]
    if len(d) < 2:
        return None
    rng = random.Random(seed)
    means = []
    for _ in range(n):
        means.append(sum(rng.choices(d, k=len(d))) / len(d))
    means.sort()
    lo, hi = means[int(n * .025)], means[int(n * .975)]
    return {"n": len(d), "meanDelta": round(sum(d) / len(d), 4),
            "ci95": [round(lo, 4), round(hi, 4)],
            "significant": bool(lo > 0 or hi < 0)}


def summarize(rows, subset=None):
    rs = [r for r in rows if subset is None or r["tier"] == subset]
    out = {"n": len(rs)}
    for sysname in SYSTEMS:
        vals = [r[sysname] for r in rs]
        live = [v for v in vals if not v["empty"]]
        out[sysname] = {
            "queries": len(live),
            "emptyArm": len(vals) - len(live),
            "ndcg10": mean([v["ndcg10"] for v in live]),
            "ndcg10Exp": mean([v["ndcg10Exp"] for v in live]),
            "p5": mean([v["p5"] for v in live]),
            "pooledRecall10": mean([v["pooledRecall10"] for v in live]),
            "mrr": mean([v["mrr"] for v in live]),
            "depthMean": mean([v["depth"] for v in live]),
            "depthUnderK": sum(1 for v in live if v["depth"] < K),
        }
    return out


def service_metrics(rows, diag, subset=None):
    ks = [r["keyword"] for r in rows if subset is None or r["tier"] == subset]
    lat = sorted(diag[k]["latencyMs"] for k in ks if k in diag)
    tot = [diag[k]["totalElements"] for k in ks if k in diag]
    if not lat:
        return {}
    return {"latencyP50": lat[len(lat) // 2], "latencyP95": lat[min(int(len(lat) * .95), len(lat) - 1)],
            "totalElementsMean": round(st.mean(tot), 1),
            "vectorDepthMean": round(st.mean([diag[k]["vectorDepth"] for k in ks if k in diag]), 1)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bootstrap", type=int, default=10000)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    pool, grades, diag = load(BASE)
    rows, missing = per_query(pool, grades)

    if missing:
        print(f"[경고] 판정이 없는 (쿼리,아티클) {len(missing)}건 — 지표에서 빠진다. 예: {missing[:3]}")

    result = {
        "runId": RUN_ID, "k": K, "relevanceThreshold": REL,
        "gain": "linear(등급 0~3, §2-1) — ndcg10Exp 는 2^g-1 민감도",
        "judgedPairs": len(grades),
        "missingJudgements": len(missing),
        "overall": summarize(rows),
        "byTier": {t: summarize(rows, t) for t in TIERS},
        "service": {"overall": service_metrics(rows, diag),
                    **{t: service_metrics(rows, diag, t) for t in TIERS}},
        "pairedBootstrap": {},
    }
    for metric in ("ndcg10", "p5", "pooledRecall10", "mrr"):
        result["pairedBootstrap"][metric] = {
            "hybrid-bm25": paired_bootstrap(rows, "hybrid", "bm25", metric, args.bootstrap),
            "hybrid-vector": paired_bootstrap(rows, "hybrid", "vector", metric, args.bootstrap),
            "bm25-vector": paired_bootstrap(rows, "bm25", "vector", metric, args.bootstrap),
        }

    out = args.out or f"{BASE}/scores.json"
    io.open(out, "w", encoding="utf-8").write(json.dumps(
        {**result, "perQuery": rows}, ensure_ascii=False, indent=2) + "\n")

    # ── 사람이 읽는 표 ──
    o = result["overall"]
    print(f"\n=== 전체 (n={o['n']} 쿼리, 판정 {len(grades)}쌍, 관련 기준 등급>={REL}) ===")
    print(f"{'시스템':10} {'NDCG@10':>9} {'(지수)':>8} {'P@5':>7} {'pooledR@10':>11} {'MRR':>7} {'깊이':>7} {'빈팔':>5}")
    for s in SYSTEMS:
        v = o[s]
        f = lambda x: f"{x:.4f}" if x is not None else "  N/A "
        print(f"  {s:8} {f(v['ndcg10']):>9} {f(v['ndcg10Exp']):>8} {f(v['p5']):>7} "
              f"{f(v['pooledRecall10']):>11} {f(v['mrr']):>7} {v['depthMean']:>7} {v['emptyArm']:>5}")

    print(f"\n=== paired bootstrap 95% CI (B={args.bootstrap}) ===")
    for metric in ("ndcg10", "p5", "pooledRecall10", "mrr"):
        for pair, r in result["pairedBootstrap"][metric].items():
            if r:
                mark = "유의" if r["significant"] else "  — "
                print(f"  {metric:15} {pair:15} Δ={r['meanDelta']:+.4f}  "
                      f"CI[{r['ci95'][0]:+.4f}, {r['ci95'][1]:+.4f}]  {mark}  (n={r['n']})")

    print(f"\n=== 층별 NDCG@10 (선형 gain) ===")
    print(f"{'층':13} " + "".join(f"{s:>10}" for s in SYSTEMS) + f"{'지연p50':>9}{'벡터깊이':>9}")
    for t in TIERS:
        v = result["byTier"][t]; sm = result["service"][t]
        cells = "".join(f"{(v[s]['ndcg10'] if v[s]['ndcg10'] is not None else float('nan')):>10.4f}" for s in SYSTEMS)
        print(f"  {t:11} {cells}{sm.get('latencyP50',0):>8}ms{sm.get('vectorDepthMean',0):>9}")
    print(f"\n→ {out}")


if __name__ == "__main__":
    main()
