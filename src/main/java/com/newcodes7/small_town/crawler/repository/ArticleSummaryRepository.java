package com.newcodes7.small_town.crawler.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.newcodes7.small_town.global.entity.ArticleSummary;

@Repository
public interface ArticleSummaryRepository extends JpaRepository<ArticleSummary, Long> {
    List<ArticleSummary> findByArticleIdAndDeletedAtIsNullOrderByCreatedAt(Long articleId);
    List<ArticleSummary> findByArticleId(Long articleId);
}