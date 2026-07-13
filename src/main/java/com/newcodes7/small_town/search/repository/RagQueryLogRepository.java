package com.newcodes7.small_town.search.repository;

import com.newcodes7.small_town.search.entity.RagQueryLog;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * 어드민 히스토리 목록 조회용 필터 페이징.
     * matchedCorporationIds는 "1, 2, 3" 형태의 comma-join 문자열이라 단순 LIKE로는
     * 부분 일치(예: 1 검색 시 12도 매칭)가 발생 — 정규식으로 콤마/문자열 경계를 고정한다.
     */
    @Query(
            value =
                    """
        SELECT * FROM rag_query_log r
        WHERE (:outcome IS NULL OR r.outcome = :outcome)
          AND (:model IS NULL OR r.model = :model)
          AND (CAST(:from AS timestamp) IS NULL OR r.created_at >= :from)
          AND (CAST(:to AS timestamp) IS NULL OR r.created_at <= :to)
          AND (:keyword IS NULL OR r.question ILIKE '%' || :keyword || '%')
          AND (:corporationId IS NULL
               OR r.matched_corporation_ids ~ ('(^|,\\s)' || :corporationId || '(,|$)'))
        ORDER BY r.created_at DESC
        """,
            countQuery =
                    """
        SELECT COUNT(*) FROM rag_query_log r
        WHERE (:outcome IS NULL OR r.outcome = :outcome)
          AND (:model IS NULL OR r.model = :model)
          AND (CAST(:from AS timestamp) IS NULL OR r.created_at >= :from)
          AND (CAST(:to AS timestamp) IS NULL OR r.created_at <= :to)
          AND (:keyword IS NULL OR r.question ILIKE '%' || :keyword || '%')
          AND (:corporationId IS NULL
               OR r.matched_corporation_ids ~ ('(^|,\\s)' || :corporationId || '(,|$)'))
        """,
            nativeQuery = true)
    Page<RagQueryLog> findAllWithFilter(
            @Param("outcome") String outcome,
            @Param("model") String model,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("keyword") String keyword,
            @Param("corporationId") String corporationId,
            Pageable pageable);

    long countByCreatedAtAfter(LocalDateTime since);

    long countByOutcomeAndCreatedAtAfter(RagQueryLog.Outcome outcome, LocalDateTime since);
}
