package com.newcodes7.small_town.admin.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.newcodes7.small_town.global.repository.SearchClickLogRepository;
import com.newcodes7.small_town.global.repository.SearchLogRepository;
import com.newcodes7.small_town.global.service.SearchLogService;
import com.newcodes7.small_town.global.service.SearchLogService.SearchQualityStats;

@WebMvcTest(AdminSearchQualityController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource("classpath:application-test.properties")
class AdminSearchQualityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SearchLogService searchLogService;

    @MockBean
    private SearchLogRepository searchLogRepository;

    @MockBean
    private SearchClickLogRepository searchClickLogRepository;

    @Test
    void 검색_품질_통계_조회() throws Exception {
        // given
        SearchQualityStats stats = SearchQualityStats.builder()
                .totalSearches(100L)
                .uniqueKeywords(50L)
                .averageResultCount(15.5)
                .averageSearchDurationMs(120.0)
                .clickThroughRate(30.0)
                .zeroResultRate(5.0)
                .build();

        when(searchLogService.getSearchQualityStats(anyInt())).thenReturn(stats);

        // when & then
        mockMvc.perform(get("/admin/api/search-quality/stats")
                .param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSearches").value(100))
                .andExpect(jsonPath("$.uniqueKeywords").value(50))
                .andExpect(jsonPath("$.averageResultCount").value("15.5"))
                .andExpect(jsonPath("$.averageSearchDurationMs").value("120"))
                .andExpect(jsonPath("$.clickThroughRate").value("30.0"))
                .andExpect(jsonPath("$.zeroResultRate").value("5.0"));
    }

    @Test
    void 일별_검색_추이_조회() throws Exception {
        // given
        List<Object[]> trendData = Arrays.asList(
            new Object[]{"2024-02-14", 50L},
            new Object[]{"2024-02-15", 60L}
        );

        when(searchLogRepository.countByDateRange(any(LocalDateTime.class)))
                .thenReturn(trendData);

        // when & then
        mockMvc.perform(get("/admin/api/search-quality/daily-trend")
                .param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].date").value("2024-02-14"))
                .andExpect(jsonPath("$[0].count").value(50))
                .andExpect(jsonPath("$[1].date").value("2024-02-15"))
                .andExpect(jsonPath("$[1].count").value(60));
    }

    @Test
    void 인기_검색어_조회() throws Exception {
        // given
        List<Object[]> keywordData = Arrays.asList(
            new Object[]{"spring boot", 100L, 15.5},
            new Object[]{"java", 80L, 12.0}
        );

        Map<String, Long> clickCounts = new HashMap<>();
        clickCounts.put("spring boot", 30L);
        clickCounts.put("java", 20L);

        when(searchLogRepository.findTopKeywordsWithStats(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(keywordData);
        when(searchClickLogRepository.countClicksByKeyword(any(LocalDateTime.class)))
                .thenReturn(clickCounts);

        // when & then
        mockMvc.perform(get("/admin/api/search-quality/top-keywords")
                .param("days", "7")
                .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].keyword").value("spring boot"))
                .andExpect(jsonPath("$[0].searchCount").value(100))
                .andExpect(jsonPath("$[0].avgResultCount").value("15.5"))
                .andExpect(jsonPath("$[0].clickThroughRate").value("30.0")) // 30/100 * 100
                .andExpect(jsonPath("$[1].keyword").value("java"))
                .andExpect(jsonPath("$[1].searchCount").value(80))
                .andExpect(jsonPath("$[1].clickThroughRate").value("25.0")); // 20/80 * 100
    }

    @Test
    void 결과_없는_검색어_조회() throws Exception {
        // given
        List<Object[]> zeroResultData = Arrays.asList(
            new Object[]{"nonexistent keyword", 5L},
            new Object[]{"another unknown", 3L}
        );

        when(searchLogRepository.findZeroResultKeywords(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(zeroResultData);

        // when & then
        mockMvc.perform(get("/admin/api/search-quality/zero-result-keywords")
                .param("days", "7")
                .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].keyword").value("nonexistent keyword"))
                .andExpect(jsonPath("$[0].count").value(5))
                .andExpect(jsonPath("$[1].keyword").value("another unknown"))
                .andExpect(jsonPath("$[1].count").value(3));
    }
}
