package com.newcodes7.small_town.article.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.newcodes7.small_town.global.entity.ArticleChunk;

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
     * 벡터 유사도 검색 - 청크 단위
     * 유사도가 높은 청크를 찾아서 해당 Article ID와 유사도를 반환
     *
     * @param queryEmbedding 검색 쿼리의 임베딩 벡터 (PostgreSQL 배열 포맷)
     * @param threshold 최소 유사도 임계값
     * @param limit 최대 결과 수
     * @return Article ID와 최고 유사도를 담은 결과
     */
    @Query(value = "SELECT " +
           "ac.article_id, " +
           "MAX(1 - (ac.embedding <=> CAST(:queryEmbedding AS vector))) as max_similarity " +
           "FROM article_chunk ac " +
           "JOIN article a ON ac.article_id = a.id " +
           "WHERE a.deleted_at IS NULL " +
           "AND ac.embedding IS NOT NULL " +
           "AND 1 - (ac.embedding <=> CAST(:queryEmbedding AS vector)) >= :threshold " +
           "GROUP BY ac.article_id " +
           "ORDER BY max_similarity DESC " +
           "LIMIT :limit",
           nativeQuery = true)
    List<Object[]> findArticleIdsBySimilarity(
            @Param("queryEmbedding") String queryEmbedding,
            @Param("threshold") double threshold,
            @Param("limit") int limit
    );

    /**
     * 임베딩이 없는 청크 조회
     */
    @Query("SELECT ac FROM ArticleChunk ac " +
           "WHERE ac.embedding IS NULL")
    List<ArticleChunk> findChunksWithoutEmbedding(Pageable pageable);

    /**
     * 특정 Article의 임베딩이 없는 청크 수
     */
    @Query("SELECT COUNT(ac) FROM ArticleChunk ac " +
           "WHERE ac.article.id = :articleId " +
           "AND ac.embedding IS NULL")
    long countChunksWithoutEmbeddingByArticleId(@Param("articleId") Long articleId);

    /**
     * 전체 청크 수
     */
    @Query("SELECT COUNT(ac) FROM ArticleChunk ac")
    long countAllChunks();

    /**
     * 임베딩이 있는 청크 수
     */
    @Query("SELECT COUNT(ac) FROM ArticleChunk ac WHERE ac.embedding IS NOT NULL")
    long countChunksWithEmbedding();
}
