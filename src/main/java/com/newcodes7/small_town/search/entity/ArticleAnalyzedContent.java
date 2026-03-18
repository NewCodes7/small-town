package com.newcodes7.small_town.search.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * article_analyzed_content 테이블 엔티티
 * article_search_view Materialized View 대체 - 형태소 분석된 title_terms/content_terms 저장
 * id는 article.id와 동일 (FK, ON DELETE CASCADE)
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(name = "article_analyzed_content")
public class ArticleAnalyzedContent {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "title")
    private String title;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "corporation_id")
    private Long corporationId;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "title_terms", columnDefinition = "TEXT")
    private String titleTerms;

    @Column(name = "content_terms", columnDefinition = "TEXT")
    private String contentTerms;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
