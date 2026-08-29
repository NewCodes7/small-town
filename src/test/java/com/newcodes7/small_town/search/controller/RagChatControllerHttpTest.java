package com.newcodes7.small_town.search.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.newcodes7.small_town.config.IntegrationTestBase;
import com.newcodes7.small_town.search.service.RagConcurrencyLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RAG 거절 경로를 <b>HTTP 레벨</b>에서 확인한다.
 *
 * <p>컨트롤러 단위 테스트로는 안 잡히는 게 하나 있다: {@code RestApiExceptionHandler}가
 * {@code @Order(HIGHEST_PRECEDENCE)} + {@code @ExceptionHandler(Exception.class)}라
 * {@code /api/*}에서 던진 {@code ResponseStatusException}을 먼저 잡아 <b>500으로 바꿔버렸다</b>
 * ({@code ExceptionHandlerExceptionResolver}가 {@code ResponseStatusExceptionResolver}보다
 * 앞서 도는 Spring MVC 기본 순서). 그래서 여기서는 "던진 예외의 타입"이 아니라
 * <b>실제로 나가는 상태코드</b>를 본다.
 */
public class RagChatControllerHttpTest extends IntegrationTestBase {

    private static final String BODY = "{\"question\":\"Kafka 도입 사례\",\"conversationId\":\"conv-1\"}";

    @Autowired
    private RagConcurrencyLimiter ragConcurrencyLimiter;

    @Autowired
    private RagChatController ragChatController;

    @Autowired
    private RagChatLoadTestController ragChatLoadTestController;

    @Test
    @DisplayName("동시 실행 상한 초과: 429 + Retry-After + RAG_BUSY (500이 아니다)")
    void 상한초과는_429다() throws Exception {
        int acquired = 0;
        try {
            // 상한만큼 permit을 미리 소진시킨다 (테스트 DB에는 설정 행이 없어 기본값 45가 적용된다)
            while (acquired < 200 && ragConcurrencyLimiter.tryAcquire()) {
                acquired++;
            }
            assertThat(acquired)
                    .as("permit을 하나도 잡지 못하면 이 테스트는 아무것도 검증하지 못한다")
                    .isGreaterThan(0);

            mockMvc.perform(post("/api/rag/answer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "5"))
                    .andExpect(jsonPath("$.error").value("RAG_BUSY"))
                    .andExpect(jsonPath("$.retryAfterSeconds").value(5));
        } finally {
            for (int i = 0; i < acquired; i++) {
                ragConcurrencyLimiter.release();
            }
        }
    }

    @Test
    @DisplayName("시간당 한도 초과: 429다 — ResponseStatusException이 500으로 새지 않는다")
    void 시간당한도_초과는_429다() throws Exception {
        Object original = ReflectionTestUtils.getField(ragChatController, "hourlyLimitPerIp");
        ReflectionTestUtils.setField(ragChatController, "hourlyLimitPerIp", 0);
        try {
            mockMvc.perform(post("/api/rag/answer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isTooManyRequests());
        } finally {
            ReflectionTestUtils.setField(ragChatController, "hourlyLimitPerIp", original);
        }

        // 거절돼도 permit은 누수되지 않는다
        assertThat(ragConcurrencyLimiter.getInUse()).isZero();
    }

    @Test
    @DisplayName("부하테스트 경로 비활성(기본값): 404다 — 존재를 숨기는 게 500으로 새면 안 된다")
    void 부하테스트_비활성은_404다() throws Exception {
        assertThat((Boolean) ReflectionTestUtils.getField(ragChatLoadTestController, "loadTestEnabled"))
                .as("기본값이 false여야 이 검증이 성립한다")
                .isFalse();

        mockMvc.perform(post("/api/rag/answer/loadtest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound());
    }
}
