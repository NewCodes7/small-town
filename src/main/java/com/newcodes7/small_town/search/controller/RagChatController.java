package com.newcodes7.small_town.search.controller;

import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.global.util.Client;
import com.newcodes7.small_town.search.config.RagModelProperties;
import com.newcodes7.small_town.search.dto.RagChatRequestDto;
import com.newcodes7.small_town.search.repository.RagQueryLogRepository;
import com.newcodes7.small_town.search.service.RagAnswerService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 검색 페이지 "AI 기업 사례 모아보기" 카드가 사용하는 RAG 질의응답 스트리밍 API.
 * admin/rag 테스트 페이지와 달리 모델·검색 파라미터를 고정하고, 로그인 여부 무관(IP 기반 식별)으로 동작한다.
 * rate limit은 nginx에서 처리한다(분당/시간당 제한, /api/rag/answer 전용 location).
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class RagChatController {

    // Claude Sonnet 4.5 (Bedrock) 고정 — rag.models[4] (application.properties)
    private static final String FIXED_MODEL_ID = "global.anthropic.claude-sonnet-4-5-20250929-v1:0";
    private static final int TOP_ARTICLES = 5;
    private static final int CHUNKS_PER_ARTICLE = 3;
    private static final double THRESHOLD = 0.6;
    // 전처리(sync, 30s) + 답변 생성(async, 90s) 순차 호출 worst case(BedrockClientFactory) + 여유
    private static final long SSE_TIMEOUT_MS = 150_000L;
    // 전처리(쿼리 분해 JSON 추출) 전용 경량 모델 — 답변 모델과 분리해 TTFT 단축. 미설정/미등록이면 답변 모델 사용.
    @Value("${rag.chat.preprocess-model-id:}")
    private String preprocessModelId;

    // nginx는 분당 rate limit만 처리(정수 r/m 제약으로 시간당 30회를 표현 못 함) — 시간당 한도는 여기서 카운트
    @Value("${rag.chat.hourly-limit-per-ip:30}")
    private int hourlyLimitPerIp;

    // 부하테스트(Fargate) 전용 예외 — nginx의 X-LoadTest-Token(nginx/loadtest_token.conf)과 같은 값이어야 함.
    // Fargate 태스크는 매번 임의 public IP를 쓰므로 IP 대신 헤더로 판별한다. 값이 비어 있으면 기능 자체가 꺼짐.
    // 다른 요청(실사용자)의 시간당 한도는 그대로 유지됨.
    @Value("${rag.chat.loadtest-bypass-token:}")
    private String loadtestBypassToken;

    private final RagAnswerService ragAnswerService;
    private final RagModelProperties ragModelProperties;
    private final UserRepository userRepository;
    private final RagQueryLogRepository ragQueryLogRepository;
    @Qualifier("searchExecutor")
    private final ExecutorService searchExecutor;

    @PostMapping(value = "/api/rag/answer", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter answer(
            @RequestBody RagChatRequestDto body,
            @AuthenticationPrincipal UserDetails userDetails,
            HttpServletRequest request) {
        RagModelProperties.ModelOption model = ragModelProperties.findById(FIXED_MODEL_ID)
                .orElseThrow(() -> new IllegalStateException("rag.models에 " + FIXED_MODEL_ID + " 설정이 없습니다"));
        RagModelProperties.ModelOption preprocessModel = resolvePreprocessModel(model);
        String ipAddress = Client.getClientIpAddress(request);
        Long userId = resolveUserId(userDetails);

        LocalDateTime hourAgo = LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusHours(1);
        boolean exempt = !loadtestBypassToken.isBlank()
                && loadtestBypassToken.equals(request.getHeader("X-LoadTest-Token"));
        if (!exempt && ragQueryLogRepository.countByIpAddressAndCreatedAtAfter(ipAddress, hourAgo) >= hourlyLimitPerIp) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "시간당 요청 한도를 초과했습니다");
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        CompletableFuture.runAsync(
                () -> ragAnswerService.streamAnswer(
                        body.question(), TOP_ARTICLES, CHUNKS_PER_ARTICLE, THRESHOLD, model, preprocessModel,
                        body.conversationId(), ipAddress, userId, emitter),
                searchExecutor);
        return emitter;
    }

    private RagModelProperties.ModelOption resolvePreprocessModel(RagModelProperties.ModelOption answerModel) {
        if (preprocessModelId == null || preprocessModelId.isBlank()) {
            return answerModel;
        }
        return ragModelProperties.findById(preprocessModelId)
                .orElseGet(() -> {
                    log.warn("rag.chat.preprocess-model-id={}가 rag.models에 없어 답변 모델로 전처리합니다", preprocessModelId);
                    return answerModel;
                });
    }

    private Long resolveUserId(UserDetails userDetails) {
        if (userDetails == null) {
            return null;
        }
        return userRepository.findByUsernameAndDeletedAtIsNull(userDetails.getUsername())
                .map(user -> user.getId())
                .orElse(null);
    }
}
