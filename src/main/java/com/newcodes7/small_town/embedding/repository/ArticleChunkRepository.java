package com.newcodes7.small_town.embedding.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.newcodes7.small_town.embedding.entity.ArticleChunk;

/**
 * ArticleChunk 리포지토리
 *
 * 2단계 검색 지원:
 * - Stage 1: Binary HNSW (빠른 후보 필터링)
 * - Stage 2: halfvec Reranking (정밀 유사도 계산)
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
     * @param queryEmbedding 검색 쿼리의 임베딩 벡터 (PostgreSQL 배열 포맷)
     * @param articleIds 유사도를 계산할 Article ID 목록
     * @param topK 평균 계산에 사용할 상위 청크 수
     * @return Article ID와 상위 K개 청크 평균 유사도를 담은 결과
     */
    @Query(value = "SELECT article_id, AVG(similarity) as avg_similarity " +
           "FROM (" +
           "  SELECT " +
           "    cac.article_id, " +
           "    -(ccv.embedding_normalized <#> l2_normalize(CAST(:queryEmbedding AS halfvec))) as similarity, " +
           "    ROW_NUMBER() OVER (PARTITION BY cac.article_id ORDER BY ccv.embedding_normalized <#> l2_normalize(CAST(:queryEmbedding AS halfvec))) as rn " +
           "  FROM clova_article_chunk cac " +
           "  JOIN article a ON cac.article_id = a.id " +
           "  JOIN clova_chunk_vectors ccv ON ccv.id = cac.id " +
           "  WHERE a.deleted_at IS NULL " +
           "  AND cac.article_id IN (:articleIds) " +
           ") ranked " +
           "WHERE rn <= :topK " +
           "GROUP BY article_id " +
           "ORDER BY avg_similarity DESC",
           nativeQuery = true)
    List<Object[]> computeSimilarityForArticleIds(
            @Param("queryEmbedding") String queryEmbedding,
            @Param("articleIds") List<Long> articleIds,
            @Param("topK") int topK
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
     * @param queryEmbedding 검색 쿼리의 임베딩 벡터 (PostgreSQL 배열 포맷)
     * @param queryBinary 검색 쿼리의 binary vector (bit string)
     * @param candidateLimit Stage 1 후보 수 (예: 500)
     * @param topK 평균 계산에 사용할 상위 청크 수
     * @param threshold 최소 유사도 임계값
     * @param limit 최종 결과 수
     * @return Article ID와 평균 유사도
     */
    @Query(value = """
            WITH query_vec AS (
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
            )
            SELECT article_id, AVG(similarity) AS avg_similarity
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
              AND similarity >= :threshold
            GROUP BY article_id
            ORDER BY avg_similarity DESC
            LIMIT :limit
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
            WITH query_vec AS (
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
            )
            SELECT article_id, AVG(similarity) AS avg_similarity
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
              AND similarity >= :threshold
            GROUP BY article_id
            ORDER BY avg_similarity DESC
            LIMIT :limit
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
            WITH query_vec AS (
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
            )
            SELECT article_id, AVG(similarity) AS avg_similarity
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
              AND similarity >= :threshold
            GROUP BY article_id
            ORDER BY avg_similarity DESC
            LIMIT :limit
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
            WITH query_vec AS (
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
            )
            SELECT article_id, AVG(similarity) AS avg_similarity
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
              AND similarity >= :threshold
            GROUP BY article_id
            ORDER BY avg_similarity DESC
            LIMIT :limit
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
            WITH candidates AS (
                SELECT
                    cac.article_id,
                    ccv.embedding_normalized
                FROM clova_article_chunk cac
                JOIN article a ON cac.article_id = a.id
                JOIN clova_chunk_vectors ccv ON ccv.id = cac.id
                WHERE a.deleted_at IS NULL
                  AND cac.is_representative = true
                  AND cac.article_id != :articleId
                  AND cac.embedding_binary IS NOT NULL
                ORDER BY cac.embedding_binary <~> CAST(:queryBinary AS bit(1024))
                LIMIT :candidateLimit
            )
            SELECT article_id, -(embedding_normalized <#> CAST(:queryEmbedding AS halfvec)) AS similarity
            FROM candidates
            WHERE -(embedding_normalized <#> CAST(:queryEmbedding AS halfvec)) >= :threshold
            ORDER BY similarity DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findRelatedArticlesByTwoStageSearch(
            @Param("articleId") Long articleId,
            @Param("queryEmbedding") String queryEmbedding,
            @Param("queryBinary") String queryBinary,
            @Param("candidateLimit") int candidateLimit,
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
            SELECT cac.article_id, -(ccv.embedding_normalized <#> CAST(:queryEmbedding AS halfvec)) as similarity
            FROM clova_article_chunk cac
            JOIN article a ON cac.article_id = a.id
            JOIN clova_chunk_vectors ccv ON ccv.id = cac.id
            WHERE cac.is_representative = true
              AND a.deleted_at IS NULL
              AND cac.article_id != :articleId
            ORDER BY ccv.embedding_normalized <#> CAST(:queryEmbedding AS halfvec)
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findRelatedArticlesByRepresentativeChunk(
            @Param("articleId") Long articleId,
            @Param("queryEmbedding") String queryEmbedding,
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
