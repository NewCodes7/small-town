#!/usr/bin/env python3
"""인간 앵커 — 표본 추출 + 블라인드 판정 시트 생성 + 사전 등록 게이트 판정.

설계: docs/testing/SEARCH_ACCURACY_EVAL_DESIGN.md §3-5-1, §4 T4

**이 표본이 재는 것은 "판정자가 얼마나 정확한가"가 아니라 "판정자의 오차가 결론을
뒤집을 수 있는가"다.** 950쌍에 무작위 ±1 오차를 50% 넣어도 NDCG 격차의 부호가 안 바뀌지만,
방향을 맞춰 고른 50쌍(5.3%)이면 하이브리드-벡터 비교가 뒤집힌다. 위험은 오차의 양이 아니라
방향에 있고, 자기 일치도 κ=0.949 가 이미 양(noise) 쪽을 덮었다. 남은 미지수는 계통 편향뿐이다.

그래서 표본을 세 층으로 나눈다:

  random  950쌍에서 단순 무작위 30 — 헤드라인 κ(문헌 0.3 대조)와 전체 평균 델타(등급 인플레이션)
  B       BM25 top-10 단독 항목 15 — §5-5 어휘 중복 편향이 실재하면 여기서 나온다
  V       벡터 top-10 단독 항목 15 — §5-9 발췌 선택기 편향이 실재하면 여기서 나온다

층 안에서는 random 층도 표적 층도 같은 모집단에서의 단순 무작위 추출이라, 카테고리 델타를
계산할 때 둘을 합쳐도 된다(실질 B ~24, V ~20). 반대로 **κ 는 random 30 으로만 낸다** —
60쌍 전체로 내면 표적 층이 과대표집돼 모집단 κ 가 아니다.

사용:
  python3 search-eval/sample_human_anchor.py                    # 표본 + 시트 생성
  python3 search-eval/sample_human_anchor.py --report runs/2026-08-22c/human.jsonl
"""
import argparse, html, importlib.util, io, json, os, pathlib, random, statistics as st
from collections import Counter, defaultdict

RUN_ID = os.environ.get("RUN_ID", "2026-08-22c")
BASE = os.path.join("search-eval", "runs", RUN_ID)
SEED = 20260823
SYSTEMS = ("hybrid", "bm25", "vector")

# 뒤집힘 지점 — 해당 카테고리 전체를 계통적으로 이동시켰을 때 NDCG@10 격차가 0 이 되는
# 평균 등급 이동량이다 (2026-08-23 실측, runs/2026-08-22c). 게이트는 그 절반으로 잡아
# 안전마진 2배를 둔다. 위험 방향은 둘 다 **사람 > LLM**(판정자가 그 층을 과소평가했다는 뜻).
#
#   B  전량 +1 → hybrid-bm25   +0.1467 → -0.0488  (f=0.7 부근에서 0)
#   V  전량 +1 → hybrid-vector +0.0589 → -0.0137  (f=0.8 부근에서 0)
GATES = {
    "B": {"flip": 0.70, "gate": 0.35, "claim": "hybrid > bm25 (NDCG@10 +0.147)",
          "threat": "§5-5 어휘 중복 편향 — 판정자가 쿼리 단어가 그대로 박힌 문서에 후하다"},
    "V": {"flip": 0.80, "gate": 0.40, "claim": "hybrid > vector (NDCG@10 +0.059)",
          "threat": "§5-9 발췌 선택기 편향 — 발췌의 절반을 피험자인 벡터 팔이 골랐다"},
}
KAPPA_REFERENCE = 0.30          # §3-1-1 GPT-4o 대 사람이 TREC DL 에서 κ=0.308. 게이트가 아니다.

_here = pathlib.Path(__file__).parent


def _load_module(name):
    spec = importlib.util.spec_from_file_location(name, _here / f"{name}.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def categorize(pool):
    """각 쌍이 어느 시스템의 top-10 에 들었는지 — 'B', 'V', 'HV', 'HBV' …

    카테고리가 곧 계통 편향의 통로다. B(BM25 단독)와 V(벡터 단독)만이 결론을 뒤집을 수 있고,
    나머지는 전량을 1등급 옮겨도 부호가 안 바뀐다(HB 는 f=1.0 에서도 +0.002).
    """
    cat, tier = {}, {}
    for q in pool["queries"]:
        kw = q["keyword"]
        tops = {s: set(q["rankings"][s]) for s in SYSTEMS}
        for it in q["pool"]:
            key = (kw, it["articleId"])
            cat[key] = "".join(s[0].upper() for s in SYSTEMS if it["articleId"] in tops[s]) or "-"
            tier[key] = q["tier"]
    return cat, tier


def load_grades(base):
    out = {}
    for line in io.open(f"{base}/judgments.jsonl", encoding="utf-8"):
        d = json.loads(line)
        if d.get("mode") == "passages" and d.get("trial", 1) == 1:
            out[(d["keyword"], d["articleId"])] = d["relevance"]
    return out


def draw(base, n_random, n_b, n_v, seed):
    pool = json.load(io.open(f"{base}/pool.json", encoding="utf-8"))
    grades = load_grades(base)
    cat, tier = categorize(pool)
    universe = sorted(grades)                       # 정렬 후 추출 — 시드가 같으면 표본도 같다
    rng = random.Random(seed)

    picked, strata = {}, []
    for key in rng.sample(universe, n_random):
        picked[key] = "random"
    for name, want in (("B", n_b), ("V", n_v)):
        rest = [k for k in universe if cat[k] == name and k not in picked]
        if len(rest) < want:
            raise SystemExit(f"[중단] {name} 층 잔여 {len(rest)} < 요청 {want}")
        for key in rng.sample(rest, want):
            picked[key] = name

    order = list(picked)
    rng.shuffle(order)                              # 층이 뭉쳐 있으면 메타 추론이 개입한다
    for key in order:
        strata.append({"keyword": key[0], "articleId": key[1], "tier": tier[key],
                       "stratum": picked[key], "category": cat[key], "llmGrade": grades[key]})
    return pool, grades, cat, strata


def sheet_records(base, strata):
    """블라인드 시트에 들어갈 것만 고른다 — 등급·층·카테고리·순위·점수는 넣지 않는다.

    devtools 로 들여다봐도 정답이 없어야 진짜 블라인드다. 채점은 human_anchor_sample.json
    쪽 매핑으로 사후에 한다.
    """
    judge = _load_module("judge")
    inputs = {}
    for line in io.open(f"{base}/judge_inputs.jsonl", encoding="utf-8"):
        d = json.loads(line)
        inputs[(d["keyword"], d["articleId"])] = d

    out = []
    for s in strata:
        key = (s["keyword"], s["articleId"])
        rec = inputs.get(key)
        if rec is None:
            raise SystemExit(f"[중단] judge_inputs.jsonl 에 {key} 가 없다 — build_passages.py 를 먼저 돌려라")
        out.append({
            "keyword": rec["keyword"],
            "articleId": rec["articleId"],
            "title": rec.get("title"),
            "translatedTitle": rec.get("translatedTitle"),
            "corporation": rec.get("corporation"),
            "category": rec.get("category"),
            "totalChunks": rec.get("totalChunks"),
            "passages": [{"text": p["text"], "span": _span(p)} for p in rec["passages"]],
            # 판정자가 실제로 받은 user 프롬프트 원문 — 사람이 같은 것을 봤다는 증거
            "promptText": judge.build_user_prompt(rec, "passages"),
        })
    return judge.RUBRIC, out


def _span(p):
    m = p.get("mergedFrom") or ([p["chunkIndex"]] if p.get("chunkIndex") is not None else [])
    if not m:
        return "?"
    return str(m[0] + 1) if len(m) == 1 else f"{m[0] + 1}~{m[-1] + 1}"


def render_sheet(rubric, records, meta):
    tpl = io.open(_here / "human_anchor_sheet.template.html", encoding="utf-8").read()
    payload = json.dumps({"meta": meta, "rubric": rubric, "records": records},
                         ensure_ascii=False, separators=(",", ":"))
    # </script> 가 페이로드 안에 있으면 스크립트 블록이 조기 종료된다.
    payload = payload.replace("</", "<\\/")
    return tpl.replace("__DATA__", payload)


# ---------------------------------------------------------------- report

def report(base, human_path, strata_path):
    sample = json.load(io.open(strata_path, encoding="utf-8"))
    strata = {(s["keyword"], s["articleId"]): s for s in sample["pairs"]}
    human = {}
    for line in io.open(human_path, encoding="utf-8"):
        line = line.strip()
        if not line:
            continue
        d = json.loads(line)
        human[(d["keyword"], d["articleId"])] = int(d["relevance"])

    ab = _load_module("ab_excerpt")
    common = [k for k in strata if k in human]
    missing = [k for k in strata if k not in human]

    def delta(keys):
        return [human[k] - strata[k]["llmGrade"] for k in keys]

    res = {"runId": RUN_ID, "sampleSeed": sample["meta"]["seed"],
           "judged": len(common), "sampled": len(strata), "missing": len(missing)}

    # ── κ 는 random 층으로만 (모집단 대표성) ────────────────────────────
    rnd = [k for k in common if strata[k]["stratum"] == "random"]
    if len(rnd) >= 2:
        pairs = [(strata[k]["llmGrade"], human[k]) for k in rnd]
        res["headline"] = {
            "n": len(rnd),
            "kappaUnweighted": round(ab.kappa(pairs), 4),
            "kappaQuadraticWeighted": round(ab.kappa(pairs, weighted=True), 4),
            "exactAgreement": round(sum(1 for a, b in pairs if a == b) / len(pairs), 4),
            "meanDelta(human-llm)": round(st.mean(delta(rnd)), 4),
            "reference": KAPPA_REFERENCE,
            "note": "게이트가 아니다 — 문헌(GPT-4o 대 사람 κ=0.308) 대조용. 음의 평균 델타는 §5-5-1 등급 인플레이션.",
        }

    # ── 게이트는 카테고리 델타로 (random 층에 딸려온 같은 카테고리 쌍을 합친다) ──
    res["gates"] = {}
    for name, g in GATES.items():
        keys = [k for k in common if strata[k]["category"] == name]
        if not keys:
            res["gates"][name] = {"n": 0, "verdict": "표본 없음"}
            continue
        d = delta(keys)
        m = st.mean(d)
        se = (st.stdev(d) / len(d) ** 0.5) if len(d) > 1 else None
        res["gates"][name] = {
            "n": len(keys), "claim": g["claim"], "threat": g["threat"],
            "meanDelta(human-llm)": round(m, 4),
            "stderr": round(se, 4) if se else None,
            "gate": g["gate"], "flipPoint": g["flip"],
            "deltaDist": dict(sorted(Counter(d).items())),
            "verdict": ("통과" if m <= g["gate"] else
                        "게이트 초과 — 이 비교를 '유의'로 보고하지 않는다"),
            "direction": ("판정자가 이 층을 과대평가 → 결론은 하한(강화)" if m < 0 else
                          "판정자가 이 층을 과소평가 → 결론 약화 방향"),
        }

    # ── 경계(1↔2) — MRR·pooledRecall 이 여기서 부호까지 바뀐다 ──
    bnd = [k for k in common if strata[k]["llmGrade"] in (1, 2) or human[k] in (1, 2)]
    cross = [k for k in bnd if (strata[k]["llmGrade"] >= 2) != (human[k] >= 2)]
    res["boundary"] = {
        "n": len(bnd), "crossings": len(cross),
        "crossRate": round(len(cross) / len(bnd), 4) if bnd else None,
        "llmHigher": sum(1 for k in cross if strata[k]["llmGrade"] >= 2),
        "humanHigher": sum(1 for k in cross if human[k] >= 2),
        "note": "관련/무관 경계를 넘나든 비율. NDCG 는 견디지만 MRR·pooledRecall 은 부호까지 바뀐다.",
    }

    res["byTier"] = {}
    for t in ("SIMPLE", "MODERATE", "COMPLEX", "SPECIFIC", "CORPORATION"):
        ks = [k for k in common if strata[k]["tier"] == t]
        if ks:
            res["byTier"][t] = {"n": len(ks), "meanDelta": round(st.mean(delta(ks)), 3),
                                "exact": round(sum(1 for k in ks if human[k] == strata[k]["llmGrade"]) / len(ks), 3)}
    return res, missing


def print_report(r, missing):
    print(f"=== 인간 앵커  {r['runId']}  (판정 {r['judged']}/{r['sampled']}쌍) ===")
    if missing:
        print(f"  [경고] 미판정 {len(missing)}쌍 — 예: {missing[:3]}")
    h = r.get("headline")
    if h:
        print(f"\n  [참고] 무작위 층 n={h['n']}")
        print(f"    unweighted κ          {h['kappaUnweighted']:.4f}  (문헌 대조 {h['reference']}, 게이트 아님)")
        print(f"    quadratic-weighted κ  {h['kappaQuadraticWeighted']:.4f}")
        print(f"    완전 일치율           {h['exactAgreement']:.4f}")
        print(f"    평균 델타 (사람−LLM)  {h['meanDelta(human-llm)']:+.4f}"
              f"  ({'판정자가 후하다 — §5-5-1 인플레이션' if h['meanDelta(human-llm)'] < 0 else '판정자가 짜다'})")
    print(f"\n  [게이트] 사전 등록 — 사람−LLM 평균 델타가 게이트를 넘으면 그 비교를 유의로 쓰지 않는다")
    for name, g in r["gates"].items():
        if not g.get("n"):
            print(f"    {name}: 표본 없음")
            continue
        se = f" ± {g['stderr']:.3f}" if g["stderr"] else ""
        mark = "✅" if g["verdict"] == "통과" else "❌"
        print(f"    {mark} {name} (n={g['n']:>2})  Δ {g['meanDelta(human-llm)']:+.3f}{se}"
              f"   게이트 +{g['gate']} · 뒤집힘 +{g['flipPoint']}")
        print(f"         주장: {g['claim']}")
        print(f"         {g['direction']}")
    b = r["boundary"]
    print(f"\n  [경계 1↔2] 관련/무관 경계를 넘나든 쌍 {b['crossings']}/{b['n']}"
          + (f" ({b['crossRate']:.1%})" if b["crossRate"] is not None else ""))
    print(f"    LLM 이 관련이라 본 것 {b['llmHigher']} · 사람만 관련이라 본 것 {b['humanHigher']}")
    if r["byTier"]:
        print(f"\n  {'층':13}{'n':>4}{'평균Δ':>9}{'일치':>8}")
        for t, v in r["byTier"].items():
            print(f"    {t:11}{v['n']:>4}{v['meanDelta']:>+9.3f}{v['exact']:>8.3f}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", metavar="HUMAN_JSONL",
                    help="사람 판정(JSONL: keyword/articleId/relevance)을 표본과 대조한다")
    ap.add_argument("--seed", type=int, default=SEED)
    ap.add_argument("--n-random", type=int, default=30)
    ap.add_argument("--n-b", type=int, default=15)
    ap.add_argument("--n-v", type=int, default=15)
    args = ap.parse_args()

    sample_path = f"{BASE}/human_anchor_sample.json"

    if args.report:
        r, missing = report(BASE, args.report, sample_path)
        out = f"{BASE}/human_anchor_report.json"
        io.open(out, "w", encoding="utf-8").write(json.dumps(r, ensure_ascii=False, indent=2) + "\n")
        print_report(r, missing)
        print(f"\n→ {out}")
        return

    pool, grades, cat, strata = draw(BASE, args.n_random, args.n_b, args.n_v, args.seed)
    rubric, records = sheet_records(BASE, strata)

    meta = {
        "runId": RUN_ID, "seed": args.seed,
        "strata": {"random": args.n_random, "B": args.n_b, "V": args.n_v},
        "universe": len(grades),
        "categoryPopulation": dict(sorted(Counter(cat.values()).items(), key=lambda x: -x[1])),
        "gates": GATES, "kappaReference": KAPPA_REFERENCE,
        "design": "docs/testing/SEARCH_ACCURACY_EVAL_DESIGN.md §3-5-1",
    }
    io.open(sample_path, "w", encoding="utf-8").write(
        json.dumps({"meta": meta, "pairs": strata}, ensure_ascii=False, indent=2) + "\n")

    sheet = f"{BASE}/human_anchor_sheet.html"
    io.open(sheet, "w", encoding="utf-8").write(render_sheet(rubric, records, {
        "runId": RUN_ID, "seed": args.seed, "total": len(records)}))

    print(f"=== 인간 앵커 표본  {len(strata)}쌍 (seed {args.seed}) ===")
    print(f"  층      {dict(Counter(s['stratum'] for s in strata))}")
    print(f"  카테고리 {dict(sorted(Counter(s['category'] for s in strata).items()))}"
          f"   ← 게이트는 카테고리 기준(무작위 층에 딸려온 것 포함)")
    print(f"  쿼리 층 {dict(sorted(Counter(s['tier'] for s in strata).items()))}")
    print(f"  LLM 등급 {dict(sorted(Counter(s['llmGrade'] for s in strata).items()))}  (시트에는 없다)")
    print(f"  발췌 문자수 p50 {int(st.median(sum(len(p['text']) for p in r['passages']) for r in records))}")
    print(f"\n→ {sample_path}   (정답 매핑 — 판정 끝나기 전에 열지 말 것)")
    print(f"→ {sheet}   (블라인드 시트)")


if __name__ == "__main__":
    main()
