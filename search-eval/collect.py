#!/usr/bin/env python3
"""T2 후보 풀 수집 — 동결된 쿼리 세트를 prod 검색 API로 1회씩 호출해 원본 응답을 보존한다.

설계: docs/testing/SEARCH_ACCURACY_EVAL_DESIGN.md §4 T2

- size는 전체 결과를 덮도록 크게 준다(상한 없음 확인, 실측 totalElements 최대 193).
  전량을 받아야 BM25/벡터 단독 랭킹이 **절단 없이** 재구성된다.
- 순차 호출 + 간격 (§5-3: prod search_logs 오염 최소화, 동시성 제한 15에 걸리지 않도록)
- 응답은 가공하지 않고 그대로 저장한다 — 가공본만 남기면 재분석이 불가능하다.
"""
import json, time, io, os, sys, datetime, urllib.parse, urllib.request, urllib.error

BASE = "https://newcodes.net/api/search/articles"
# 동결된 세트(§3-3)는 고치지 않는다. 확장 세트는 별도 파일을 QUERIES 로 주입한다(T8).
QUERIES = os.environ.get("QUERIES", "search-eval/queries.json")
SIZE = int(os.environ.get("COLLECT_SIZE", "300"))
DELAY = float(os.environ.get("COLLECT_DELAY", "1.5"))
PROVENANCE_FIELDS = ("sourceBm25Rank", "sourceVectorRank")

def provenance_error(content):
    if any(any(field not in article for field in PROVENANCE_FIELDS) for article in content):
        return "provenance 필드 없음"
    for field in PROVENANCE_FIELDS:
        ranks = [article[field] for article in content if article[field] is not None]
        if any(type(rank) is not int or rank < 1 for rank in ranks):
            return f"{field} 타입/범위 오류"
        if sorted(ranks) != list(range(1, len(ranks) + 1)):
            return f"{field} 중복/비연속"
    return None

def fetch(keyword):
    url = f"{BASE}?keyword={urllib.parse.quote(keyword)}&size={SIZE}"
    t0 = time.monotonic()
    try:
        with urllib.request.urlopen(url, timeout=60) as r:
            body = json.load(r); status = r.status
    except urllib.error.HTTPError as e:
        return {"status": e.code, "error": e.read().decode("utf-8", "replace")[:300],
                "latencyMs": round((time.monotonic() - t0) * 1000)}
    return {"status": status, "body": body,
            "latencyMs": round((time.monotonic() - t0) * 1000)}

def main():
    qdoc = json.load(io.open(QUERIES, encoding="utf-8"))
    run_id = os.environ.get("RUN_ID", datetime.date.today().isoformat())
    outdir = f"search-eval/runs/{run_id}"
    os.makedirs(outdir, exist_ok=True)
    started = datetime.datetime.now().astimezone().isoformat()

    incomplete, throttled, http_errors, incompatible = [], [], [], []
    with io.open(f"{outdir}/raw.jsonl", "w", encoding="utf-8") as out:
        for i, q in enumerate(qdoc["queries"], 1):
            k = q["keyword"]
            r = fetch(k)
            rec = {"keyword": k, "tier": q["tier"], "appTier": q["appTier"],
                   "requestedSize": SIZE, "fetchedAt": datetime.datetime.now().astimezone().isoformat(),
                   **r}
            out.write(json.dumps(rec, ensure_ascii=False) + "\n"); out.flush()

            if r["status"] == 429:
                throttled.append(k); note = "429!"
            elif r["status"] != 200:
                http_errors.append({"keyword": k, "status": r["status"]})
                note = f"HTTP {r['status']}"
            else:
                total, got = r["body"].get("totalElements", 0), len(r["body"].get("content", []))
                provenance_problem = provenance_error(r["body"].get("content", []))
                if provenance_problem:
                    incompatible.append({"keyword": k, "error": provenance_problem})
                note = provenance_problem or ("" if got >= total else f"불완전 {got}/{total}")
                if got < total: incomplete.append((k, got, total))
            print(f"[{i:2}/{len(qdoc['queries'])}] {q['tier']:12} {k[:30]:32} "
                  f"total={r.get('body',{}).get('totalElements','-'):>4} "
                  f"got={len(r.get('body',{}).get('content',[])):>4} {r['latencyMs']:>5}ms {note}", flush=True)
            time.sleep(DELAY)

    meta = {"runId": run_id, "querySet": QUERIES, "startedAt": started,
            "finishedAt": datetime.datetime.now().astimezone().isoformat(),
            "requestedSize": SIZE, "delaySeconds": DELAY,
            "queryCount": len(qdoc["queries"]),
            "querySetVersion": qdoc["version"], "querySetFrozenAt": qdoc["frozenAt"],
            "throttled429": throttled, "httpErrors": http_errors, "incompleteFetches": incomplete,
            "incompatibleResponses": incompatible,
            "requiredProvenanceFields": list(PROVENANCE_FIELDS)}
    io.open(f"{outdir}/collect_meta.json", "w", encoding="utf-8").write(
        json.dumps(meta, ensure_ascii=False, indent=2) + "\n")
    print(f"\n429: {len(throttled)}건 | 불완전 수집: {len(incomplete)}건 "
          f"| provenance 불일치: {len(incompatible)}건 → {outdir}/")
    if throttled or http_errors or incomplete or incompatible:
        raise SystemExit("HTTP/완전성/provenance 검증 실패로 T2 데이터를 사용할 수 없습니다.")

if __name__ == "__main__":
    main()
