package com.newcodes7.small_town.search.llm;

import java.io.IOException;

/**
 * LLM 호출 실패 예외 — message는 SSE error 이벤트로 그대로 노출 가능한 한국어 문구여야 한다.
 * 원인 상세(HTTP 상태, AWS 에러 코드 등)는 cause에 담는다.
 */
public class RagLlmException extends IOException {

    public RagLlmException(String userMessage) {
        super(userMessage);
    }

    public RagLlmException(String userMessage, Throwable cause) {
        super(userMessage, cause);
    }
}
