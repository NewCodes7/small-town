package com.newcodes7.small_town.search.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcodes7.small_town.config.IntegrationTestBase;
import com.newcodes7.small_town.search.entity.RagQueryLog;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

class RagQueryLogRepositoryTest extends IntegrationTestBase {

    @Autowired
    private RagQueryLogRepository ragQueryLogRepository;

    private RagQueryLog save(String question, RagQueryLog.Outcome outcome, String model, String matchedCorporationIds) {
        return ragQueryLogRepository.save(RagQueryLog.builder()
                .question(question)
                .outcome(outcome)
                .model(model)
                .matchedCorporationIds(matchedCorporationIds)
                .build());
    }

    @Test
    @DisplayName("어드민 히스토리 필터: outcome/model/keyword 조합")
    void findAllWithFilter_byOutcomeModelKeyword() {
        save("네이버 사례 알려줘", RagQueryLog.Outcome.ANSWERED, "claude-sonnet-4-5", "1, 2");
        save("카카오 사례", RagQueryLog.Outcome.NO_RESULT, "claude-sonnet-4-5", null);
        save("에러난 질문", RagQueryLog.Outcome.ERROR, "gpt-4", null);

        Page<RagQueryLog> result = ragQueryLogRepository.findAllWithFilter(
                RagQueryLog.Outcome.ANSWERED.name(), "claude-sonnet-4-5", null, null, "네이버", null,
                PageRequest.of(0, 10));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getQuestion()).isEqualTo("네이버 사례 알려줘");
    }

    @Test
    @DisplayName("어드민 히스토리 필터: corporationId는 콤마 경계로 정확히 매칭 (1이 12를 매칭하지 않음)")
    void findAllWithFilter_corporationIdMatchesExactBoundary() {
        save("기업1 질문", RagQueryLog.Outcome.ANSWERED, "m1", "1, 3");
        save("기업12 질문", RagQueryLog.Outcome.ANSWERED, "m1", "12, 3");
        save("기업21 질문", RagQueryLog.Outcome.ANSWERED, "m1", "21");

        Page<RagQueryLog> result = ragQueryLogRepository.findAllWithFilter(
                null, null, null, null, null, "1", PageRequest.of(0, 10));

        List<String> questions = result.getContent().stream().map(RagQueryLog::getQuestion).toList();
        assertThat(questions).containsExactly("기업1 질문");
    }

    @Test
    @DisplayName("어드민 히스토리 필터: 기간(from/to) 필터")
    void findAllWithFilter_byDateRange() {
        RagQueryLog log = save("기간 테스트", RagQueryLog.Outcome.ANSWERED, "m1", null);

        Page<RagQueryLog> withinRange = ragQueryLogRepository.findAllWithFilter(
                null, null, log.getCreatedAt().minusMinutes(1), log.getCreatedAt().plusMinutes(1), null, null,
                PageRequest.of(0, 10));
        Page<RagQueryLog> outsideRange = ragQueryLogRepository.findAllWithFilter(
                null, null, log.getCreatedAt().plusDays(1), null, null, null,
                PageRequest.of(0, 10));

        assertThat(withinRange.getTotalElements()).isEqualTo(1);
        assertThat(outsideRange.getTotalElements()).isEqualTo(0);
    }

    @Test
    @DisplayName("countByCreatedAtAfter / countByOutcomeAndCreatedAtAfter")
    void countMethods() {
        save("q1", RagQueryLog.Outcome.ANSWERED, "m1", null);
        save("q2", RagQueryLog.Outcome.ERROR, "m1", null);

        LocalDateTime since = LocalDateTime.now().minusMinutes(1);

        assertThat(ragQueryLogRepository.countByCreatedAtAfter(since)).isEqualTo(2);
        assertThat(ragQueryLogRepository.countByOutcomeAndCreatedAtAfter(RagQueryLog.Outcome.ERROR, since))
                .isEqualTo(1);
    }
}
