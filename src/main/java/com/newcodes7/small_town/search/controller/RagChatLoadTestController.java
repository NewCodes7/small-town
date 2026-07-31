package com.newcodes7.small_town.search.controller;

import com.newcodes7.small_town.global.util.Client;
import com.newcodes7.small_town.search.config.RagModelProperties;
import com.newcodes7.small_town.search.dto.RagChatRequestDto;
import com.newcodes7.small_town.search.service.RagAnswerService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 부하테스트 전용 RAG 스트리밍 엔드포인트 — 실사용자 경로(/api/rag/answer)와 흐름은 동일하되
 * LLM을 mock 모델(rag.chat.loadtest.*, endpoint override → load-test/mock server)로 대체한다.
 * 실사용자 요청과의 구분: URL 자체가 다르고, rag_query_log에는 mock 모델 id(mock.%)로 기록된다.
 *
 * 접근 통제 3중:
 *  1. 기본 비활성(rag.chat.loadtest.enabled=false) — 비활성 시 404로 존재 자체를 숨긴다
 *  2. nginx location이 $loadtest_bypass geo(부하테스트 IP)만 통과시키고 나머지는 403
 *  3. mock 모델 endpoint 미설정이면 503 — 실 Bedrock으로 새는 오설정을 기동 전에 잡는다
 * 앱 레벨 시간당 rate limit은 걸지 않는다(테스트 창에서만 켜는 전용 경로 — README 절차 참고).
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class RagChatLoadTestController {

    // 검색 파라미터는 실사용자 경로(RagChatController)와 동일하게 고정 — 부하 특성을 같게 유지
    private static final int TOP_ARTICLES = 5;
    private static final int CHUNKS_PER_ARTICLE = 3;
    private static final double THRESHOLD = 0.6;
    private static final long SSE_TIMEOUT_MS = 150_000L;

    @Value("${rag.chat.loadtest.enabled:false}")
    private boolean loadTestEnabled;

    @Value("${rag.chat.loadtest.answer-model-id:}")
    private String answerModelId;

    @Value("${rag.chat.loadtest.preprocess-model-id:}")
    private String preprocessModelId;

    private final RagAnswerService ragAnswerService;
    private final RagModelProperties ragModelProperties;
    @Qualifier("searchExecutor")
    private final ExecutorService searchExecutor;

    @PostMapping(value = "/api/rag/answer/loadtest", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter answer(@RequestBody RagChatRequestDto body, HttpServletRequest request) {
        if (!loadTestEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        RagModelProperties.ModelOption model = resolveMockModel(answerModelId);
        RagModelProperties.ModelOption preprocessModel = resolveMockModel(preprocessModelId);
        String ipAddress = Client.getClientIpAddress(request);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        CompletableFuture.runAsync(
                () -> ragAnswerService.streamAnswerForLoadTest(
                        body.question(), TOP_ARTICLES, CHUNKS_PER_ARTICLE, THRESHOLD, model, preprocessModel,
                        body.conversationId(), ipAddress, null, emitter),
                searchExecutor);
        return emitter;
    }

    private RagModelProperties.ModelOption resolveMockModel(String modelId) {
        RagModelProperties.ModelOption model = ragModelProperties.findById(modelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "rag.models에 부하테스트 모델 " + modelId + " 설정이 없습니다"));
        if (!model.hasEndpointOverride()) {
            // endpoint(RAG_LOADTEST_BEDROCK_ENDPOINT) 미설정 상태로 실행하면 실 Bedrock으로 과금 호출이 나간다
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "부하테스트 모델 " + modelId + "에 mock endpoint가 없습니다 (RAG_LOADTEST_BEDROCK_ENDPOINT 확인)");
        }
        return model;
    }
}
