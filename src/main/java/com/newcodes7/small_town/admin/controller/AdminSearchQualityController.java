package com.newcodes7.small_town.admin.controller;

import com.newcodes7.small_town.global.entity.SearchClickLog;
import com.newcodes7.small_town.global.repository.SearchClickLogRepository;
import com.newcodes7.small_town.global.repository.SearchLogRepository;
import com.newcodes7.small_town.global.service.SearchLogService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 검색 품질 대시보드 API 컨트롤러
 */
@Controller
@RequestMapping("/admin/api/search-quality")
@RequiredArgsConstructor
@Slf4j
public class AdminSearchQualityController {

    private final SearchLogService searchLogService;
    private final SearchLogRepository searchLogRepository;
    private final SearchClickLogRepository searchClickLogRepository;

    /**
     * 검색 품질 통계
     */
    @GetMapping("/stats")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getStats(@RequestParam(defaultValue = "7") int days) {
        SearchLogService.SearchQualityStats stats = searchLogService.getSearchQualityStats(days);
        
        Map<String, Object> response = new HashMap<>();
        response.put("totalSearches", stats.getTotalSearches());
        response.put("uniqueKeywords", stats.getUniqueKeywords());
        response.put("averageResultCount", String.format("%.1f", stats.getAverageResultCount()));
        response.put("averageSearchDurationMs", String.format("%.0f", stats.getAverageSearchDurationMs()));
        response.put("clickThroughRate", String.format("%.1f", stats.getClickThroughRate()));
        response.put("zeroResultRate", String.format("%.1f", stats.getZeroResultRate()));
        
        return ResponseEntity.ok(response);
    }

    /**
     * 일별 검색 추이
     */
    @GetMapping("/daily-trend")
    @ResponseBody
    public ResponseEntity<List<DailyTrendData>> getDailyTrend(@RequestParam(defaultValue = "30") int days) {
        LocalDateTime startDate = LocalDateTime.now().minusDays(days);
        List<Object[]> results = searchLogRepository.countByDateRange(startDate);
        
        List<DailyTrendData> trendData = results.stream()
                .map(row -> {
                    DailyTrendData data = new DailyTrendData();
                    data.setDate(row[0].toString());
                    data.setCount(((Number) row[1]).longValue());
                    return data;
                })
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(trendData);
    }

    /**
     * 인기 검색어
     */
    @GetMapping("/top-keywords")
    @ResponseBody
    public ResponseEntity<List<KeywordStatsData>> getTopKeywords(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "20") int limit) {
        
        LocalDateTime startDate = LocalDateTime.now().minusDays(days);
        Pageable pageable = Pageable.ofSize(limit);
        List<Object[]> results = searchLogRepository.findTopKeywordsWithStats(startDate, pageable);
        
        // 클릭 수 정보도 가져오기
        Map<String, Long> clickCounts = new HashMap<>();
        List<SearchClickLog> recentClicks = searchClickLogRepository.findByCreatedAtBetween(
            startDate, LocalDateTime.now(), Pageable.unpaged()).getContent();
        
        for (var click : recentClicks) {
            String keyword = click.getSearchKeyword();
            clickCounts.put(keyword, clickCounts.getOrDefault(keyword, 0L) + 1);
        }
        
        List<KeywordStatsData> keywordStats = results.stream()
                .map(row -> {
                    KeywordStatsData data = new KeywordStatsData();
                    data.setKeyword((String) row[0]);
                    long searchCount = ((Number) row[1]).longValue();
                    data.setSearchCount(searchCount);
                    data.setAvgResultCount(String.format("%.1f", ((Number) row[2]).doubleValue()));
                    
                    long clicks = clickCounts.getOrDefault((String) row[0], 0L);
                    double ctr = searchCount > 0 ? (double) clicks / searchCount * 100 : 0.0;
                    data.setClickThroughRate(String.format("%.1f", ctr));
                    
                    return data;
                })
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(keywordStats);
    }

    /**
     * 결과 없는 검색어
     */
    @GetMapping("/zero-result-keywords")
    @ResponseBody
    public ResponseEntity<List<ZeroResultData>> getZeroResultKeywords(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "20") int limit) {
        
        LocalDateTime startDate = LocalDateTime.now().minusDays(days);
        Pageable pageable = Pageable.ofSize(limit);
        List<Object[]> results = searchLogRepository.findZeroResultKeywords(startDate, pageable);
        
        List<ZeroResultData> zeroResultData = results.stream()
                .map(row -> {
                    ZeroResultData data = new ZeroResultData();
                    data.setKeyword((String) row[0]);
                    data.setCount(((Number) row[1]).longValue());
                    return data;
                })
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(zeroResultData);
    }

    @Getter
    @Setter
    public static class DailyTrendData {
        private String date;
        private Long count;
    }

    @Getter
    @Setter
    public static class KeywordStatsData {
        private String keyword;
        private Long searchCount;
        private String avgResultCount;
        private String clickThroughRate;
    }

    @Getter
    @Setter
    public static class ZeroResultData {
        private String keyword;
        private Long count;
    }
}
