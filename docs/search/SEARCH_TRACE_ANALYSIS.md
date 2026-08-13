# 검색 API 트레이싱 분석 — `GET /api/search/articles`

**대상 트레이스**: `850054decdf63c36a3cc480f991fe502` (856ms)
**비교 트레이스**: `834af73427b6674452b85d95ac8785c9` (1428ms)
**출처**: Grafana Tempo (`service.name=small-town`), 2026-08-13 수집분
**주의**: 표본 2건이다. "일관됨"이라고 쓴 항목은 두 트레이스에서 값이 붙어 있다는 뜻이지, 분포를 측정한 것이 아니다.

---

## 1. 타임라인 (트레이스 A, 856ms / 24 span)

`s` = 루트 span 시작 기준 오프셋(ms), `ms` = 구간 길이.

| s | ms | span | 정체 |
|---:|---:|------|------|
| 0 | 856 | `http get /api/search/articles` | 루트 (200, `has_keyword=true`) |
| 0 | 7 | `security filterchain before` | Spring Security 15-filter chain |
| 4 | 1 | `SELECT users` | `AuthorizationFilter` — 이메일로 User 조회 |
| 7 | 849 | `secured request` | 핸들러 본체 |
| **22** | **164** | `SELECT term_synonym ⋈ term ⋈ term` | **유의어 확장 (직렬 선행 구간)** |
| 190 | 169 | `bm25-search` → `article_analyzed_content` | ParadeDB `@@@ parse(...)` LIMIT 100 |
| 191 | 351 | `vector-search-filtered` | ↓ 4개 자식, BM25와 **병렬** |
| 193 | 1 | `SELECT search_query_embedding` | 쿼리 임베딩 DB 캐시 조회 (miss) |
| 195 | **246** | `http post` Clova | **임베딩 API 호출** |
| 447 | 3 | `INSERT search_query_embedding` | 임베딩 upsert |
| 449 | 87 | `SELECT` 2-stage 벡터 | binary HNSW 후보 → halfvec 재랭킹 |
| 454 | 3 | `SELECT search_logs` | 임베딩–search_log 연결용 |
| **543** | **105** | `SELECT` cross-scoring 벡터 | BM25-only 항목에 벡터 점수 보충 |
| 544 | 94 | `SELECT` AI요약 chunk | `warmChunkCacheAsync` — **비동기, 크리티컬 패스 밖**. ~~현재는 삭제됨~~ (아래 주석 참고) |
| **649** | **171** | `bm25-supplement` | Vector-only 항목에 BM25 점수 보충 |
| 823 | 2 | `SELECT article (id, published_at)` | deleted_at 검증 + 날짜 보충 |
| 828 | 0.4 | `nsf-fusion-weighted` | NSF 융합 (순수 CPU) |
| 831 | 8 | `SELECT article ⋈ corporation ⋈ category` | 최종 hydration |
| 843 | 1 | `SELECT users` | **필터체인과 동일 쿼리 재실행** |
| 847 | 4 | `SELECT like_log` | 좋아요 여부 |

### 크리티컬 패스

```
164 (유의어)  →  351 (벡터, BM25 169과 병렬)  →  105 (벡터 보충)  →  171 (BM25 보충)  →  33 (마무리)
= 824ms / 856ms
```

병렬화가 실제로 먹히는 구간은 BM25 ∥ Vector **한 곳뿐**이고, 앞뒤로 붙은 직렬 구간(164 + 105 + 171 = 440ms)이 전체의 절반이다.

---

## 2. 각 쿼리가 무슨 쿼리인가

| # | 쿼리 | 호출부 | A / B (ms) |
|---|------|--------|-----------|
| 1 | `users ⋈ role ⟕ provider WHERE email=?` | `AuthorizationFilter` (Security) | 1 / 1 |
| 2 | `term_synonym ⋈ term t1 ⋈ term t2 WHERE t1.term IN (?) OR t2.term IN (?)` | `TermSynonymRepository.findSynonymPairsByTerms` ← `SemanticTermExpansionService.expandSearchTerms` | **164 / 150** |
| 3 | `SELECT id, paradedb.score(id), published_at FROM article_analyzed_content WHERE @@@ parse(?) ORDER BY bm25_score DESC LIMIT ?` | `ArticleSearchService.executeBM25Search` | **167 / 157** |
| 4 | `search_query_embedding WHERE normalized_keyword=?` | `SearchQueryEmbeddingService` — 임베딩 캐시 | 1 / 1 |
| 5 | `POST clovastudio.../embedding/v2` (HTTP) | Clova Embedding v2 | **246 / 658** |
| 6 | `INSERT search_query_embedding ... ON CONFLICT DO UPDATE` | 임베딩 캐시 저장 | 3 / 8 |
| 7 | `WITH query_vec … candidates(binary HNSW LIMIT ?) … agg(rn<=topK)` | `ArticleChunkRepository` 2단계 벡터 본검색 | 87 / 76 |
| 8 | `search_logs WHERE search_keyword=? ORDER BY created_at DESC` | `SearchQueryEmbeddingService` — search_log 연결 | 3 / 2 |
| 9 | `WITH q … stage1(hamming, article_id = ANY(?)) … stage2(halfvec)` | `VectorSearchService.computeSimilarityForSearchCrossScoring` | 105 / 23 |
| 10 | `WITH query_vec … candidates … JOIN clova_chunk_contents cc` | `VectorSearchService.warmChunkCacheAsync` → `findTopChunksForAiSummary` (**비동기**) — **삭제됨** | 94 / 30 |
| 11 | `… WHERE @@@ parse(?) AND asi.id IN (?)` | `ArticleSearchService.computeBM25ScoreForArticles` | **169 / 167** |
| 12 | `article (id, published_at) WHERE id IN (?)` | deleted_at 검증 | 2 / 2 |
| 13 | `article ⋈ corporation ⟕ category WHERE id IN (?)` | 최종 엔티티 hydration | 8 / 15 |
| 14 | `users ⋈ role ⟕ provider WHERE email=?` (재실행) | 좋아요 판정용 User 조회 | 1 / 1 |
| 15 | `like_log WHERE user_id=? AND article_id IN (?)` | 좋아요 여부 | 4 / 1 |

> #10의 span 부모가 `vector-search-filtered`인데 시작 시각은 그 span 종료 직후다. `searchExecutor`가 `ContextExecutorService`로 감싼 virtual thread executor라 fire-and-forget 작업이 trace context를 물려받기 때문이며, 버그가 아니다. **크리티컬 패스에는 없다.**

---

## 3. 개선 여지

우선순위 순. 앞의 두 개가 직렬 구간 440ms의 대부분이다.

### A. 유의어 확장 150~165ms가 요청 맨 앞에 직렬로 붙어 있다 — 가장 큰 단일 낭비

두 트레이스에서 164 / 150ms로 재현된다. 노이즈가 아니다. 두 갈래로 볼 수 있다.

**A-1. 이 구간은 애초에 직렬일 이유가 없다.** ✅ **적용됨 (아래 "A-1 적용 결과" 참고)**

**A-1. 이 구간은 애초에 직렬일 이유가 없다.** 확장 결과는 BM25 쿼리 문자열을 만드는 데만 쓰이고, **Clova 임베딩 호출은 원본 키워드만 있으면 된다**. 지금은 컨트롤러(`ArticleSearchController:90`)에서 `expandSearchTerms`를 부른 뒤에야 `searchArticlesHybrid` → `vectorFuture`가 뜬다. 벡터 future를 유의어 확장보다 **먼저** 띄우면 150ms가 통째로 임베딩 대기 뒤에 숨는다. 트레이스 A 기준 856 → 약 700ms.

**A-2. 쿼리 자체도 느릴 이유가 없다.** `WHERE t1.term IN (?) OR t2.term IN (?)` — 조인된 **서로 다른 두 테이블 별칭에 걸린 OR**라 PostgreSQL이 `uk_term_term_type`(`term` 선두 컬럼) 인덱스를 못 쓰고 `term_synonym` 전체를 두 번 조인해 필터한다. `max_parallel_workers_per_gather=0`(`application-prod.properties:38`)이라 병렬 워커 보정도 없다. 두 갈래 `UNION ALL`(각각 인덱스 사용 가능)로 쪼개는 게 정석이다.

**A-3.** `term_synonym`은 작고 거의 안 변하는 테이블이다. 키워드별 Caffeine 캐시(`EXPANSION_CACHE_NAME`)는 있지만 **키워드마다 첫 요청은 항상 이 150ms를 낸다.** 유의어 맵 전체를 기동 시 메모리에 올리고 갱신 시 무효화하면 쿼리가 사라진다. (A-1과 달리 이건 캐시 미스 요청도 구제한다.)

### B. `bm25-supplement`가 본검색과 같은 값을 낸다 (169 / 167ms)

`WHERE @@@ parse(?) AND asi.id IN (?)`. **몇 건에만 점수를 매기는데 LIMIT 100 전체 검색과 시간이 같다.** ParadeDB가 BM25 쿼리를 먼저 평가하고 `id IN` 은 그 뒤에 거르므로, 대상 건수를 줄여도 비용이 안 준다. 두 트레이스 모두 그렇다.

- **본검색 LIMIT 100을 늘려 보충 자체를 없애는 쪽**이 유력하다. 정렬은 이미 끝난 상태라 LIMIT 확대는 거의 공짜인데, 보충은 전체 스캔 1회를 통째로 더 낸다. LIMIT 300 정도면 vector-only 항목 대부분이 본검색 결과에 이미 포함돼 `needBm25Ids`가 비고, 그러면 171ms 구간이 통째로 사라진다.
- 남는 경우를 위해 보충 경로는 유지하되, "몇 건이 실제로 보충 대상이 되는지"를 먼저 계측할 것. `needBm25Ids`가 대부분 비어 있다면 A/B 없이 바로 정리된다.

### C. 벡터 보충(105ms)과 BM25 보충(171ms)이 직렬이다 — 다만 이건 의도된 선택

`ArticleSearchService`의 주석이 명시한다: 병렬화하면 요청 1건이 Hikari 풀(5개)에서 커넥션을 하나 더 잡으므로, 둘 다 필요한 드문 케이스의 지연보다 풀 절약을 택했다. **B가 해결되면 이 항목은 자동으로 없어진다** — 보충이 한쪽만 남으므로. B를 먼저 하고 C는 건드리지 않는 게 맞다.

### D. Clova 임베딩 246 / 658ms — 벡터 경로의 지배 항목, 변동폭도 가장 크다

DB 캐시 조회는 1ms인데 미스 시 API가 246~658ms다. `SearchPrewarmScheduler`가 이미 있으니, 실제 커버리지(검색 키워드 중 프리워밍이 적중하는 비율)를 확인하는 게 다음 수순이다. 커버리지가 낮으면 A-1이 더 중요해진다 — 임베딩 대기가 길수록 그 뒤에 숨길 수 있는 시간도 늘어난다.

### E. 자잘한 것들 (합쳐도 ~15ms, 낮은 우선순위)

- **동일한 User 조회 2회** (#1, #14). 필터체인이 이미 조회한 것을 좋아요 판정에서 다시 읽는다. 요청 스코프로 넘기면 없앨 수 있다. 각 1ms라 지연보다는 풀 점유 횟수 측면의 이야기다.
- **최종 hydration이 `article.content`를 통째로 읽는다** (#13). 목록 렌더에 본문은 안 쓰인다. TOAST 참조가 걸리는 컬럼이라 결과 수가 늘면 비용이 선형으로 는다. 프로젝션 DTO로 전환할 여지.
- **`search_logs` 조회(#8, 3ms)가 벡터 크리티컬 패스 안에 있다.** 임베딩–search_log 연결용이라 검색 결과에는 영향이 없다. 비동기로 빼도 된다.
- **AI요약 chunk 워밍(#10, 94ms)** 은 크리티컬 패스 밖이지만, 하필 cross-scoring(#9)과 **동시에** 돌며 5개짜리 풀에서 커넥션을 다툰다. 동시성이 올라가면 여기가 먼저 아플 수 있다.
  > **후속 조치(2026-08-13): 제거함.** 이 워밍이 채우던 `chunkSearchResults` 평문 키를 읽는 곳은 `getTopChunksForSummary`/`…SingleTerm` 뿐인데 커밋 `319b742`(AI 요약 chunk 선정 전략을 하이브리드 상위 4개 글 기준으로 변경) 이후 프로덕션 호출부가 사라져, 아무도 읽지 않는 캐시를 매 검색마다 채우고 있었다. 지연은 그대로(비동기)이고 이득은 커넥션·DB CPU 절감. 현재 AI 요약(`AiSummaryService`)은 `findFirstAndBestChunksByArticleIds`를 쓰므로 영향 없음.

---

## 4. 요약

| 항목 | 절감 예상 | 난이도 | 비고 |
|------|----------|--------|------|
| ~~A-1 벡터 future를 유의어 확장 앞으로~~ | ~150ms | 낮음 | ✅ 적용 완료 |
| B 본검색 LIMIT 확대 → 보충 제거 | ~170ms | 중간 | 먼저 `needBm25Ids` 계측 |
| A-2/A-3 유의어 쿼리 UNION ALL / 전량 캐시 | ~150ms (A-1과 중복) | 중간 | A-1이 못 숨기는 경우 대비 |
| D 임베딩 프리워밍 커버리지 개선 | 미측정 | 중간 | 커버리지 확인 선행 |
| E 잡정리 | ~15ms | 낮음 | 지연보다 풀 점유 관점 |

A-1 + B만으로 856 → 약 530ms. 표본이 2건이므로, 착수 전에 `needBm25Ids` 건수와 임베딩 캐시 적중률부터 계측할 것.

---

## 5. A-1 적용 결과

`computeHybridCore`에서 `vectorFuture` 생성을 메서드 맨 앞(복잡도 분류·검색어 확장보다 위)으로 올리고,
`ArticleSearchController`가 확장을 미리 부르지 않고 `expandedTerms=null`로 넘기도록 바꿨다.
확장은 이제 코어 안에서 — Clova 호출이 이미 떠 있는 상태에서 — 실행된다.

변경 파일: `ArticleSearchService.computeHybridCore`, `ArticleSearchController`,
`ArticleSearchLoadTestController`(k6가 같은 순서를 재현하도록).

부수 효과 두 가지:
- **single-flight 패자는 확장을 아예 건너뛴다.** 기존엔 모든 요청이 컨트롤러에서 확장을 치른 뒤
  `getHybridCoreShared`에 들어가 곧바로 `join()` 했다. 이제 승자만 확장한다.
- **빈 BM25 쿼리 경로가 500 대신 200+빈결과가 된다.** 확장 예외가 컨트롤러 밖으로 나가지 않고
  `buildBM25SearchQuery`의 try/catch에 잡히기 때문이다.

고아 future(빈 BM25 쿼리로 early return할 때 이미 떠 있는 Clova 호출)는 취소하지 않는다 —
`cancel(true)`는 실행 중인 `supplyAsync` 태스크를 중단시키지 못하고, 무필터 경로면
`vectorSearchResults` 캐시를, 어느 경로든 `search_query_embedding`을 채워 다음 요청이 이득을 본다.

### 커넥션 풀에 대한 판단

요청당 동시 커넥션 피크는 2로 그대로다. 실제로는 보통 1인데, 확장 150ms 동안 벡터 태스크는
Clova HTTP 대기 중이라 커넥션을 0개 쥐고 있기 때문이다(`getEmbeddingWithCacheInfo`에 `@Transactional`이
없는 덕분). 겹치는 건 DB작업 ∥ HTTP대기라 자원 경합이 아니다.

**이 이득은 벡터 경로가 `getEmbeddingWithCacheInfo`를 쓰는 동안에만 성립한다.** `@Transactional`이 붙은
`getOrCreateEmbedding`으로 바꾸면 Clova 호출 내내 커넥션을 물어 확장 쿼리와 5개짜리 풀을 두고 경합한다.

### 회귀 가드

`ArticleSearchServiceTest.searchArticlesHybrid_expansionRunsAfterVectorFutureLaunched` —
확장 스텁이 "벡터가 이미 떠 있는가"를 래치로 확인한다. future를 다시 아래로 내리거나 컨트롤러
선호출을 되살리면 결정적으로 실패한다(실제로 되돌려 실패하는 것까지 확인함).

### 아직 측정 안 된 것

프로덕션 트레이스로 실제 단축폭을 확인해야 한다. 판정 기준:
1. `vector-search-filtered` 시작 오프셋이 ~191ms → `secured request` 시작 부근으로 떨어질 것
2. **유의어 SELECT 구간이 `http post`(Clova) 구간 안에 완전히 포함될 것** — 이게 병렬화의 증거다
3. 둘이 `secured request` 아래 형제이고 같은 trace id일 것 (`ContextExecutorService` 전파 유지 확인)
4. `hikaricp_connections_pending`이 오르지 않을 것

임베딩 **캐시 히트** 요청은 `http post` span이 없어 벡터 태스크가 ~90ms뿐이므로, 그 구간에서는
확장 150ms가 다시 크리티컬 패스가 되어 이득이 ~90ms에 그친다. 두 캐시 상태를 모두 표본으로 뽑을 것.

> `[검색]` 로그의 `총: {}ms`는 이제 확장 시간을 포함한다(`totalStartTime`이 `computeHybridCore` 진입
> 시점이므로). 과거 로그와 1:1 비교하지 말고 HTTP 루트 span으로 비교할 것.
