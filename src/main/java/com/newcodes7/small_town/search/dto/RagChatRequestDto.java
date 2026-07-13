package com.newcodes7.small_town.search.dto;

/**
 * 사용자용 RAG 챗봇 요청 본문
 *
 * conversationId는 프론트에서 채팅 세션 시작 시 발급(crypto.randomUUID())해 매 요청에 실어 보낸다.
 * 서버는 이 값으로 직전 턴 히스토리를 조회해 멀티턴 맥락을 구성한다.
 */
public record RagChatRequestDto(String question, String conversationId) {
}
