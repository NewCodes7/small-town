package com.newcodes7.small_town.crawler.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.crawler.entity.CrawlingArticleProcessingLog;
import com.newcodes7.small_town.crawler.entity.CrawlingJobType;
import com.newcodes7.small_town.crawler.entity.CrawlingRunStatus;
import com.newcodes7.small_town.crawler.entity.CrawlingSchedulerRun;
import com.newcodes7.small_town.crawler.entity.CrawlingStepStatus;
import com.newcodes7.small_town.crawler.entity.CrawlingStepType;
import com.newcodes7.small_town.crawler.repository.CrawlingArticleProcessingLogRepository;
import com.newcodes7.small_town.crawler.repository.CrawlingSchedulerRunRepository;
import com.newcodes7.small_town.global.entity.Article;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CrawlingRunService {

    private final CrawlingSchedulerRunRepository runRepository;
    private final CrawlingArticleProcessingLogRepository logRepository;
    private final CrawlingRunContext runContext;

    @Transactional
    public CrawlingSchedulerRun startRun(CrawlingJobType jobType) {
        CrawlingSchedulerRun run = CrawlingSchedulerRun.builder()
                .jobType(jobType)
                .status(CrawlingRunStatus.RUNNING)
                .startedAt(LocalDateTime.now())
                .successCount(0)
                .failureCount(0)
                .newItemsCount(0)
                .build();
        return runRepository.save(run);
    }

    @Transactional
    public void finishRun(Long runId, CrawlingRunStatus status, int successCount, int failureCount, int newItemsCount, String errorMessage) {
        if (runId == null) {
            return;
        }
        Optional<CrawlingSchedulerRun> runOpt = runRepository.findById(runId);
        if (runOpt.isEmpty()) {
            return;
        }
        CrawlingSchedulerRun run = runOpt.get();
        run.setStatus(status);
        run.setFinishedAt(LocalDateTime.now());
        run.setSuccessCount(successCount);
        run.setFailureCount(failureCount);
        run.setNewItemsCount(newItemsCount);
        run.setErrorMessage(errorMessage);
        runRepository.save(run);
    }

    @Transactional
    public void ensureArticleLogForCurrentRun(Article article) {
        Long runId = runContext.getRunId();
        if (runId == null || article == null || article.getId() == null) {
            return;
        }
        ensureArticleLog(runId, article);
    }

    @Transactional
    public void recordStepForCurrentRun(Article article, CrawlingStepType stepType, CrawlingStepStatus status, String errorMessage) {
        Long runId = runContext.getRunId();
        if (runId == null || article == null || article.getId() == null) {
            return;
        }
        recordStep(runId, article, stepType, status, errorMessage);
    }

    @Transactional
    public void recordStep(Long runId, Article article, CrawlingStepType stepType, CrawlingStepStatus status, String errorMessage) {
        CrawlingArticleProcessingLog log = ensureArticleLog(runId, article);
        if (log == null) {
            return;
        }
        switch (stepType) {
            case CONTENT_EXTRACTION -> {
                log.setContentExtractionStatus(status);
                log.setContentExtractionError(errorMessage);
            }
            case TERM_ANALYSIS -> {
                log.setTermAnalysisStatus(status);
                log.setTermAnalysisError(errorMessage);
            }
            case EMBEDDING -> {
                log.setEmbeddingStatus(status);
                log.setEmbeddingError(errorMessage);
            }
            case REPRESENTATIVE_CHUNK -> {
                log.setRepresentativeChunkStatus(status);
                log.setRepresentativeChunkError(errorMessage);
            }
            case CATEGORY_ANALYSIS -> {
                log.setCategoryStatus(status);
                log.setCategoryError(errorMessage);
            }
            default -> {
                return;
            }
        }
        logRepository.save(log);
    }

    private CrawlingArticleProcessingLog ensureArticleLog(Long runId, Article article) {
        Optional<CrawlingArticleProcessingLog> existing = logRepository.findByRunIdAndArticleId(runId, article.getId());
        if (existing.isPresent()) {
            CrawlingArticleProcessingLog log = existing.get();
            updateBasicInfo(log, article);
            return logRepository.save(log);
        }

        Optional<CrawlingSchedulerRun> runOpt = runRepository.findById(runId);
        if (runOpt.isEmpty()) {
            return null;
        }

        CrawlingArticleProcessingLog log = CrawlingArticleProcessingLog.builder()
                .run(runOpt.get())
                .articleId(article.getId())
                .articleTitle(article.getTitle())
                .articleLink(article.getLink())
                .corporationId(article.getCorporation() != null ? article.getCorporation().getId() : null)
                .corporationName(article.getCorporation() != null ? article.getCorporation().getName() : null)
                .isNew(true)
                .build();

        return logRepository.save(log);
    }

    private void updateBasicInfo(CrawlingArticleProcessingLog log, Article article) {
        log.setArticleTitle(article.getTitle());
        log.setArticleLink(article.getLink());
        if (article.getCorporation() != null) {
            log.setCorporationId(article.getCorporation().getId());
            log.setCorporationName(article.getCorporation().getName());
        }
    }
}
