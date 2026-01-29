package com.newcodes7.small_town.embedding.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.newcodes7.small_town.embedding.entity.ClovaArticleChunk;

/**
 * ClovaArticleChunk 리포지토리
 *
 * 2단계 검색 지원:
 * - Stage 1: Binary HNSW (빠른 후보 필터링)
 * - Stage 2: halfvec Reranking (정밀 유사도 계산)
 */
@Repository
public interface ClovaArticleChunkRepository extends JpaRepository<ClovaArticleChunk, Long> {

    /**
     * 특정 Article의 모든 청크 조회
     */
    List<ClovaArticleChunk> findByArticleIdOrderByChunkIndexAsc(Long articleId);

    /**
     * 특정 Article의 청크 삭제
     */
    void deleteByArticleId(Long articleId);

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
    @Query("SELECT DISTINCT c.article.id FROM ClovaArticleChunk c WHERE c.embedding IS NOT NULL")
    List<Long> findArticleIdsWithEmbedding();

    /**
     * 벡터 유사도 검색 - 상위 K개 청크 평균 방식 (halfvec)
     * 단일 청크만 유사한 경우 낮은 점수를 받아, 주제 전체가 관련있는 글이 상위에 노출됨
     *
     * @param queryEmbedding 검색 쿼리의 임베딩 벡터 (PostgreSQL 배열 포맷)
     * @param threshold 최소 유사도 임계값
     * @param topK 평균 계산에 사용할 상위 청크 수
     * @param limit 최대 결과 수
     * @return Article ID와 상위 K개 청크 평균 유사도를 담은 결과
     */
    @Query(value = "SELECT article_id, AVG(similarity) as avg_similarity " +
           "FROM (" +
           "  SELECT " +
           "    cac.article_id, " +
           "    (1 - (cac.embedding <=> CAST(:queryEmbedding AS halfvec))) as similarity, " +
           "    ROW_NUMBER() OVER (PARTITION BY cac.article_id ORDER BY cac.embedding <=> CAST(:queryEmbedding AS halfvec)) as rn " +
           "  FROM clova_article_chunk cac " +
           "  JOIN article a ON cac.article_id = a.id " +
           "  WHERE a.deleted_at IS NULL " +
           "  AND cac.embedding IS NOT NULL " +
           ") ranked " +
           "WHERE rn <= :topK " +
           "AND similarity >= :threshold " +
           "GROUP BY article_id " +
           "ORDER BY avg_similarity DESC " +
           "LIMIT :limit",
           nativeQuery = true)
    List<Object[]> findArticleIdsByTopKAvgSimilarity(
            @Param("queryEmbedding") String queryEmbedding,
            @Param("threshold") double threshold,
            @Param("topK") int topK,
            @Param("limit") int limit
    );

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
           "    (1 - (cac.embedding <=> CAST(:queryEmbedding AS halfvec))) as similarity, " +
           "    ROW_NUMBER() OVER (PARTITION BY cac.article_id ORDER BY cac.embedding <=> CAST(:queryEmbedding AS halfvec)) as rn " +
           "  FROM clova_article_chunk cac " +
           "  JOIN article a ON cac.article_id = a.id " +
           "  WHERE a.deleted_at IS NULL " +
           "  AND cac.embedding IS NOT NULL " +
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
    @Query("SELECT c FROM ClovaArticleChunk c WHERE c.embedding IS NULL")
    List<ClovaArticleChunk> findChunksWithoutEmbedding(Pageable pageable);

    /**
     * 전체 청크 수
     */
    @Query("SELECT COUNT(c) FROM ClovaArticleChunk c")
    long countAllChunks();

    /**
     * 임베딩이 있는 청크 수
     */
    @Query("SELECT COUNT(c) FROM ClovaArticleChunk c WHERE c.embedding IS NOT NULL")
    long countChunksWithEmbedding();

    /**
     * 임베딩이 있는 Article 수 (중복 제외)
     */
    @Query("SELECT COUNT(DISTINCT c.article.id) FROM ClovaArticleChunk c WHERE c.embedding IS NOT NULL")
    long countArticlesWithEmbedding();

    // ==================== 2단계 검색 (Binary HNSW → halfvec Reranking) ====================

    /**
     * [Stage 1] Binary HNSW 검색 - 빠른 후보 필터링
     * Hamming distance 기반 근사 검색으로 후보 청크 ID 반환
     *
     * @param queryBinary 검색 쿼리의 binary vector (bit string)
     * @param candidateLimit 후보 수 (예: 500)
     * @return chunk ID 리스트
     */
    @Query(value = """
            SELECT cac.id
            FROM clova_article_chunk cac
            JOIN article a ON cac.article_id = a.id
            WHERE a.deleted_at IS NULL
              AND cac.embedding_binary IS NOT NULL
            ORDER BY cac.embedding_binary <~> CAST(:queryBinary AS bit(1024))
            LIMIT :candidateLimit
            """, nativeQuery = true)
    List<Long> findCandidateChunkIdsByBinaryHnsw(
            @Param("queryBinary") String queryBinary,
            @Param("candidateLimit") int candidateLimit
    );

    /**
     * [Stage 2] halfvec Reranking - 후보 청크들의 정밀 유사도 계산
     * Stage 1에서 필터링된 청크들에 대해서만 cosine similarity 계산
     *
     * @param queryEmbedding 검색 쿼리의 임베딩 벡터
     * @param chunkIds Stage 1에서 필터링된 청크 ID 목록
     * @param topK 평균 계산에 사용할 상위 청크 수
     * @param threshold 최소 유사도 임계값
     * @param limit 최종 결과 수
     * @return Article ID와 평균 유사도
     */
    @Query(value = """
            SELECT article_id, AVG(similarity) as avg_similarity
            FROM (
                SELECT
                    cac.article_id,
                    (1 - (cac.embedding <=> CAST(:queryEmbedding AS halfvec))) as similarity,
                    ROW_NUMBER() OVER (PARTITION BY cac.article_id
                                       ORDER BY cac.embedding <=> CAST(:queryEmbedding AS halfvec)) as rn
                FROM clova_article_chunk cac
                WHERE cac.id IN (:chunkIds)
            ) ranked
            WHERE rn <= :topK
              AND similarity >= :threshold
            GROUP BY article_id
            ORDER BY avg_similarity DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> rerankByHalfvec(
            @Param("queryEmbedding") String queryEmbedding,
            @Param("chunkIds") List<Long> chunkIds,
            @Param("topK") int topK,
            @Param("threshold") double threshold,
            @Param("limit") int limit
    );

    /**
     * Binary embedding이 없는 청크 수
     */
    @Query("SELECT COUNT(c) FROM ClovaArticleChunk c WHERE c.embedding IS NOT NULL AND c.embeddingBinary IS NULL")
    long countChunksWithoutBinaryEmbedding();

    /**
     * Binary embedding이 있는 청크 수
     */
    @Query("SELECT COUNT(c) FROM ClovaArticleChunk c WHERE c.embeddingBinary IS NOT NULL")
    long countChunksWithBinaryEmbedding();
}
