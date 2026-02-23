package com.newcodes7.small_town.crawler.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.newcodes7.small_town.crawler.entity.CrawlingArticleProcessingLog;

public interface CrawlingArticleProcessingLogRepository extends JpaRepository<CrawlingArticleProcessingLog, Long> {
    Optional<CrawlingArticleProcessingLog> findByRunIdAndArticleId(Long runId, Long articleId);
    Page<CrawlingArticleProcessingLog> findByRunId(Long runId, Pageable pageable);
}
