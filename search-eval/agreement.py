#!/usr/bin/env python3
"""판정자 신뢰도 — 두 등급 출처의 일치도.

설계: docs/testing/SEARCH_ACCURACY_EVAL_DESIGN.md §3-5, §4 T4

용도 둘:
  자기 일치도  같은 판정자가 같은 쌍을 두 번 판정 (--a trial:1 --b trial:2), 목표 κ >= 0.6
  인간 앵커    사람 판정과 대조        (--a trial:1 --b file:human.jsonl), κ ~ 0.3 이상이면 정상
              (§3-1-1 — UMBRELA 후속 연구에서 GPT-4o 대 사람이 κ 0.308 이었다)

κ 는 unweighted 를 주로 보고 quadratic 도 함께 낸다. 두 값이 크게 다르면 불일치가 인접 등급에
몰려 있다는 뜻이고, 그 자체가 읽을거리다.

일치도가 낮으면 지표가 통째로 흔들린다 — 이 수치 없이는 베이스라인이 "한 번 매긴 등급" 위에 선다.
"""
import argparse, importlib.util, io, json, os, pathlib, statistics as st
from collections import Counter, defaultdict

RUN_ID = os.environ.get("RUN_ID", "2026-08-22c")
BASE = os.path.join("search-eval", "runs", RUN_ID)
TIERS = ("SIMPLE", "MODERATE", "COMPLEX", "SPECIFIC", "CORPORATION")

# κ 는 ab_excerpt.py 의 구현을 그대로 쓴다 — 계산이 두 벌이면 언젠가 갈라진다.
_spec = importlib.util.spec_from_file_location(
    "ab_excerpt", pathlib.Path(__file__).with_name("ab_excerpt.py"))
_ab = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_ab)
kappa = _ab.kappa


def load_source(spec, base):
    """spec: 'trial:N' 또는 'file:<경로>'. → {(keyword, articleId): grade}"""
    kind, _, val = spec.partition(":")
    if kind == "trial":
        want = int(val)
        out = {}
        for line in io.open(f"{base}/judgments.jsonl", encoding="utf-8"):
            d = json.loads(line)
            if d.get("mode") == "passages" and d.get("trial", 1) == want:
                out[(d["keyword"], d["articleId"])] = d["relevance"]
        return out
    if kind == "file":
        return {(d["keyword"], d["articleId"]): d["relevance"]
                for d in (json.loads(l) for l in io.open(val, encoding="utf-8"))}
    raise SystemExit(f"[중단] 알 수 없는 출처: {spec} (trial:N 또는 file:경로)")


def analyse(A, B, tiers):
    keys = sorted(set(A) & set(B))
    pairs = [(A[k], B[k]) for k in keys]
    if not pairs:
        raise SystemExit("[중단] 두 출처에 공통된 쌍이 없다.")
    exact = sum(1 for a, b in pairs if a == b) / len(pairs)
    delta = st.mean(a - b for a, b in pairs)
    conf = [[0] * 4 for _ in range(4)]
    for a, b in pairs:
        conf[a][b] += 1
    out = {
        "n": len(pairs),
        "exactAgreement": round(exact, 4),
        "kappaUnweighted": round(kappa(pairs), 4),
        "kappaQuadraticWeighted": round(kappa(pairs, weighted=True), 4),
        "meanDelta(a-b)": round(delta, 4),
        "deltaDist": dict(sorted(Counter(a - b for a, b in pairs).items())),
        "confusion": conf,
        "byTier": {},
    }
    for t in TIERS:
        ks = [k for k in keys if tiers.get(k) == t]
        ps = [(A[k], B[k]) for k in ks]
        if len(ps) >= 2:
            out["byTier"][t] = {"n": len(ps),
                                "exact": round(sum(1 for a, b in ps if a == b) / len(ps), 3),
                                "kappaUnweighted": round(kappa(ps), 3)}
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--a", default="trial:1")
    ap.add_argument("--b", default="trial:2")
    ap.add_argument("--target", type=float, default=0.6, help="κ 목표 (자기 일치도 0.6, 인간 앵커 0.3)")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    tiers = {}
    for line in io.open(f"{BASE}/judgments.jsonl", encoding="utf-8"):
        d = json.loads(line)
        tiers[(d["keyword"], d["articleId"])] = d["tier"]

    A, B = load_source(args.a, BASE), load_source(args.b, BASE)
    r = analyse(A, B, tiers)
    r = {"runId": RUN_ID, "a": args.a, "b": args.b, "target": args.target, **r}
    r["verdict"] = ("목표 충족" if r["kappaUnweighted"] >= args.target
                    else f"목표 미달 (κ {r['kappaUnweighted']} < {args.target})")

    out = args.out or f"{BASE}/agreement_{args.a.replace(':','')}_{args.b.replace(':','')}.json"
    io.open(out, "w", encoding="utf-8").write(json.dumps(r, ensure_ascii=False, indent=2) + "\n")

    print(f"=== 일치도  {args.a} vs {args.b}  (공통 {r['n']}쌍) ===")
    print(f"  완전 일치율          {r['exactAgreement']:.4f}")
    print(f"  unweighted κ         {r['kappaUnweighted']:.4f}   목표 {args.target}  → {r['verdict']}")
    print(f"  quadratic-weighted κ {r['kappaQuadraticWeighted']:.4f}")
    print(f"  평균 등급 차 (a−b)   {r['meanDelta(a-b)']:+.4f}")
    print(f"  차이 분포            {r['deltaDist']}")
    print("\n  혼동 행렬 (행=a, 열=b)")
    print("         b0   b1   b2   b3")
    for i, row in enumerate(r["confusion"]):
        print(f"    a{i} " + "".join(f"{c:5}" for c in row))
    if r["byTier"]:
        print(f"\n  {'층':13}{'n':>4}{'일치':>8}{'κ':>8}")
        for t, v in r["byTier"].items():
            print(f"    {t:11}{v['n']:>4}{v['exact']:>8.3f}{v['kappaUnweighted']:>8.3f}")
    print(f"\n→ {out}")


if __name__ == "__main__":
    main()
