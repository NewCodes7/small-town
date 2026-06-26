package com.newcodes7.small_town.admin.service;

import static org.assertj.core.api.Assertions.*;

import com.newcodes7.small_town.admin.dto.DatabaseStatsResponseDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@TestPropertySource("classpath:application-test.properties")
@Transactional
class DatabaseStatsServiceTest {

    @Autowired private DatabaseStatsService databaseStatsService;

    @Test
    @DisplayName("테이블 통계는 풀스캔 없이 추정 행 수와 DB 크기를 반환한다")
    void getTableStats_returnsNonNegativeStats() {
        DatabaseStatsResponseDto stats = databaseStatsService.getTableStats();

        assertThat(stats.approximate()).isTrue();
        assertThat(stats.capturedAt()).isNotNull();
        assertThat(stats.databaseSizeBytes()).isPositive();
        assertThat(stats.tables()).isNotEmpty();
        assertThat(stats.tables())
                .allSatisfy(
                        t -> {
                            assertThat(t.name()).isNotBlank();
                            assertThat(t.estimatedRows()).isGreaterThanOrEqualTo(0L);
                            assertThat(t.totalSizeBytes()).isGreaterThanOrEqualTo(0L);
                        });
    }

    @Test
    @DisplayName("주요 도메인 테이블(article)이 통계에 포함된다")
    void getTableStats_includesArticleTable() {
        DatabaseStatsResponseDto stats = databaseStatsService.getTableStats();

        assertThat(stats.tables()).anySatisfy(t -> assertThat(t.name()).isEqualTo("article"));
    }
}
