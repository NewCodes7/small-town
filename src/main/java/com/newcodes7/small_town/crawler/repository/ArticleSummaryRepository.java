package com.newcodes7.small_town.crawler.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.newcodes7.small_town.global.entity.ArticleSummary;

@Repository
public interface ArticleSummaryRepository extends JpaRepository<ArticleSummary, Long> {
    List<ArticleSummary> findByArticleIdAndDeletedAtIsNullOrderByCreatedAt(Long articleId);
    List<ArticleSummary> findByArticleId(Long articleId);

    @Modifying
    @Query("DELETE FROM ArticleSummary s WHERE s.article.id = :articleId")
    void deleteByArticleId(@Param("articleId") Long articleId);
}