#!/usr/bin/env python3
"""T3 A/B — 발췌 방식이 판정을 바꾸는가 (head-1200자 vs 질의집중 passage).

설계: docs/testing/SEARCH_ACCURACY_EVAL_DESIGN.md §3-2-1(개정판)

입력:  runs/<RUN_ID>/judgments_ab.jsonl  (judge.py --mode ab)
       runs/<RUN_ID>/docs.jsonl          (head-1200자 원문 — evidence 가시성 판정용)
산출:  runs/<RUN_ID>/ab_excerpt.json

**사전 결정 규칙 (판정 전에 못박았다. 결과를 보고 기준을 바꾸지 않는다):**
  |평균 등급 차| < 0.15  AND  unweighted Cohen's κ >= 0.7
    → "발췌 방식이 판정을 바꾸지 않는다" → 본판정은 싼 쪽(head1200)
  그 외 → 본판정은 passages
κ 는 **unweighted** 를 쓴다. 순서형이라 quadratic-weighted 가 더 관대한데,
"발췌를 바꿨더니 등급이 달라졌는가"를 묻는 자리에서는 엄격한 쪽이 맞다(둘 다 보고한다).
"""
import io, json, os, re
from collections import Counter, defaultdict

RUN_ID = os.environ.get("RUN_ID", "2026-08-22b")
BASE = os.path.join("search-eval", "runs", RUN_ID)
DELTA_MAX, KAPPA_MIN = 0.15, 0.70


def norm(s):
    return re.sub(r"\s+", " ", s or "").strip()


def kappa(pairs, weighted=False):
    """Cohen's κ. weighted=True 면 quadratic weights."""
    if not pairs:
        return None
    cats = sorted({v for p in pairs for v in p})
    idx = {c: i for i, c in enumerate(cats)}
    k, n = len(cats), len(pairs)
    obs = [[0] * k for _ in range(k)]
    for a, b in pairs:
        obs[idx[a]][idx[b]] += 1
    ra = [sum(r) for r in obs]
    cb = [sum(obs[i][j] for i in range(k)) for j in range(k)]

    def w(i, j):
        if not weighted:
            return 0.0 if i == j else 1.0
        return ((cats[i] - cats[j]) ** 2) / ((cats[-1] - cats[0]) ** 2 or 1)

    po = sum(w(i, j) * obs[i][j] for i in range(k) for j in range(k)) / n
    pe = sum(w(i, j) * ra[i] * cb[j] for i in range(k) for j in range(k)) / (n * n)
    if pe == 0:
        return 1.0
    return round(1 - po / pe, 4)


def main():
    path = f"{BASE}/judgments_ab.jsonl"
    rows = [json.loads(l) for l in io.open(path, encoding="utf-8")]
    by = defaultdict(dict)
    for r in rows:
        by[(r["keyword"], r["articleId"])][r["mode"]] = r
    paired = {k: v for k, v in by.items() if "passages" in v and "head1200" in v}

    excerpt = {}
    for line in io.open(f"{BASE}/docs.jsonl", encoding="utf-8"):
        d = json.loads(line)
        excerpt[d["articleId"]] = norm(d.get("excerpt") or "")

    grades = [(v["passages"]["relevance"], v["head1200"]["relevance"]) for v in paired.values()]
    n = len(grades)
    exact = sum(1 for a, b in grades if a == b)
    mean_p = sum(a for a, _ in grades) / n
    mean_h = sum(b for _, b in grades) / n

    # passages 판정의 evidence 가 head-1200자 안에서도 보였는가
    ev_total = ev_outside = 0
    for (kw, aid), v in paired.items():
        head = excerpt.get(aid, "")
        for e, g in zip(v["passages"]["evidence"], v["passages"]["evidenceGrounded"]):
            if not g:
                continue
            ev_total += 1
            if norm(e) not in head:
                ev_outside += 1

    def side(mode):
        rs = [v[mode] for v in paired.values()]
        dist = Counter(r["relevance"] for r in rs)
        et = sum(len(r["evidence"]) for r in rs)
        eg = sum(r["groundedCount"] for r in rs)
        return {"gradeDist": {str(g): dist.get(g, 0) for g in (0, 1, 2, 3)},
                "meanGrade": round(sum(r["relevance"] for r in rs) / len(rs), 3),
                "evidenceGroundedRate": round(eg / et, 4) if et else None,
                "needsReview": sum(1 for r in rs if r["needsReview"])}

    k_un = kappa(grades, False)
    delta = mean_p - mean_h
    same = abs(delta) < DELTA_MAX and (k_un or 0) >= KAPPA_MIN
    by_tier = defaultdict(list)
    for (kw, aid), v in paired.items():
        by_tier[v["passages"]["tier"]].append(
            (v["passages"]["relevance"], v["head1200"]["relevance"]))

    out = {
        "runId": RUN_ID, "pairedSamples": n,
        "decisionRule": {
            "preRegistered": "|평균 등급 차| < 0.15 AND unweighted κ >= 0.7 → head1200 로 본판정, 아니면 passages",
            "meanGradeDelta(passages-head1200)": round(delta, 4),
            "kappaUnweighted": k_un,
            "kappaQuadraticWeighted": kappa(grades, True),
            "exactAgreement": round(exact / n, 4),
            "verdict": "발췌 방식이 판정을 바꾸지 않는다 → head1200 로 본판정"
                       if same else "발췌 방식이 판정을 바꾼다 → passages 로 본판정",
        },
        "passages": side("passages"), "head1200": side("head1200"),
        "evidenceVisibility": {
            "groundedEvidence": ev_total,
            "outsideHead1200": ev_outside,
            "outsideRate": round(ev_outside / ev_total, 4) if ev_total else None,
            "note": "passages 판정의 근거 인용 중 기존 head-1200자 발췌에는 없던 비율. "
                    "높을수록 질의집중 선택이 실제로 새 근거를 보여준 것이다.",
        },
        "byTier": {t: {"n": len(g), "meanDelta": round(sum(a - b for a, b in g) / len(g), 3),
                       "exact": round(sum(1 for a, b in g if a == b) / len(g), 3)}
                   for t, g in sorted(by_tier.items())},
    }
    io.open(f"{BASE}/ab_excerpt.json", "w", encoding="utf-8").write(
        json.dumps(out, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(out, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
