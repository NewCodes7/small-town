package com.newcodes7.small_town.global.service;

import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.SearchClickLog;
import com.newcodes7.small_town.global.entity.SearchLog;
import com.newcodes7.small_town.global.entity.Video;
import com.newcodes7.small_town.global.repository.SearchClickLogRepository;
import com.newcodes7.small_town.global.repository.SearchLogRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SearchLogService {

    private final SearchLogRepository searchLogRepository;
    private final SearchClickLogRepository searchClickLogRepository;
    private final EntityManager entityManager;

    /**
     * 검색 로그 저장 (비동기)
     */
    @Async
    @Transactional
    public void logSearch(String keyword, SearchLog.SearchType searchType, HttpServletRequest request) {
        try {
            String ipAddress = getClientIp(request);
            String userAgent = request.getHeader("User-Agent");

            SearchLog searchLog = SearchLog.builder()
                    .searchKeyword(keyword)
                    .searchType(searchType)
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .build();

            searchLogRepository.save(searchLog);
        } catch (Exception e) {
            log.error("검색 로그 저장 실패: keyword={}, type={}", keyword, searchType, e);
        }
    }

    /**
     * 검색 로그 저장 (사용자 정보 포함, 비동기)
     */
    @Async
    @Transactional
    public void logSearch(String keyword, SearchLog.SearchType searchType, Long targetId, User user, HttpServletRequest request) {
        try {
            String ipAddress = getClientIp(request);
            String userAgent = request.getHeader("User-Agent");

            SearchLog searchLog = SearchLog.builder()
                    .searchKeyword(keyword)
                    .searchType(searchType)
                    .targetId(targetId)
                    .user(user)
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .build();

            searchLogRepository.save(searchLog);
        } catch (Exception e) {
            log.error("검색 로그 저장 실패: keyword={}, type={}, targetId={}", keyword, searchType, targetId, e);
        }
    }

    /**
     * 클라이언트 IP 주소 가져오기
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    /**
     * 전체 검색 로그 조회
     */
    public Page<SearchLog> getAllSearchLogs(Pageable pageable) {
        return searchLogRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    /**
     * 검색 타입별 로그 조회
     */
    public Page<SearchLog> getSearchLogsByType(SearchLog.SearchType searchType, Pageable pageable) {
        return searchLogRepository.findBySearchType(searchType, pageable);
    }

    /**
     * 특정 기간 검색 로그 조회
     */
    public Page<SearchLog> getSearchLogsByPeriod(LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return searchLogRepository.findByCreatedAtBetween(startDate, endDate, pageable);
    }

    /**
     * 인기 검색어 조회 (특정 타입, 최근 7일)
     */
    public Map<String, Long> getTopKeywords(SearchLog.SearchType searchType, int limit) {
        LocalDateTime startDate = LocalDateTime.now().minusDays(7);
        Pageable pageable = Pageable.ofSize(limit);

        List<Object[]> results = searchLogRepository.findTopKeywords(searchType, startDate, pageable);

        return results.stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> (Long) row[1],
                        (v1, v2) -> v1,
                        LinkedHashMap::new
                ));
    }

    /**
     * 전체 인기 검색어 조회 (최근 7일)
     */
    public Map<String, Long> getAllTopKeywords(int limit) {
        LocalDateTime startDate = LocalDateTime.now().minusDays(7);
        Pageable pageable = Pageable.ofSize(limit);

        List<Object[]> results = searchLogRepository.findAllTopKeywords(startDate, pageable);

        return results.stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> (Long) row[1],
                        (v1, v2) -> v1,
                        LinkedHashMap::new
                ));
    }

    /**
     * 강화된 검색 로그 저장 (비동기, SearchLog ID 반환)
     */
    @Async
    @Transactional
    public CompletableFuture<Long> logSearchEnhanced(
            String keyword, SearchLog.SearchType searchType, Long targetId, User user,
            HttpServletRequest request, Integer resultCount, Long searchDurationMs,
            Integer pageNumber, String sortType, String filters, String sessionId) {
        try {
            String ipAddress = getClientIp(request);
            String userAgent = request.getHeader("User-Agent");

            SearchLog searchLog = SearchLog.builder()
                    .searchKeyword(keyword)
                    .searchType(searchType)
                    .targetId(targetId)
                    .user(user)
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .resultCount(resultCount)
                    .searchDurationMs(searchDurationMs)
                    .pageNumber(pageNumber)
                    .sortType(sortType)
                    .filters(filters)
                    .sessionId(sessionId)
                    .build();

            SearchLog saved = searchLogRepository.save(searchLog);
            return CompletableFuture.completedFuture(saved.getId());
        } catch (Exception e) {
            log.error("강화된 검색 로그 저장 실패: keyword={}, type={}", keyword, searchType, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * 검색 클릭 로그 저장 (비동기)
     */
    @Async
    @Transactional
    public void logSearchClick(Long searchLogId, Long articleId, Long videoId, Integer clickPosition,
                                String searchKeyword, User user, String ipAddress) {
        try {
            SearchLog searchLog = searchLogId != null ? searchLogRepository.findById(searchLogId).orElse(null) : null;
            Article article = articleId != null ? entityManager.getReference(Article.class, articleId) : null;
            Video video = videoId != null ? entityManager.getReference(Video.class, videoId) : null;
            
            SearchClickLog clickLog = SearchClickLog.builder()
                    .searchLog(searchLog)
                    .article(article)
                    .video(video)
                    .clickPosition(clickPosition)
                    .searchKeyword(searchKeyword)
                    .user(user)
                    .ipAddress(ipAddress)
                    .build();

            searchClickLogRepository.save(clickLog);
        } catch (Exception e) {
            log.error("검색 클릭 로그 저장 실패: searchLogId={}, articleId={}, videoId={}", 
                      searchLogId, articleId, videoId, e);
        }
    }

    /**
     * 검색 품질 통계 조회
     */
    public SearchQualityStats getSearchQualityStats(int days) {
        LocalDateTime startDate = LocalDateTime.now().minusDays(days);

        long totalSearches = searchLogRepository.countByCreatedAtGreaterThanEqual(startDate);
        long uniqueKeywords = searchLogRepository.countDistinctKeywords(startDate);
        Double avgResultCount = searchLogRepository.getAverageResultCount(startDate);
        Double avgSearchDuration = searchLogRepository.getAverageSearchDuration(startDate);
        
        long totalClicks = searchClickLogRepository.countByCreatedAtGreaterThanEqual(startDate);
        double clickThroughRate = totalSearches > 0 ? (double) totalClicks / totalSearches * 100 : 0.0;

        // 결과 없는 검색어 수 계산 - 집계 쿼리 사용
        long zeroResultCount = searchLogRepository.countByResultCountAndCreatedAtGreaterThanEqual(0, startDate);
        double zeroResultRate = totalSearches > 0 ? (double) zeroResultCount / totalSearches * 100 : 0.0;

        return SearchQualityStats.builder()
                .totalSearches(totalSearches)
                .uniqueKeywords(uniqueKeywords)
                .averageResultCount(avgResultCount != null ? avgResultCount : 0.0)
                .averageSearchDurationMs(avgSearchDuration != null ? avgSearchDuration : 0.0)
                .clickThroughRate(clickThroughRate)
                .zeroResultRate(zeroResultRate)
                .build();
    }

    /**
     * 검색 품질 통계 DTO
     */
    @Getter
    @Builder
    public static class SearchQualityStats {
        private long totalSearches;
        private long uniqueKeywords;
        private double averageResultCount;
        private double averageSearchDurationMs;
        private double clickThroughRate;
        private double zeroResultRate;
    }
}
