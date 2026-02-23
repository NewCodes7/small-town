package com.newcodes7.small_town.embedding.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.newcodes7.small_town.embedding.entity.EmbeddingFailure;

@Repository
public interface EmbeddingFailureRepository extends JpaRepository<EmbeddingFailure, Long> {

    void deleteByArticleId(Long articleId);

    @Query("SELECT f FROM EmbeddingFailure f ORDER BY f.createdAt DESC")
    List<EmbeddingFailure> findAllOrderByCreatedAtDesc();
}
