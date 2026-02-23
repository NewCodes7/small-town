package com.newcodes7.small_town.crawler.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "crawling_article_processing_log",
    indexes = {
        @Index(name = "idx_crawling_article_log_run_id", columnList = "run_id"),
        @Index(name = "idx_crawling_article_log_article_id", columnList = "article_id")
    }
)
public class CrawlingArticleProcessingLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "run_id", nullable = false)
    private CrawlingSchedulerRun run;

    @Column(name = "article_id")
    private Long articleId;

    @Column(name = "article_title", length = 300)
    private String articleTitle;

    @Column(name = "article_link", columnDefinition = "TEXT")
    private String articleLink;

    @Column(name = "corporation_id")
    private Long corporationId;

    @Column(name = "corporation_name", length = 200)
    private String corporationName;

    @Column(name = "is_new", nullable = false)
    private boolean isNew;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_extraction_status", length = 20)
    private CrawlingStepStatus contentExtractionStatus;

    @Column(name = "content_extraction_error", columnDefinition = "TEXT")
    private String contentExtractionError;

    @Enumerated(EnumType.STRING)
    @Column(name = "term_analysis_status", length = 20)
    private CrawlingStepStatus termAnalysisStatus;

    @Column(name = "term_analysis_error", columnDefinition = "TEXT")
    private String termAnalysisError;

    @Enumerated(EnumType.STRING)
    @Column(name = "embedding_status", length = 20)
    private CrawlingStepStatus embeddingStatus;

    @Column(name = "embedding_error", columnDefinition = "TEXT")
    private String embeddingError;

    @Enumerated(EnumType.STRING)
    @Column(name = "representative_chunk_status", length = 20)
    private CrawlingStepStatus representativeChunkStatus;

    @Column(name = "representative_chunk_error", columnDefinition = "TEXT")
    private String representativeChunkError;

    @Enumerated(EnumType.STRING)
    @Column(name = "category_status", length = 20)
    private CrawlingStepStatus categoryStatus;

    @Column(name = "category_error", columnDefinition = "TEXT")
    private String categoryError;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
