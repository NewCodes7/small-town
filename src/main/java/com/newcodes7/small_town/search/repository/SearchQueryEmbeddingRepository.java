package com.newcodes7.small_town.search.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.search.entity.SearchQueryEmbedding;

public interface SearchQueryEmbeddingRepository extends JpaRepository<SearchQueryEmbedding, Long> {
    Optional<SearchQueryEmbedding> findByNormalizedKeyword(String normalizedKeyword);
    Optional<SearchQueryEmbedding> findFirstByEmbeddingIsNotNull();

    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = """
            INSERT INTO search_query_embedding (search_keyword, normalized_keyword, embedding, embedding_generated_at, created_at)
            VALUES (:keyword, :normalized, CAST(:embeddingStr AS halfvec), :generatedAt, NOW())
            ON CONFLICT (normalized_keyword) DO UPDATE SET
              search_keyword = EXCLUDED.search_keyword,
              embedding = EXCLUDED.embedding,
              embedding_generated_at = EXCLUDED.embedding_generated_at
            """)
    void upsertEmbedding(
            @Param("keyword") String keyword,
            @Param("normalized") String normalized,
            @Param("embeddingStr") String embeddingStr,
            @Param("generatedAt") LocalDateTime generatedAt);
}
