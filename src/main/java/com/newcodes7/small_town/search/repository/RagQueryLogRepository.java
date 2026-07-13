package com.newcodes7.small_town.search.repository;

import com.newcodes7.small_town.search.entity.RagQueryLog;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RagQueryLogRepository extends JpaRepository<RagQueryLog, Long> {

    /**
     * 멀티턴 히스토리 조회용 — 최근 답변 완료 턴만(outcome=ANSWERED) 최신순 조회.
     * 호출부에서 chronological 순서로 쓰려면 {@code .reversed()}로 뒤집어야 한다.
     */
    List<RagQueryLog> findTop3ByConversationIdAndOutcomeOrderByCreatedAtDesc(
            String conversationId, RagQueryLog.Outcome outcome);

    /**
     * IP 기준 시간당 rate limit 체크용 — nginx는 정수 r/s·r/m만 지원해 "시간당 N회"를
     * 정확히 표현할 수 없으므로(소수점 rate 불가) 이 카운트로 애플리케이션에서 직접 판단한다.
     */
    long countByIpAddressAndCreatedAtAfter(String ipAddress, LocalDateTime since);
}
