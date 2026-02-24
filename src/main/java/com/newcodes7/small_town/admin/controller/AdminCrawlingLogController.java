package com.newcodes7.small_town.admin.controller;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.newcodes7.small_town.crawler.entity.CrawlingArticleProcessingLog;
import com.newcodes7.small_town.crawler.entity.CrawlingSchedulerRun;
import com.newcodes7.small_town.crawler.entity.CrawlingStepStatus;
import com.newcodes7.small_town.crawler.repository.CrawlingArticleProcessingLogRepository;
import com.newcodes7.small_town.crawler.repository.CrawlingSchedulerRunRepository;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin/crawling")
@RequiredArgsConstructor
public class AdminCrawlingLogController {

    private final CrawlingSchedulerRunRepository runRepository;
    private final CrawlingArticleProcessingLogRepository logRepository;

    @GetMapping("/runs/{runId}")
    public ResponseEntity<Map<String, Object>> getRun(@PathVariable Long runId) {
        return runRepository.findById(runId)
                .map(run -> ResponseEntity.ok(toRunResponse(run)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/runs/{runId}/logs")
    public ResponseEntity<Map<String, Object>> getRunLogs(
            @PathVariable Long runId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean failedOnly) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        Page<CrawlingArticleProcessingLog> logs = failedOnly
                ? logRepository.findFailuresByRunId(runId, CrawlingStepStatus.FAILURE, pageable)
                : logRepository.findByRunIdWithFailuresFirst(runId, CrawlingStepStatus.FAILURE, pageable);

        Map<String, Object> response = new HashMap<>();
        response.put("runId", runId);
        response.put("page", logs.getNumber());
        response.put("size", logs.getSize());
        response.put("totalElements", logs.getTotalElements());
        response.put("totalPages", logs.getTotalPages());
        response.put("logs", logs.getContent().stream().map(this::toLogResponse).toList());
        if (page == 0) {
            response.put("errorSummary", buildErrorSummary(runId));
        }

        return ResponseEntity.ok(response);
    }

    private Map<String, Object> toRunResponse(CrawlingSchedulerRun run) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", run.getId());
        response.put("jobType", run.getJobType());
        response.put("status", run.getStatus());
        response.put("startedAt", run.getStartedAt());
        response.put("finishedAt", run.getFinishedAt());
        response.put("successCount", run.getSuccessCount());
        response.put("failureCount", run.getFailureCount());
        response.put("newItemsCount", run.getNewItemsCount());
        response.put("errorMessage", run.getErrorMessage());
        return response;
    }

    private Map<String, Object> toLogResponse(CrawlingArticleProcessingLog log) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", log.getId());
        response.put("articleId", log.getArticleId());
        response.put("articleTitle", log.getArticleTitle());
        response.put("articleLink", log.getArticleLink());
        response.put("corporationId", log.getCorporationId());
        response.put("corporationName", log.getCorporationName());
        response.put("isNew", log.isNew());
        response.put("contentExtractionStatus", log.getContentExtractionStatus());
        response.put("contentExtractionError", log.getContentExtractionError());
        response.put("termAnalysisStatus", log.getTermAnalysisStatus());
        response.put("termAnalysisError", log.getTermAnalysisError());
        response.put("embeddingStatus", log.getEmbeddingStatus());
        response.put("embeddingError", log.getEmbeddingError());
        response.put("representativeChunkStatus", log.getRepresentativeChunkStatus());
        response.put("representativeChunkError", log.getRepresentativeChunkError());
        response.put("categoryStatus", log.getCategoryStatus());
        response.put("categoryError", log.getCategoryError());
        response.put("createdAt", log.getCreatedAt());
        return response;
    }

    private List<Map<String, Object>> buildErrorSummary(Long runId) {
        List<CrawlingArticleProcessingLogRepository.FailureErrorView> errors = logRepository
                .findFailureErrorsByRunId(runId, CrawlingStepStatus.FAILURE);

        Map<String, Long> counts = new LinkedHashMap<>();
        errors.forEach(row -> Stream.of(
                        row.getContentExtractionError(),
                        row.getTermAnalysisError(),
                        row.getEmbeddingError(),
                        row.getRepresentativeChunkError(),
                        row.getCategoryError())
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(message -> !message.isEmpty())
                .forEach(message -> counts.merge(message, 1L, Long::sum)));

        return counts.entrySet().stream()
                .sorted((a, b) -> {
                    int cmp = Long.compare(b.getValue(), a.getValue());
                    if (cmp != 0) {
                        return cmp;
                    }
                    return a.getKey().compareTo(b.getKey());
                })
                .limit(10)
                .map(entry -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("message", entry.getKey());
                    item.put("count", entry.getValue());
                    return item;
                })
                .toList();
    }
}
