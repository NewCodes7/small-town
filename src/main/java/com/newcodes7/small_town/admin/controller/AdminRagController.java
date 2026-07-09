package com.newcodes7.small_town.admin.controller;

import com.newcodes7.small_town.search.service.RagAnswerService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 기업 기술 활용 사례 질의응답(RAG) 관리자 테스트 페이지
 * 뷰(/admin/rag)와 API(/api/admin/rag/**)의 프리픽스가 달라 클래스 레벨 매핑 없이 절대 경로 사용.
 * 두 경로 모두 SecurityConfig의 /admin/**, /api/admin/** ADMIN 규칙으로 보호된다.
 */
@Controller
@RequiredArgsConstructor
public class AdminRagController {

    // TODO: 임시 조치 (2026-07-09) — Gemini 타임아웃 5분 상향에 맞춘 값. 원인 규명 후 재조정 필요
    // 전처리·답변 생성 Gemini 호출 타임아웃(각 5분)이 순차로 소진되는 최악 케이스보다 길어야 한다
    private static final long SSE_TIMEOUT_MS = 660_000L;
    private static final int MAX_TOP_ARTICLES = 10;
    private static final int MAX_CHUNKS_PER_ARTICLE = 5;
    private static final double MIN_THRESHOLD = 0.4;
    private static final double MAX_THRESHOLD = 0.8;

    private final RagAnswerService ragAnswerService;
    @Qualifier("searchExecutor")
    private final ExecutorService searchExecutor;

    @GetMapping("/admin/rag")
    public String page() {
        return "admin/rag";
    }

    @GetMapping(value = "/api/admin/rag/answer", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter answer(
            @RequestParam String q,
            @RequestParam(defaultValue = "5") int topArticles,
            @RequestParam(defaultValue = "3") int chunksPerArticle,
            @RequestParam(defaultValue = "0.6") double threshold) {

        int safeTopArticles = Math.clamp(topArticles, 1, MAX_TOP_ARTICLES);
        int safeChunksPerArticle = Math.clamp(chunksPerArticle, 1, MAX_CHUNKS_PER_ARTICLE);
        double safeThreshold = Math.clamp(threshold, MIN_THRESHOLD, MAX_THRESHOLD);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        CompletableFuture.runAsync(
                () -> ragAnswerService.streamAnswer(q, safeTopArticles, safeChunksPerArticle, safeThreshold, emitter),
                searchExecutor);
        return emitter;
    }
}
