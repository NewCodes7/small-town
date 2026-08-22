#!/usr/bin/env python3
"""T3-P0 준비 — prod 에서 1회 실행할 자립형 SQL 을 생성한다.

설계: docs/testing/SEARCH_ACCURACY_EVAL_DESIGN.md §3-2-1 (개정판)

왜 SQL 인가:
  앱의 Clova 청크 벡터를 노출하는 admin 엔드포인트가 없다 —
  /admin/articles/{id}/embeddings 는 hasEmbedding/차원만, /admin/chunks/{id}/embedding 도 같다.
  그런데 벡터는 prod DB(clova_chunk_vectors)에 있고, 50개 쿼리의 Clova 임베딩도
  T2 수집 때 search_query_embedding 에 이미 캐시됐다. 즉 **임베딩 API 재호출 없이**
  앱이 실제로 쓰는 벡터 공간 그대로 청크 단위 top-3 를 뽑을 수 있다.

키워드 정규화는 SearchQueryEmbeddingService.normalizeKeyword 와 같아야 한다:
  trim().toLowerCase() 후 연속 공백 1칸 압축.

산출: runs/<RUN_ID>/passages.sql
  prod 호스트에서  psql "$DB_URL" -f passages.sql  로 실행하면
  같은 디렉터리에 chunks.csv / vec_top3.csv 가 떨어진다.
"""
import importlib.util, json, io, os, pathlib, re, sys, datetime

RUN_ID = os.environ.get("RUN_ID", "2026-08-22b")
BASE = os.path.join("search-eval", "runs", RUN_ID)


def build_pool_module():
    """같은 디렉터리의 build_pool.py 를 로드한다 (경로 무관, sys.path 에 의존하지 않는다)."""
    spec = importlib.util.spec_from_file_location(
        "build_pool", pathlib.Path(__file__).with_name("build_pool.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_pool(base):
    """pool.json 을 읽되, 낡은 풀이면 중단한다.

    커밋 9f86949a 이후 build_pool.rankings() 는 sourceBm25Rank/sourceVectorRank 로 단독 랭킹을
    복원한다. 그 이전 풀은 cross-scoring 이 사후에 덮어쓴 순위로 만들어져 **아티클 집합 자체가
    다르다** — 그대로 쓰면 틀린 대상을 판정하고도 파이프라인은 에러 없이 끝까지 간다(§7-5).

    판정 근거는 pool.json 안의 출처 표식이다. 예전처럼 raw.jsonl 을 다시 읽어 검사하면 안 된다 —
    그 파일은 gitignore 대상이라 **없는 것이 정상 상태**여서, 없을 때 통과시키는 순간
    가드가 사라진다(클론 직후가 정확히 그 상태다).
    """
    path = f"{base}/pool.json"
    pool = json.load(io.open(path, encoding="utf-8"))
    expected = build_pool_module().POOL_PROVENANCE
    actual = pool.get("provenance")
    if actual != expected:
        sys.exit(f"[중단] 낡은 풀이다 — {path} 의 provenance 가 {actual!r} (기대: {expected!r}).\n"
                 f"        cross-scoring 이 덮어쓴 순위로 만든 풀이라 아티클 집합이 틀리다.\n"
                 f"        서버 변경 배포 후 collect.py → build_pool.py 로 T2 를 재수집하고 "
                 f"다시 이 스크립트를 돌릴 것.")
    return pool


def normalize_keyword(kw):
    """SearchQueryEmbeddingService:113 과 동일한 정규화."""
    return re.sub(r"\s+", " ", (kw or "").strip().lower())


def sql_str(s):
    return "'" + s.replace("'", "''") + "'"


def main():
    pool = load_pool(BASE)

    pairs = []          # (normalized_keyword, article_id)
    seen = set()
    for q in pool["queries"]:
        kw = normalize_keyword(q["keyword"])
        for it in q["pool"]:
            key = (kw, it["articleId"])
            if key not in seen:
                seen.add(key)
                pairs.append(key)
    pairs.sort()
    article_ids = sorted({a for _, a in pairs})
    keywords = sorted({k for k, _ in pairs})

    out = io.open(f"{BASE}/passages.sql", "w", encoding="utf-8")
    w = out.write

    w(f"""-- T3-P0: 판정 발췌용 청크·벡터 추출 (생성 시각 {datetime.datetime.now().astimezone().isoformat()})
-- 생성기: search-eval/make_passages_sql.py   RUN_ID={RUN_ID}
-- 실행:  prod 호스트에서  psql "$DB_URL" -f passages.sql
-- 산출:  chunks.csv (청크 전량) / vec_top3.csv (쿼리x아티클별 코사인 top-3)
--
-- 부하 메모: DB 는 vCPU 1 / RAM 1GB 다. eval_pair 로 {len(article_ids)}개 아티클에 한정하며
-- (B) 는 LATERAL LIMIT 3 이라 idx_clova_chunk_article_id 로 아티클당 청크(~8개)만 만진다.
-- 크롤러 시간대(04:00~05:30 KST)는 피할 것.
\\set ON_ERROR_STOP on
\\timing on

BEGIN;
SET LOCAL statement_timeout = '600s';

CREATE TEMP TABLE eval_pair (keyword text NOT NULL, article_id bigint NOT NULL) ON COMMIT DROP;
""")

    # 1,174행 — 1000행씩 끊어 INSERT
    CH = 1000
    for i in range(0, len(pairs), CH):
        chunk = pairs[i:i + CH]
        w("INSERT INTO eval_pair (keyword, article_id) VALUES\n")
        w(",\n".join(f"  ({sql_str(k)},{a})" for k, a in chunk))
        w(";\n")

    w("""
CREATE INDEX ON eval_pair (article_id);
CREATE INDEX ON eval_pair (keyword);
ANALYZE eval_pair;

\\echo ''
\\echo '=== [preflight 1] 쿼리 임베딩 커버리지 (기대: 두 값이 같아야 한다) ==='
SELECT (SELECT count(DISTINCT keyword) FROM eval_pair)                       AS keywords_total,
       (SELECT count(*) FROM (SELECT DISTINCT p.keyword FROM eval_pair p
          JOIN search_query_embedding q ON q.normalized_keyword = p.keyword) x) AS keywords_with_embedding;

\\echo ''
\\echo '=== [preflight 2] 임베딩이 없는 키워드 (있으면 prod 검색 API로 1회씩 태운 뒤 재실행) ==='
SELECT DISTINCT p.keyword
FROM eval_pair p
WHERE NOT EXISTS (SELECT 1 FROM search_query_embedding q WHERE q.normalized_keyword = p.keyword)
ORDER BY 1;

\\echo ''
\\echo '=== [preflight 3] 청크가 없는 풀 아티클 수 (기대: 0 또는 소수) ==='
SELECT count(*) AS articles_without_chunk
FROM (SELECT DISTINCT article_id FROM eval_pair) a
WHERE NOT EXISTS (SELECT 1 FROM clova_article_chunk c WHERE c.article_id = a.article_id);

\\echo ''
\\echo '=== [preflight 4] 벡터가 없는 청크 수 (기대: 0) ==='
SELECT count(*) AS chunks_without_vector
FROM clova_article_chunk c
WHERE c.article_id IN (SELECT DISTINCT article_id FROM eval_pair)
  AND NOT EXISTS (SELECT 1 FROM clova_chunk_vectors v WHERE v.id = c.id);

\\echo ''
\\echo '=== (A) 청크 전량 -> chunks.csv ==='
""")

    # \copy 는 psql 메타명령이라 반드시 한 줄이어야 한다.
    copy_a = (
        "\\copy (SELECT c.article_id, c.chunk_index, c.token_count, cc.content "
        "FROM clova_article_chunk c JOIN clova_chunk_contents cc ON cc.id = c.id "
        "WHERE c.article_id IN (SELECT DISTINCT article_id FROM eval_pair) "
        "ORDER BY c.article_id, c.chunk_index) "
        "TO 'chunks.csv' WITH (FORMAT csv, HEADER true)"
    )
    w(copy_a + "\n\n\\echo ''\n\\echo '=== (B) 쿼리x아티클별 코사인 top-3 -> vec_top3.csv ==='\n")

    # <=> 는 코사인 거리다. q.embedding 의 정규화 여부에 영향받지 않는다.
    copy_b = (
        "\\copy (SELECT p.keyword, p.article_id, t.chunk_index, t.sim "
        "FROM (SELECT DISTINCT keyword, article_id FROM eval_pair) p "
        "JOIN search_query_embedding q ON q.normalized_keyword = p.keyword "
        "CROSS JOIN LATERAL (SELECT c.chunk_index, 1 - (v.embedding_normalized <=> q.embedding) AS sim "
        "FROM clova_article_chunk c JOIN clova_chunk_vectors v ON v.id = c.id "
        "WHERE c.article_id = p.article_id "
        "ORDER BY v.embedding_normalized <=> q.embedding LIMIT 3) t "
        "ORDER BY p.keyword, p.article_id, t.sim DESC) "
        "TO 'vec_top3.csv' WITH (FORMAT csv, HEADER true)"
    )
    w(copy_b + "\n\nCOMMIT;\n")
    w("""
\\echo ''
\\echo '완료. chunks.csv / vec_top3.csv 를 devcontainer 의 search-eval/runs/<RUN_ID>/ 로 가져올 것.'
""")
    out.close()

    meta = {
        "runId": RUN_ID,
        "generatedAt": datetime.datetime.now().astimezone().isoformat(),
        "pairs": len(pairs),
        "uniqueArticles": len(article_ids),
        "uniqueKeywords": len(keywords),
        "keywordNormalization": "trim().toLowerCase() + collapse whitespace (SearchQueryEmbeddingService:113)",
    }
    io.open(f"{BASE}/passages_sql_meta.json", "w", encoding="utf-8").write(
        json.dumps(meta, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(meta, ensure_ascii=False, indent=2))
    print(f"\n생성: {BASE}/passages.sql  ({os.path.getsize(BASE + '/passages.sql'):,} bytes)")


if __name__ == "__main__":
    main()
