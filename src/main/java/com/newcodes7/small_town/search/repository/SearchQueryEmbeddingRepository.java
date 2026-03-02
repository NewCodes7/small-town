package com.newcodes7.small_town.search.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.newcodes7.small_town.search.entity.SearchQueryEmbedding;

public interface SearchQueryEmbeddingRepository extends JpaRepository<SearchQueryEmbedding, Long> {
    Optional<SearchQueryEmbedding> findByNormalizedKeyword(String normalizedKeyword);
    Optional<SearchQueryEmbedding> findFirstByEmbeddingIsNotNull();
}
