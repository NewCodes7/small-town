#!/usr/bin/env python3
"""T1 후보 프로브 — 각 후보 쿼리를 prod 검색 API로 1회 호출해 세트 선정 근거를 수집한다.

수집 항목: 총 결과 수, BM25/벡터 팔의 기여 여부, 지연.
※ 이 호출은 prod search_logs에 기록된다 (설계서 §5-3). 순차 실행하며 간격을 둔다.
"""
import json, time, io, sys, urllib.parse, urllib.request

BASE = "https://newcodes.net/api/search/articles"
SIZE = 20
DELAY = 1.5

def probe(keyword):
    url = f"{BASE}?keyword={urllib.parse.quote(keyword)}&size={SIZE}"
    t0 = time.monotonic()
    with urllib.request.urlopen(url, timeout=30) as r:
        body = json.load(r)
        status = r.status
    elapsed = time.monotonic() - t0
    content = body.get("content", [])
    return {
        "keyword": keyword,
        "status": status,
        "totalElements": body.get("totalElements", 0),
        "returned": len(content),
        "bm25Count": sum(1 for a in content if a.get("bm25Rank") is not None),
        "vectorCount": sum(1 for a in content if a.get("foundByVector") is True),
        "vectorScored": sum(1 for a in content if a.get("vectorRank") is not None),
        "latencyMs": round(elapsed * 1000),
    }

def main():
    cands = json.load(io.open("search-eval/runs/candidates.json", encoding="utf-8"))
    out = io.open("search-eval/runs/probe.jsonl", "w", encoding="utf-8")
    for i, c in enumerate(cands, 1):
        try:
            r = probe(c["keyword"]); r["tier"] = c["tier"]
        except Exception as e:
            r = {"keyword": c["keyword"], "tier": c["tier"], "error": f"{type(e).__name__}: {e}"}
        out.write(json.dumps(r, ensure_ascii=False) + "\n"); out.flush()
        flag = "ERR" if "error" in r else ("BM25=0" if r["bm25Count"] == 0 else "")
        print(f"[{i:2}/{len(cands)}] {r['tier']:8} {c['keyword'][:28]:30} "
              f"total={r.get('totalElements','-'):>4} bm25={r.get('bm25Count','-'):>2} "
              f"vec={r.get('vectorCount','-'):>2} {r.get('latencyMs','-')}ms {flag}", flush=True)
        time.sleep(DELAY)
    out.close()

if __name__ == "__main__":
    main()
