package com.newcodes7.small_town.crawler.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.newcodes7.small_town.crawler.entity.CrawlingArticleProcessingLog;
import com.newcodes7.small_town.crawler.entity.CrawlingStepStatus;

public interface CrawlingArticleProcessingLogRepository extends JpaRepository<CrawlingArticleProcessingLog, Long> {
    Optional<CrawlingArticleProcessingLog> findByRunIdAndArticleId(Long runId, Long articleId);
    Page<CrawlingArticleProcessingLog> findByRunId(Long runId, Pageable pageable);

    @Query("""
        select log
        from CrawlingArticleProcessingLog log
        where log.run.id = :runId
          and (
            log.contentExtractionStatus = :status
            or log.termAnalysisStatus = :status
            or log.embeddingStatus = :status
            or log.representativeChunkStatus = :status
            or log.categoryStatus = :status
          )
        """)
    Page<CrawlingArticleProcessingLog> findFailuresByRunId(
            @Param("runId") Long runId,
            @Param("status") CrawlingStepStatus status,
            Pageable pageable);

    @Query("""
        select log
        from CrawlingArticleProcessingLog log
        where log.run.id = :runId
        order by
          case when (
            log.contentExtractionStatus = :status
            or log.termAnalysisStatus = :status
            or log.embeddingStatus = :status
            or log.representativeChunkStatus = :status
            or log.categoryStatus = :status
          ) then 1 else 0 end desc,
          log.id desc
        """)
    Page<CrawlingArticleProcessingLog> findByRunIdWithFailuresFirst(
            @Param("runId") Long runId,
            @Param("status") CrawlingStepStatus status,
            Pageable pageable);

    @Query("""
        select log.contentExtractionError as contentExtractionError,
               log.termAnalysisError as termAnalysisError,
               log.embeddingError as embeddingError,
               log.representativeChunkError as representativeChunkError,
               log.categoryError as categoryError
        from CrawlingArticleProcessingLog log
        where log.run.id = :runId
          and (
            log.contentExtractionStatus = :status
            or log.termAnalysisStatus = :status
            or log.embeddingStatus = :status
            or log.representativeChunkStatus = :status
            or log.categoryStatus = :status
          )
        """)
    List<FailureErrorView> findFailureErrorsByRunId(
            @Param("runId") Long runId,
            @Param("status") CrawlingStepStatus status);

    interface FailureErrorView {
        String getContentExtractionError();
        String getTermAnalysisError();
        String getEmbeddingError();
        String getRepresentativeChunkError();
        String getCategoryError();
    }
}
