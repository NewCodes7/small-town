package com.newcodes7.small_town.search.controller;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.newcodes7.small_town.search.dto.AiSummaryPromptDto;
import com.newcodes7.small_town.search.service.AiSummaryService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class AiSummaryController {

    private final AiSummaryService aiSummaryService;
    @Qualifier("searchExecutor")
    private final ExecutorService searchExecutor;

    @GetMapping(value = "/ai-summary", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter getAiSummary(@RequestParam String q) {
        SseEmitter emitter = new SseEmitter(90_000L);
        CompletableFuture.runAsync(() -> aiSummaryService.streamAiSummary(q, emitter), searchExecutor);
        return emitter;
    }

    @GetMapping("/recommended-queries")
    public List<String> getRecommendedQueries() {
        return aiSummaryService.getRecommendedQueries();
    }

    @GetMapping("/ai-summary-prompt")
    public ResponseEntity<AiSummaryPromptDto> getAiSummaryPrompt(@RequestParam String q) {
        if (q == null || q.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(aiSummaryService.buildPromptPreview(q.trim()));
    }
}
