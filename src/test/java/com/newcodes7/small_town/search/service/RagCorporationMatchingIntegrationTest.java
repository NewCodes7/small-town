package com.newcodes7.small_town.search.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcodes7.small_town.config.IntegrationTestBase;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.global.entity.Corporation;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * RAG 기업 매칭 쿼리(findActiveByLowerNames) 통합 테스트
 * 실제 PostgreSQL에서 name/alternateName lower 정확 일치 동작 검증
 */
public class RagCorporationMatchingIntegrationTest extends IntegrationTestBase {

    @Autowired
    private CorporationRepository corporationRepository;

    private Corporation naver;
    private Corporation toss;

    @BeforeEach
    void setUp() {
        naver = corporationRepository.save(Corporation.builder()
                .name("네이버")
                .alternateName("NAVER")
                .isDomestic(1)
                .build());
        toss = corporationRepository.save(Corporation.builder()
                .name("토스")
                .alternateName("Toss")
                .isDomestic(1)
                .build());
    }

    @Test
    @DisplayName("name 정확 일치로 매칭")
    void findActiveByLowerNames_matchesByName() {
        List<Corporation> result = corporationRepository.findActiveByLowerNames(List.of("네이버"));

        assertThat(result).extracting(Corporation::getId).containsExactly(naver.getId());
    }

    @Test
    @DisplayName("alternateName 대소문자 무관 매칭 (lower 비교)")
    void findActiveByLowerNames_matchesByAlternateNameCaseInsensitive() {
        // 호출부 규약: lower/trim 후 전달
        List<Corporation> result = corporationRepository.findActiveByLowerNames(List.of("naver", "toss"));

        assertThat(result).extracting(Corporation::getId)
                .containsExactlyInAnyOrder(naver.getId(), toss.getId());
    }

    @Test
    @DisplayName("부분 문자열은 매칭되지 않음 (정확 일치만)")
    void findActiveByLowerNames_noPartialMatch() {
        List<Corporation> result = corporationRepository.findActiveByLowerNames(List.of("네이", "nav"));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("소프트 삭제된 기업은 제외")
    void findActiveByLowerNames_excludesDeleted() {
        toss.softDelete();
        corporationRepository.save(toss);

        List<Corporation> result = corporationRepository.findActiveByLowerNames(List.of("토스"));

        assertThat(result).isEmpty();
    }
}
