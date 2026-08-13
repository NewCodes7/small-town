package com.newcodes7.small_town.embedding.repository;

import com.newcodes7.small_town.embedding.entity.ArticleChunk;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * ArticleChunk 리포지토리
 *
 * 2단계 검색 지원:
 * - Stage 1: Binary HNSW (빠른 후보 필터링)
 * - Stage 2: halfvec Reranking (정밀 유사도 계산)
 *
 * <h2>쿼리 벡터 파라미터는 반드시 {@code AS MATERIALIZED} CTE로 감싼다</h2>
 *
 * <p>{@code :queryEmbedding}은 1024차원을 텍스트로 편 <b>11.8KB 문자열</b>이다
 * (VectorSearchService.formatVectorForPostgres). {@code CAST(... AS halfvec)}은 그 문자열에서
 * 숫자 1024개를 파싱하는 작업이라 <b>행당 약 97µs</b>가 든다 — 정작 목적인 거리 연산
 * {@code <#>}은 행당 2.5µs다.
 *
 * <p>PG 12부터 <b>한 번만 참조되는 CTE는 본문에 인라인</b>되고, pgjdbc가
 * {@code prepareThreshold=5}로 서버측 prepared statement를 쓰기 때문에 커넥션마다 5회 실행 후
 * generic plan으로 바뀌면 파라미터가 상수로 접히지 않는다. 두 조건이 겹치면 같은 문자열을
 * <b>행마다 다시 파싱</b>한다. 실측으로 본검색이 2.5ms → 50.0ms가 됐다.
 * {@code MATERIALIZED}는 인라인을 막아 파싱을 쿼리당 1회로 되돌린다
 * (docs/operations/QUERY_PARAM_REPARSE.md).
 *
 * <p><b>단 binary 캐스트는 다르다.</b> {@code ORDER BY embedding_binary <~> CAST(:queryBinary AS
 * bit(1024)) LIMIT}의 캐스트는 <b>HNSW 탐색키</b>라 CTE로 빼면 인덱스 스캔이 Seq Scan으로
 * 떨어진다. 이 형태는 반드시 인라인으로 둔다. halfvec {@code <#>}은 전부 윈도우 ORDER BY나
 * 조인 내부라 인덱스가 쓰이지 않으므로 끌어올려도 안전하다.
 */
@Repository
public interface ArticleChunkRepository extends JpaRepository<ArticleChunk, Long> {

    /**
     * 특정 Article의 모든 청크 조회
     */
    List<ArticleChunk> findByArticleIdOrderByChunkIndexAsc(Long articleId);

    /**
     * 특정 Article의 청크 삭제
     */
    void deleteByArticleId(Long articleId);

    /**
     * 여러 Article의 청크 벌크 삭제
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM ArticleChunk c WHERE c.article.id IN :articleIds")
    void deleteByArticleIdIn(@Param("articleIds") List<Long> articleIds);

    /**
     * 특정 Article의 청크 존재 여부 확인
     */
    boolean existsByArticleId(Long articleId);

    /**
     * 특정 Article의 청크 수
     */
    long countByArticleId(Long articleId);

    /**
     * 임베딩이 있는 청크가 있는 Article ID 목록
     */
    @Query("SELECT DISTINCT c.article.id FROM ArticleChunk c WHERE c.embeddingBinary IS NOT NULL")
    List<Long> findArticleIdsWithEmbedding();

    /**
     * Article ID 목록 (ID 내림차순, 전체)
     */
    @Query(value = "SELECT a.id FROM article a " +
           "WHERE a.deleted_at IS NULL " +
           "ORDER BY a.id DESC",
           nativeQuery = true)
    List<Long> findArticleIdsWithEmbeddingOrderByIdDesc();

    /**
     * Article ID 목록 (특정 ID 미만, ID 내림차순, 전체)
     */
    @Query(value = "SELECT a.id FROM article a " +
           "WHERE a.deleted_at IS NULL " +
           "AND a.id < :maxArticleId " +
           "ORDER BY a.id DESC",
           nativeQuery = true)
    List<Long> findArticleIdsWithEmbeddingLessThanOrderByIdDesc(
            @Param("maxArticleId") Long maxArticleId);

    /**
     * 특정 Corporation Article ID 목록 (ID 내림차순, 전체)
     */
    @Query(value = "SELECT a.id FROM article a " +
           "WHERE a.deleted_at IS NULL " +
           "AND a.corporation_id = :corporationId " +
           "ORDER BY a.id DESC",
           nativeQuery = true)
    List<Long> findArticleIdsWithEmbeddingByCorporationIdOrderByIdDesc(
            @Param("corporationId") Long corporationId);

    /**
     * 특정 Article ID들에 대한 벡터 유사도 계산 - 상위 K개 청크 평균 방식 (halfvec)
     * BM25로만 검색된 article들의 vector score를 계산하기 위해 사용
     *
     * <p>id 목록은 {@code IN (:ids)}가 아니라 <b>배열 파라미터 하나</b>로 받는다. Hibernate가 IN
     * 리스트를 {@code IN ($1..$n)}으로 펼치면 n마다 다른 쿼리가 되어 pg_stat_statements의 queryid가
     * 호출마다 흩어지고(실측 13회 호출 → queryid 12개) 플랜 캐시도 재사용되지 않는다. PG16+의 IN
     * 병합은 상수 리스트에만 적용돼 도움이 되지 않는다. 이 쿼리는 검색 1건당 DB 예산의 33%를
     * 쓰는 최대 비용 지점이라 단일 queryid로 관측되는 것이 특히 중요하다
     * (docs/operations/PGSS_SEARCH_COST.md 그 외 3번).
     *
     * @param queryEmbedding 검색 쿼리의 임베딩 벡터 (PostgreSQL 배열 포맷)
     * @param articleIds 유사도를 계산할 Article ID 목록 (PostgreSQL 배열 리터럴, 예: {@code {1,2,3}})
     * @param topK 평균 계산에 사용할 상위 청크 수
     * @return Article ID와 상위 K개 청크 평균 유사도를 담은 결과
     */
    @Query(value = """
            WITH query_vec AS MATERIALIZED (
                SELECT l2_normalize(CAST(:queryEmbedding AS halfvec)) AS vec
            )
            SELECT article_id, AVG(similarity) AS avg_similarity
            FROM (
                SELECT
                    cac.article_id,
                    -(ccv.embedding_normalized <#> q.vec) AS similarity,
                    ROW_NUMBER() OVER (
                        PARTITION BY cac.article_id
                        ORDER BY ccv.embedding_normalized <#> q.vec
                    ) AS rn
                FROM clova_article_chunk cac
                JOIN article a ON cac.article_id = a.id
                JOIN clova_chunk_vectors ccv ON ccv.id = cac.id
                CROSS JOIN query_vec q
                WHERE a.deleted_at IS NULL
                  AND cac.article_id = ANY(CAST(:articleIds AS bigint[]))
            ) ranked
            WHERE rn <= :topK
            GROUP BY article_id
            ORDER BY avg_similarity DESC
            """, nativeQuery = true)
    List<Object[]> computeSimilarityForArticleIds(
            @Param("queryEmbedding") String queryEmbedding,
            @Param("articleIds") String articleIds,
            @Param("topK") int topK
    );

    /**
     * cross-scoring 보충의 2단계(퍼널) 버전 — Stage 1은 binary, Stage 2만 halfvec.
     *
     * <p>단일 쿼리 버전(computeSimilarityForArticleIds)은 대상 아티클 100개의 <b>전 청크(평균
     * 840개)</b>를 clova_chunk_vectors.embedding_normalized(halfvec 2,048B, EXTERNAL 저장)에서
     * 꺼낸다. 실측 10,272블록(84MB)/93.3ms이고 블록 접근의 64%가 TOAST 인덱스 조회다 — 비용의
     * 지배 변수는 FLOPs가 아니라 <b>꺼내는 벡터의 개수</b>다.
     *
     * <p>embedding_binary(bit(1024) = 128B)는 clova_article_chunk에 <b>인라인</b> 저장되므로
     * TOAST 간접 참조가 통째로 없다. 그래서 본검색과 같은 binary → halfvec 퍼널을 이 경로에도
     * 적용한다 (docs/operations/PGSS_SEARCH_COST.md 항목 B').
     *
     * <ul>
     *   <li><b>Stage 1은 아티클을 고르고 청크를 고르지 않는다.</b> Stage 2가 생존 아티클의 전
     *       청크를 다시 읽으므로 반환 <b>값</b>은 단일 쿼리 버전과 완전히 같다 — 달라지는 건
     *       "누가 살아남았나"뿐이다. 대표 청크로 미리 하나만 남기는 방식과 다른 지점.</li>
     *   <li><b>집계 모양을 Stage 2와 맞춘다.</b> 컷은 Stage 2 값을 예측해야 하므로 최고 청크
     *       하나(MIN 해밍)가 아니라 상위 topK 평균이어야 한다.</li>
     *   <li><b>해밍 → 코사인 환산은 청크 단위로 먼저 한다.</b> cos(π·h/d)가 비선형이라
     *       cos(avg(h)) ≠ avg(cos(h)). 청크 선별 순서는 해밍 오름차순 = 추정 유사도 내림차순이라
     *       ROW_NUMBER의 ORDER BY는 해밍 그대로 쓴다.</li>
     *   <li><b>deleted_at 필터를 Stage 1로 옮겼다.</b> Stage 2는 Stage 1 생존분만 보므로 등가이고
     *       halfvec 쪽 article 조인이 사라져 더 싸다.</li>
     *   <li>stage1은 MATERIALIZED — 플래너가 LIMIT CTE를 인라인하려 드는 것을 막아 계획을
     *       결정적으로 만든다.</li>
     *   <li><b>쿼리 벡터 두 개를 {@code q} CTE 하나로 묶었다.</b> 이전에는 Stage 1이
     *       {@code CAST(:queryBinary AS bit(1024))}를 타깃리스트와 윈도우 ORDER BY에 각각 적어
     *       <b>행당 2회</b> 평가했고, Stage 2의 halfvec도 CTE가 인라인돼 행마다 재파싱됐다.
     *       generic plan에서 19.2ms → <b>3.2ms</b> (클래스 javadoc 참고). Stage 1의 binary 캐스트는
     *       {@code article_id = ANY(...)} 인덱스 스캔 위의 일반 표현식이지 HNSW 탐색키가 아니라
     *       끌어올려도 안전하다.</li>
     *   <li>해밍을 안쪽 서브쿼리에서 1회만 계산하고 {@code cos()} 환산을 집계 시점으로 옮겼다 —
     *       {@code avg(cos(h))} 순서는 그대로다(위 세 번째 항목의 제약 유지).</li>
     * </ul>
     *
     * @param queryEmbedding 검색 쿼리의 임베딩 벡터 (PostgreSQL 배열 포맷)
     * @param queryBinary 검색 쿼리의 binary vector (bit string)
     * @param articleIds 유사도를 계산할 Article ID 목록 (PostgreSQL 배열 리터럴)
     * @param topK 평균 계산에 사용할 상위 청크 수
     * @param stage1Floor Stage 1 추정 유사도 하한. 임계값에서 여유를 뺀 값 — 이진 양자화 추정치의
     *                    분산(σ ≈ 15비트) 때문에 임계값을 그대로 쓰면 통과했을 아티클을 떨어뜨린다
     * @param stage2Limit Stage 2로 넘길 아티클 수 상한. 실제 비용을 결정하는 값
     * @return Article ID와 상위 K개 청크 평균 유사도 (단일 쿼리 버전과 같은 형식)
     */
    @Query(value = """
            WITH q AS MATERIALIZED (
                SELECT l2_normalize(CAST(:queryEmbedding AS halfvec)) AS vec,
                       CAST(:queryBinary AS bit(1024))                AS bvec
            ),
            stage1 AS MATERIALIZED (
                SELECT article_id
                FROM (
                    SELECT
                        article_id,
                        hamming,
                        ROW_NUMBER() OVER (
                            PARTITION BY article_id
                            ORDER BY hamming
                        ) AS rn
                    FROM (
                        SELECT
                            cac.article_id,
                            cac.embedding_binary <~> q.bvec AS hamming
                        FROM clova_article_chunk cac
                        JOIN article a ON cac.article_id = a.id
                        CROSS JOIN q
                        WHERE a.deleted_at IS NULL
                          AND cac.embedding_binary IS NOT NULL
                          AND cac.article_id = ANY(CAST(:articleIds AS bigint[]))
                    ) h
                ) estimated
                WHERE rn <= :topK
                GROUP BY article_id
                HAVING AVG(cos(pi() * hamming / 1024.0)) >= :stage1Floor
                ORDER BY AVG(cos(pi() * hamming / 1024.0)) DESC
                LIMIT :stage2Limit
            )
            SELECT article_id, AVG(similarity) AS avg_similarity
            FROM (
                SELECT
                    cac.article_id,
                    -(ccv.embedding_normalized <#> q.vec) AS similarity,
                    ROW_NUMBER() OVER (
                        PARTITION BY cac.article_id
                        ORDER BY ccv.embedding_normalized <#> q.vec
                    ) AS rn
                FROM clova_article_chunk cac
                JOIN clova_chunk_vectors ccv ON ccv.id = cac.id
                CROSS JOIN q
                WHERE cac.article_id = ANY(ARRAY(SELECT article_id FROM stage1))
            ) ranked
            WHERE rn <= :topK
            GROUP BY article_id
            ORDER BY avg_similarity DESC
            """, nativeQuery = true)
    List<Object[]> computeSimilarityForArticleIdsTwoStage(
            @Param("queryEmbedding") String queryEmbedding,
            @Param("queryBinary") String queryBinary,
            @Param("articleIds") String articleIds,
            @Param("topK") int topK,
            @Param("stage1Floor") double stage1Floor,
            @Param("stage2Limit") int stage2Limit
    );

    /**
     * 임베딩이 없는 청크 조회
     */
    @Query("SELECT c FROM ArticleChunk c WHERE c.embeddingBinary IS NULL")
    List<ArticleChunk> findChunksWithoutEmbedding(Pageable pageable);

    /**
     * 전체 청크 수
     */
    @Query("SELECT COUNT(c) FROM ArticleChunk c")
    long countAllChunks();

    /**
     * 임베딩이 있는 청크 수
     */
    @Query("SELECT COUNT(c) FROM ArticleChunk c WHERE c.embeddingBinary IS NOT NULL")
    long countChunksWithEmbedding();

    /**
     * 임베딩이 있는 Article 수 (중복 제외)
     */
    @Query("SELECT COUNT(DISTINCT c.article.id) FROM ArticleChunk c WHERE c.embeddingBinary IS NOT NULL")
    long countArticlesWithEmbedding();

    // ==================== 2단계 검색 (Binary HNSW → halfvec Reranking) ====================

    /**
     * 2단계 통합 검색: Binary HNSW 후보 필터링 → halfvec Reranking
     *
     * CTE로 쿼리 벡터 정규화를 한 번만 수행하고,
     * candidates CTE에서 embedding_normalized를 미리 읽어 PK 재조회를 방지합니다.
     *
     * <p>threshold/limit로 잘려나가는 후보의 점수도 함께 반환한다 — 그 아티클들은 곧이어
     * cross-scoring(computeSimilarityForArticleIds)이 다시 계산하려는 대상과 겹치므로,
     * 여기서 넘겨주면 DB 왕복을 통째로 없앨 수 있다(docs/operations/PGSS_SEARCH_COST.md 항목 A).
     * Stage 1 후보 집합은 이미 메모리에 있으므로 추가 비용은 반환 행 수뿐이다.
     *
     * <p><b>candidate_similarity는 후보 청크가 topK개 이상인 아티클에만 채운다(그 외 NULL).</b>
     * AVG의 분모가 후보에 걸린 청크 수라, 후보 청크가 topK 미만이면 상위 1~2개만 평균 내
     * cross-scoring(전체 청크 중 상위 topK 평균)보다 <b>높게</b> 나온다 — 재활용하면 약한 매칭이
     * 부풀어 오른다. topK개 이상이면 같은 깊이의 평균이고 후보는 전체 청크의 부분집합이므로
     * candidate_similarity ≤ cross-scoring 값이 항상 성립한다(과대평가 불가).
     *
     * @param queryEmbedding 검색 쿼리의 임베딩 벡터 (PostgreSQL 배열 포맷)
     * @param queryBinary 검색 쿼리의 binary vector (bit string)
     * @param candidateLimit Stage 1 후보 수 (예: 500)
     * @param topK 평균 계산에 사용할 상위 청크 수
     * @param threshold 최소 유사도 임계값
     * @param limit 최종 결과 수 (is_main=true로 표시되는 행 수)
     * @return (article_id, avg_similarity, candidate_similarity, is_main) 행 목록.
     *         avg_similarity는 threshold를 통과한 청크만 평균 낸 값(통과 청크 없으면 NULL),
     *         candidate_similarity는 후보 청크가 topK개 이상일 때만 채워지는 상위 topK 평균
     *         (threshold 미적용, 미달 아티클은 NULL),
     *         is_main은 기존 semantics(threshold 통과 + limit 이내) 해당 여부다.
     */
    @Query(value = """
            WITH query_vec AS MATERIALIZED (
                SELECT l2_normalize(CAST(:queryEmbedding AS halfvec)) AS vec
            ),
            candidates AS (
                SELECT
                    cac.article_id,
                    ccv.embedding_normalized
                FROM clova_article_chunk cac
                JOIN article a ON cac.article_id = a.id
                JOIN clova_chunk_vectors ccv ON ccv.id = cac.id
                WHERE a.deleted_at IS NULL
                  AND cac.embedding_binary IS NOT NULL
                ORDER BY cac.embedding_binary <~> CAST(:queryBinary AS bit(1024))
                LIMIT :candidateLimit
            ),
            agg AS (
                SELECT
                    article_id,
                    AVG(similarity) FILTER (WHERE similarity >= :threshold) AS avg_similarity,
                    CASE WHEN COUNT(*) >= :topK THEN AVG(similarity) END AS candidate_similarity
                FROM (
                    SELECT
                        c.article_id,
                        -(c.embedding_normalized <#> q.vec) AS similarity,
                        ROW_NUMBER() OVER (
                            PARTITION BY c.article_id
                            ORDER BY c.embedding_normalized <#> q.vec
                        ) AS rn
                    FROM candidates c, query_vec q
                ) ranked
                WHERE rn <= :topK
                GROUP BY article_id
            )
            SELECT
                article_id,
                avg_similarity,
                candidate_similarity,
                (avg_similarity IS NOT NULL
                 AND ROW_NUMBER() OVER (ORDER BY avg_similarity DESC NULLS LAST) <= :limit) AS is_main
            FROM agg
            ORDER BY avg_similarity DESC NULLS LAST
            """, nativeQuery = true)
    List<Object[]> findArticlesByTwoStageSearch(
            @Param("queryEmbedding") String queryEmbedding,
            @Param("queryBinary") String queryBinary,
            @Param("candidateLimit") int candidateLimit,
            @Param("topK") int topK,
            @Param("threshold") double threshold,
            @Param("limit") int limit
    );

    /**
     * 2단계 통합 검색 (해외/국내 필터 적용): Binary HNSW 후보 필터링 → halfvec Reranking
     *
     * @param domesticTypes 허용할 is_domestic 값 목록 (1=국내, 0=해외)
     */
    @Query(value = """
            WITH query_vec AS MATERIALIZED (
                SELECT l2_normalize(CAST(:queryEmbedding AS halfvec)) AS vec
            ),
            candidates AS (
                SELECT
                    cac.article_id,
                    ccv.embedding_normalized
                FROM clova_article_chunk cac
                JOIN article a ON cac.article_id = a.id
                JOIN corporation corp ON a.corporation_id = corp.id
                JOIN clova_chunk_vectors ccv ON ccv.id = cac.id
                WHERE a.deleted_at IS NULL
                  AND cac.embedding_binary IS NOT NULL
                  AND corp.is_domestic IN (:domesticTypes)
                ORDER BY cac.embedding_binary <~> CAST(:queryBinary AS bit(1024))
                LIMIT :candidateLimit
            ),
            agg AS (
                SELECT
                    article_id,
                    AVG(similarity) FILTER (WHERE similarity >= :threshold) AS avg_similarity,
                    CASE WHEN COUNT(*) >= :topK THEN AVG(similarity) END AS candidate_similarity
                FROM (
                    SELECT
                        c.article_id,
                        -(c.embedding_normalized <#> q.vec) AS similarity,
                        ROW_NUMBER() OVER (
                            PARTITION BY c.article_id
                            ORDER BY c.embedding_normalized <#> q.vec
                        ) AS rn
                    FROM candidates c, query_vec q
                ) ranked
                WHERE rn <= :topK
                GROUP BY article_id
            )
            SELECT
                article_id,
                avg_similarity,
                candidate_similarity,
                (avg_similarity IS NOT NULL
                 AND ROW_NUMBER() OVER (ORDER BY avg_similarity DESC NULLS LAST) <= :limit) AS is_main
            FROM agg
            ORDER BY avg_similarity DESC NULLS LAST
            """, nativeQuery = true)
    List<Object[]> findArticlesByTwoStageSearchWithDomesticFilter(
            @Param("queryEmbedding") String queryEmbedding,
            @Param("queryBinary") String queryBinary,
            @Param("candidateLimit") int candidateLimit,
            @Param("topK") int topK,
            @Param("threshold") double threshold,
            @Param("limit") int limit,
            @Param("domesticTypes") List<Integer> domesticTypes
    );

    /**
     * 2단계 통합 검색 (category 필터 적용): Binary HNSW 후보 필터링 → halfvec Reranking
     *
     * @param categories 허용할 카테고리 이름 목록
     */
    @Query(value = """
            WITH query_vec AS MATERIALIZED (
                SELECT l2_normalize(CAST(:queryEmbedding AS halfvec)) AS vec
            ),
            candidates AS (
                SELECT
                    cac.article_id,
                    ccv.embedding_normalized
                FROM clova_article_chunk cac
                JOIN article a ON cac.article_id = a.id
                JOIN category cat ON a.category_id = cat.id
                JOIN clova_chunk_vectors ccv ON ccv.id = cac.id
                WHERE a.deleted_at IS NULL
                  AND cac.embedding_binary IS NOT NULL
                  AND cat.name IN (:categories)
                ORDER BY cac.embedding_binary <~> CAST(:queryBinary AS bit(1024))
                LIMIT :candidateLimit
            ),
            agg AS (
                SELECT
                    article_id,
                    AVG(similarity) FILTER (WHERE similarity >= :threshold) AS avg_similarity,
                    CASE WHEN COUNT(*) >= :topK THEN AVG(similarity) END AS candidate_similarity
                FROM (
                    SELECT
                        c.article_id,
                        -(c.embedding_normalized <#> q.vec) AS similarity,
                        ROW_NUMBER() OVER (
                            PARTITION BY c.article_id
                            ORDER BY c.embedding_normalized <#> q.vec
                        ) AS rn
                    FROM candidates c, query_vec q
                ) ranked
                WHERE rn <= :topK
                GROUP BY article_id
            )
            SELECT
                article_id,
                avg_similarity,
                candidate_similarity,
                (avg_similarity IS NOT NULL
                 AND ROW_NUMBER() OVER (ORDER BY avg_similarity DESC NULLS LAST) <= :limit) AS is_main
            FROM agg
            ORDER BY avg_similarity DESC NULLS LAST
            """, nativeQuery = true)
    List<Object[]> findArticlesByTwoStageSearchWithCategoryFilter(
            @Param("queryEmbedding") String queryEmbedding,
            @Param("queryBinary") String queryBinary,
            @Param("candidateLimit") int candidateLimit,
            @Param("topK") int topK,
            @Param("threshold") double threshold,
            @Param("limit") int limit,
            @Param("categories") List<String> categories
    );

    /**
     * 2단계 통합 검색 (해외/국내 + category 필터 모두 적용): Binary HNSW 후보 필터링 → halfvec Reranking
     */
    @Query(value = """
            WITH query_vec AS MATERIALIZED (
                SELECT l2_normalize(CAST(:queryEmbedding AS halfvec)) AS vec
            ),
            candidates AS (
                SELECT
                    cac.article_id,
                    ccv.embedding_normalized
                FROM clova_article_chunk cac
                JOIN article a ON cac.article_id = a.id
                JOIN corporation corp ON a.corporation_id = corp.id
                JOIN category cat ON a.category_id = cat.id
                JOIN clova_chunk_vectors ccv ON ccv.id = cac.id
                WHERE a.deleted_at IS NULL
                  AND cac.embedding_binary IS NOT NULL
                  AND corp.is_domestic IN (:domesticTypes)
                  AND cat.name IN (:categories)
                ORDER BY cac.embedding_binary <~> CAST(:queryBinary AS bit(1024))
                LIMIT :candidateLimit
            ),
            agg AS (
                SELECT
                    article_id,
                    AVG(similarity) FILTER (WHERE similarity >= :threshold) AS avg_similarity,
                    CASE WHEN COUNT(*) >= :topK THEN AVG(similarity) END AS candidate_similarity
                FROM (
                    SELECT
                        c.article_id,
                        -(c.embedding_normalized <#> q.vec) AS similarity,
                        ROW_NUMBER() OVER (
                            PARTITION BY c.article_id
                            ORDER BY c.embedding_normalized <#> q.vec
                        ) AS rn
                    FROM candidates c, query_vec q
                ) ranked
                WHERE rn <= :topK
                GROUP BY article_id
            )
            SELECT
                article_id,
                avg_similarity,
                candidate_similarity,
                (avg_similarity IS NOT NULL
                 AND ROW_NUMBER() OVER (ORDER BY avg_similarity DESC NULLS LAST) <= :limit) AS is_main
            FROM agg
            ORDER BY avg_similarity DESC NULLS LAST
            """, nativeQuery = true)
    List<Object[]> findArticlesByTwoStageSearchWithBothFilters(
            @Param("queryEmbedding") String queryEmbedding,
            @Param("queryBinary") String queryBinary,
            @Param("candidateLimit") int candidateLimit,
            @Param("topK") int topK,
            @Param("threshold") double threshold,
            @Param("limit") int limit,
            @Param("domesticTypes") List<Integer> domesticTypes,
            @Param("categories") List<String> categories
    );

    /**
     * RAG용 2단계 통합 검색 (기업 필터 적용): Binary HNSW 후보 필터링 → halfvec Reranking
     *
     * @param corporationIds 허용할 corporation_id 목록
     */
    @Query(value = """
            WITH query_vec AS MATERIALIZED (
                SELECT l2_normalize(CAST(:queryEmbedding AS halfvec)) AS vec
            ),
            candidates AS (
                SELECT
                    cac.article_id,
                    ccv.embedding_normalized
                FROM clova_article_chunk cac
                JOIN article a ON cac.article_id = a.id
                JOIN clova_chunk_vectors ccv ON ccv.id = cac.id
                WHERE a.deleted_at IS NULL
                  AND cac.embedding_binary IS NOT NULL
                  AND a.corporation_id IN (:corporationIds)
                ORDER BY cac.embedding_binary <~> CAST(:queryBinary AS bit(1024))
                LIMIT :candidateLimit
            ),
            agg AS (
                SELECT
                    article_id,
                    AVG(similarity) FILTER (WHERE similarity >= :threshold) AS avg_similarity,
                    CASE WHEN COUNT(*) >= :topK THEN AVG(similarity) END AS candidate_similarity
                FROM (
                    SELECT
                        c.article_id,
                        -(c.embedding_normalized <#> q.vec) AS similarity,
                        ROW_NUMBER() OVER (
                            PARTITION BY c.article_id
                            ORDER BY c.embedding_normalized <#> q.vec
                        ) AS rn
                    FROM candidates c, query_vec q
                ) ranked
                WHERE rn <= :topK
                GROUP BY article_id
            )
            SELECT
                article_id,
                avg_similarity,
                candidate_similarity,
                (avg_similarity IS NOT NULL
                 AND ROW_NUMBER() OVER (ORDER BY avg_similarity DESC NULLS LAST) <= :limit) AS is_main
            FROM agg
            ORDER BY avg_similarity DESC NULLS LAST
            """, nativeQuery = true)
    List<Object[]> findArticlesByTwoStageSearchWithCorporationFilter(
            @Param("queryEmbedding") String queryEmbedding,
            @Param("queryBinary") String queryBinary,
            @Param("candidateLimit") int candidateLimit,
            @Param("topK") int topK,
            @Param("threshold") double threshold,
            @Param("limit") int limit,
            @Param("corporationIds") List<Long> corporationIds
    );

    /**
     * AI 요약용: 지정 아티클 목록에서 아티클별 첫 청크 + 최고 유사도 청크 반환
     * UNION으로 중복 chunk_id 제거 (첫 청크 = 최고 유사도 청크인 경우 1개만 반환)
     */
    @Query(value = """
            WITH query_vec AS MATERIALIZED (
                SELECT l2_normalize(CAST(:queryEmbedding AS halfvec)) AS vec
            ),
            all_chunks AS (
                SELECT
                    cac.article_id,
                    cac.id            AS chunk_id,
                    cc.content,
                    cac.chunk_index,
                    -(ccv.embedding_normalized <#> q.vec) AS similarity,
                    a.title           AS article_title,
                    a.link            AS article_url,
                    corp.logo_s3_url,
                    corp.logo_filename,
                    corp.name         AS corp_name,
                    a.thumbnail_image
                FROM clova_article_chunk cac
                JOIN article a          ON a.id    = cac.article_id
                JOIN corporation corp   ON corp.id = a.corporation_id
                JOIN clova_chunk_contents cc  ON cc.id  = cac.id
                JOIN clova_chunk_vectors  ccv ON ccv.id = cac.id
                CROSS JOIN query_vec q
                WHERE cac.article_id IN (:articleIds)
                  AND ccv.embedding_normalized IS NOT NULL
            ),
            first_chunk_ids AS (
                SELECT DISTINCT ON (article_id) chunk_id
                FROM all_chunks
                ORDER BY article_id, chunk_index ASC
            ),
            best_chunk_ids AS (
                SELECT DISTINCT ON (article_id) chunk_id
                FROM all_chunks
                ORDER BY article_id, similarity DESC
            ),
            selected_ids AS (
                SELECT chunk_id FROM first_chunk_ids
                UNION
                SELECT chunk_id FROM best_chunk_ids
            )
            SELECT ac.article_id, ac.article_title, ac.article_url, ac.content,
                   ac.logo_s3_url, ac.logo_filename, ac.corp_name, ac.thumbnail_image
            FROM all_chunks ac
            WHERE ac.chunk_id IN (SELECT chunk_id FROM selected_ids)
            ORDER BY ac.article_id, ac.chunk_index
            """, nativeQuery = true)
    List<Object[]> findFirstAndBestChunksByArticleIds(
            @Param("queryEmbedding") String queryEmbedding,
            @Param("articleIds") List<Long> articleIds
    );

    /**
     * RAG용: 지정 아티클 목록에서 아티클별 첫 청크 + 유사도 상위 K개 청크 반환
     * findFirstAndBestChunksByArticleIds의 확장 — 최고 유사도 1개 대신 상위 :chunksPerArticle개
     * UNION으로 중복 chunk_id 제거 (첫 청크가 상위 K에 포함되면 1개만 반환)
     */
    @Query(value = """
            WITH query_vec AS MATERIALIZED (
                SELECT l2_normalize(CAST(:queryEmbedding AS halfvec)) AS vec
            ),
            all_chunks AS (
                SELECT
                    cac.article_id,
                    cac.id            AS chunk_id,
                    cc.content,
                    cac.chunk_index,
                    -(ccv.embedding_normalized <#> q.vec) AS similarity,
                    a.title           AS article_title,
                    a.link            AS article_url,
                    corp.logo_s3_url,
                    corp.logo_filename,
                    corp.name         AS corp_name,
                    a.thumbnail_image
                FROM clova_article_chunk cac
                JOIN article a          ON a.id    = cac.article_id
                JOIN corporation corp   ON corp.id = a.corporation_id
                JOIN clova_chunk_contents cc  ON cc.id  = cac.id
                JOIN clova_chunk_vectors  ccv ON ccv.id = cac.id
                CROSS JOIN query_vec q
                WHERE cac.article_id IN (:articleIds)
                  AND ccv.embedding_normalized IS NOT NULL
            ),
            first_chunk_ids AS (
                SELECT DISTINCT ON (article_id) chunk_id
                FROM all_chunks
                ORDER BY article_id, chunk_index ASC
            ),
            top_chunk_ids AS (
                SELECT chunk_id
                FROM (
                    SELECT chunk_id,
                           ROW_NUMBER() OVER (
                               PARTITION BY article_id
                               ORDER BY similarity DESC
                           ) AS rn
                    FROM all_chunks
                ) ranked
                WHERE rn <= :chunksPerArticle
            ),
            selected_ids AS (
                SELECT chunk_id FROM first_chunk_ids
                UNION
                SELECT chunk_id FROM top_chunk_ids
            )
            SELECT ac.article_id, ac.article_title, ac.article_url, ac.content,
                   ac.logo_s3_url, ac.logo_filename, ac.corp_name, ac.thumbnail_image
            FROM all_chunks ac
            WHERE ac.chunk_id IN (SELECT chunk_id FROM selected_ids)
            ORDER BY ac.article_id, ac.chunk_index
            """, nativeQuery = true)
    List<Object[]> findFirstAndTopChunksByArticleIds(
            @Param("queryEmbedding") String queryEmbedding,
            @Param("articleIds") List<Long> articleIds,
            @Param("chunksPerArticle") int chunksPerArticle
    );

    /**
     * Binary embedding이 없는 청크 수
     */
    @Query("SELECT COUNT(c) FROM ArticleChunk c WHERE c.embeddingBinary IS NULL")
    long countChunksWithoutBinaryEmbedding();

    /**
     * Binary embedding이 있는 청크 수
     */
    @Query("SELECT COUNT(c) FROM ArticleChunk c WHERE c.embeddingBinary IS NOT NULL")
    long countChunksWithBinaryEmbedding();

    // ==================== 대표 Chunk 관련 ====================

    /**
     * 특정 Article의 대표 Chunk 조회
     */
    @Query("SELECT c FROM ArticleChunk c WHERE c.article.id = :articleId AND c.isRepresentative = true")
    ArticleChunk findRepresentativeByArticleId(@Param("articleId") Long articleId);

    /**
     * 대표 Chunk가 있는 Article ID 목록
     */
    @Query("SELECT DISTINCT c.article.id FROM ArticleChunk c WHERE c.isRepresentative = true")
    List<Long> findArticleIdsWithRepresentativeChunk();

    /**
     * 대표 Chunk가 0번인 Article ID 목록
     */
    @Query("SELECT DISTINCT c.article.id FROM ArticleChunk c WHERE c.isRepresentative = true AND c.chunkIndex = 0")
    List<Long> findArticleIdsWithRepresentativeChunkIndexZero();

    /**
     * 대표 Chunk가 없는 Article ID 목록 (청크는 있지만 대표가 선정되지 않은 것)
     */
    @Query(value = """
            SELECT DISTINCT cac.article_id
            FROM clova_article_chunk cac
            WHERE cac.embedding_binary IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1 FROM clova_article_chunk cac2
                  WHERE cac2.article_id = cac.article_id
                    AND cac2.is_representative = true
              )
            """, nativeQuery = true)
    List<Long> findArticleIdsWithoutRepresentativeChunk();

    /**
     * 대표 Chunk 기반 관련 글 검색 (2단계: Binary HNSW → halfvec Reranking)
     *
     * Stage 1: embedding_binary <~> 로 Hamming 거리 기반 후보 추출 (HNSW 인덱스 활용)
     * Stage 2: embedding_normalized <#> 로 cosine 유사도 정밀 계산
     *
     * @param articleId 현재 Article ID (제외)
     * @param queryEmbedding 현재 Article 대표 chunk의 L2-정규화된 halfvec 임베딩
     * @param queryBinary 현재 Article 대표 chunk의 binary 임베딩 (bit string)
     * @param candidateLimit Stage 1 후보 수
     * @param threshold 최소 유사도 임계값
     * @param limit 최종 결과 수
     * @return Article ID와 유사도
     */
    @Query(value = """
            WITH query_vec AS MATERIALIZED (
                SELECT CAST(:queryEmbedding AS halfvec) AS vec
            ),
            stage1 AS (
                SELECT cac.article_id
                FROM clova_article_chunk cac
                JOIN article a ON cac.article_id = a.id
                WHERE a.deleted_at IS NULL
                  AND cac.is_representative = true
                  AND cac.article_id != :articleId
                  AND cac.embedding_binary IS NOT NULL
                ORDER BY cac.embedding_binary <~> CAST(:queryBinary AS bit(1024))
                LIMIT :candidateLimit
            ),
            all_chunks AS (
                SELECT cac.article_id,
                       -(ccv.embedding_normalized <#> q.vec) AS similarity,
                       ROW_NUMBER() OVER (
                           PARTITION BY cac.article_id
                           ORDER BY ccv.embedding_normalized <#> q.vec
                       ) AS rn
                FROM clova_article_chunk cac
                JOIN clova_chunk_vectors ccv ON ccv.id = cac.id
                CROSS JOIN query_vec q
                WHERE cac.article_id = ANY(ARRAY(SELECT article_id FROM stage1))
            )
            SELECT article_id, AVG(similarity) AS similarity
            FROM all_chunks
            WHERE rn <= :topK
              AND similarity >= :threshold
            GROUP BY article_id
            ORDER BY similarity DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findRelatedArticlesByTwoStageSearch(
            @Param("articleId") Long articleId,
            @Param("queryEmbedding") String queryEmbedding,
            @Param("queryBinary") String queryBinary,
            @Param("candidateLimit") int candidateLimit,
            @Param("topK") int topK,
            @Param("threshold") double threshold,
            @Param("limit") int limit
    );

    /**
     * 대표 Chunk 기반 관련 글 검색 (halfvec 직접 비교, binary 임베딩 없을 때 fallback)
     *
     * @param articleId 현재 Article ID (제외)
     * @param queryEmbedding 현재 Article 대표 chunk의 embedding
     * @param limit 결과 수
     * @return Article ID와 유사도
     */
    @Query(value = """
            WITH query_vec AS MATERIALIZED (
                SELECT CAST(:queryEmbedding AS halfvec) AS vec
            )
            SELECT article_id, AVG(similarity) AS similarity
            FROM (
                SELECT cac.article_id,
                       -(ccv.embedding_normalized <#> q.vec) AS similarity,
                       ROW_NUMBER() OVER (
                           PARTITION BY cac.article_id
                           ORDER BY ccv.embedding_normalized <#> q.vec
                       ) AS rn
                FROM clova_article_chunk cac
                JOIN clova_chunk_vectors ccv ON ccv.id = cac.id
                CROSS JOIN query_vec q
                WHERE cac.article_id != :articleId
                  AND cac.article_id IN (
                      SELECT id FROM article WHERE deleted_at IS NULL
                  )
            ) ranked
            WHERE rn <= :topK
            GROUP BY article_id
            ORDER BY similarity DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findRelatedArticlesByRepresentativeChunk(
            @Param("articleId") Long articleId,
            @Param("queryEmbedding") String queryEmbedding,
            @Param("topK") int topK,
            @Param("limit") int limit
    );

    /**
     * 대표 Chunk 플래그 초기화 (특정 Article)
     */
    @Query("UPDATE ArticleChunk c SET c.isRepresentative = false WHERE c.article.id = :articleId")
    @org.springframework.data.jpa.repository.Modifying
    void resetRepresentativeFlag(@Param("articleId") Long articleId);

    /**
     * 대표 Chunk 설정
     */
    @Query("UPDATE ArticleChunk c SET c.isRepresentative = true WHERE c.id = :chunkId")
    @org.springframework.data.jpa.repository.Modifying
    void setRepresentativeFlag(@Param("chunkId") Long chunkId);

    /**
     * 대표 Chunk 수 조회
     */
    @Query("SELECT COUNT(c) FROM ArticleChunk c WHERE c.isRepresentative = true")
    long countRepresentativeChunks();
}
