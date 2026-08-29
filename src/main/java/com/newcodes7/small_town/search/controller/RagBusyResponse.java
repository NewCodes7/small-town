package com.newcodes7.small_town.search.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * RAG 유입 제어 거절 응답(429)을 응답 객체에 직접 쓴다.
 *
 * <p><b>왜 예외를 던지지 않는가.</b> 두 가지가 동시에 걸려 있다.
 * <ol>
 *   <li>SSE라 {@code SseEmitter}를 만들고 나면 헤더가 이미 200으로 나가 상태코드를 바꿀 수 없다 —
 *       거절은 emitter <b>생성 전</b>에 확정돼야 한다.</li>
 *   <li>과부하 시 거절은 로그를 남기면 안 된다. prod 로깅은 AsyncAppender 없이 RollingFileAppender
 *       직결이라 appender 락 경합이 그대로 요청 경로에 얹힌다(런 3에서 붕괴 12분간 37,338줄이
 *       쌓인 그 경로 — 결과 문서 5.3). 예외는 스택트레이스 fill-in + 리졸버 체인 전체를 타므로
 *       셰딩 경로에는 부적합하다.</li>
 * </ol>
 *
 * <p>핸들러가 {@code null}을 반환하면 {@code ResponseBodyEmitterReturnValueHandler}가
 * {@code requestHandled=true}로 처리하므로, 여기서 쓴 응답이 그대로 나간다.
 *
 * <p>바디는 상수 문자열이다 — 사용자 입력이 섞이지 않아 이스케이프가 필요 없고, 거절 경로에서
 * 직렬화 비용을 만들 이유도 없다. 검색의 {@code SEARCH_BUSY}와 짝을 이룬다.
 */
final class RagBusyResponse {

    private static final String RETRY_AFTER_SECONDS = "5";

    private static final String BODY = """
            {"error":"RAG_BUSY",\
            "message":"요청이 많아 잠시 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.",\
            "retryAfterSeconds":5}""";

    private RagBusyResponse() {}

    static void write(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(BODY);
    }
}
