package com.newcodes7.small_town.crawler.integration.analytics;

import com.google.analytics.data.v1beta.BetaAnalyticsDataClient;
import com.google.analytics.data.v1beta.BetaAnalyticsDataSettings;
import com.google.analytics.data.v1beta.DateRange;
import com.google.analytics.data.v1beta.Dimension;
import com.google.analytics.data.v1beta.Filter;
import com.google.analytics.data.v1beta.FilterExpression;
import com.google.analytics.data.v1beta.Metric;
import com.google.analytics.data.v1beta.Row;
import com.google.analytics.data.v1beta.RunReportRequest;
import com.google.analytics.data.v1beta.RunReportResponse;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.global.entity.Corporation;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Analytics Data API를 통한 조회수 동기화 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleAnalyticsService {

    private final CorporationRepository corporationRepository;

    @Value("${google.analytics.property-id}")
    private String propertyId;

    @Value("${google.analytics.credentials-path:}")
    private String credentialsPath;

    @Value("${google.analytics.credentials-json:}")
    private String credentialsJson;

    private BetaAnalyticsDataClient analyticsDataClient;
    private boolean initialized = false;

    // 기업 상세 페이지 URL 패턴: /corporations/{corporationId}
    private static final Pattern CORPORATION_URL_PATTERN = Pattern.compile("^/corporations/(\\d+)$");

    // GA4 데이터 보관 기간 (기본 14개월 = 약 420일)
    private static final int GA_MAX_DATA_RETENTION_DAYS = 420;

    @PostConstruct
    public void init() {
        InputStream credentialsStream = getCredentialsInputStream();
        if (credentialsStream == null) {
            log.warn("Google Analytics credentials가 설정되지 않았습니다. GA 동기화가 비활성화됩니다.");
            return;
        }

        try {
            GoogleCredentials credentials = ServiceAccountCredentials.fromStream(credentialsStream);

            BetaAnalyticsDataSettings settings = BetaAnalyticsDataSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build();

            analyticsDataClient = BetaAnalyticsDataClient.create(settings);
            initialized = true;
            log.info("Google Analytics Data API 클라이언트 초기화 완료 - Property ID: {}", propertyId);
        } catch (IOException e) {
            log.error("Google Analytics 클라이언트 초기화 실패: {}", e.getMessage(), e);
        }
    }

    private InputStream getCredentialsInputStream() {
        // 1. JSON 환경 변수 우선 확인
        if (credentialsJson != null && !credentialsJson.trim().isEmpty()) {
            log.info("GA credentials를 환경 변수(GA_CREDENTIALS_JSON)에서 로드합니다.");
            return new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8));
        }

        // 2. 파일 경로로 fallback
        if (credentialsPath != null && !credentialsPath.trim().isEmpty()) {
            try {
                log.info("GA credentials를 파일 경로({})에서 로드합니다.", credentialsPath);
                return new FileInputStream(credentialsPath);
            } catch (IOException e) {
                log.error("GA credentials 파일을 읽을 수 없습니다: {}", e.getMessage());
            }
        }

        return null;
    }

    /**
     * GA 서비스 활성화 여부 확인
     */
    public boolean isEnabled() {
        return initialized && analyticsDataClient != null;
    }

    /**
     * 기업별 조회수 조회 (지난 30일)
     * @return 기업 ID별 조회수 맵
     */
    public Map<Long, Long> getCorporationPageViews() {
        return getCorporationPageViews(30);
    }

    /**
     * 기업별 조회수 조회
     * @param days 조회 기간 (일)
     * @return 기업 ID별 조회수 맵
     */
    public Map<Long, Long> getCorporationPageViews(int days) {
        Map<Long, Long> corporationViews = new HashMap<>();

        if (!isEnabled()) {
            log.warn("Google Analytics가 비활성화되어 있습니다.");
            return corporationViews;
        }

        try {
            RunReportRequest request = RunReportRequest.newBuilder()
                .setProperty(propertyId)
                .addDateRanges(DateRange.newBuilder()
                    .setStartDate(days + "daysAgo")
                    .setEndDate("today")
                    .build())
                .addDimensions(Dimension.newBuilder()
                    .setName("pagePath")
                    .build())
                .addMetrics(Metric.newBuilder()
                    .setName("screenPageViews")
                    .build())
                .setDimensionFilter(FilterExpression.newBuilder()
                    .setFilter(Filter.newBuilder()
                        .setFieldName("pagePath")
                        .setStringFilter(Filter.StringFilter.newBuilder()
                            .setMatchType(Filter.StringFilter.MatchType.BEGINS_WITH)
                            .setValue("/corporations/")
                            .build())
                        .build())
                    .build())
                .build();

            RunReportResponse response = analyticsDataClient.runReport(request);

            for (Row row : response.getRowsList()) {
                String pagePath = row.getDimensionValues(0).getValue();
                long pageViews = Long.parseLong(row.getMetricValues(0).getValue());

                // URL에서 기업 ID 추출
                Matcher matcher = CORPORATION_URL_PATTERN.matcher(pagePath);
                if (matcher.matches()) {
                    Long corporationId = Long.parseLong(matcher.group(1));
                    corporationViews.merge(corporationId, pageViews, Long::sum);
                }
            }

            log.info("GA 기업 조회수 조회 완료 - 기업 수: {}, 기간: {}일", corporationViews.size(), days);

        } catch (Exception e) {
            log.error("GA 기업 조회수 조회 실패: {}", e.getMessage(), e);
        }

        return corporationViews;
    }

    /**
     * 모든 기업의 조회수 동기화
     * @return 동기화된 기업 수
     */
    @Transactional
    public int syncAllCorporationViewCounts() {
        return syncAllCorporationViewCounts(30);
    }

    /**
     * 모든 기업의 조회수 동기화
     * @param days 조회 기간 (일)
     * @return 동기화된 기업 수
     */
    @Transactional
    public int syncAllCorporationViewCounts(int days) {
        if (!isEnabled()) {
            log.warn("Google Analytics가 비활성화되어 있어 동기화를 건너뜁니다.");
            return 0;
        }

        log.info("기업 조회수 GA 동기화 시작 - 기간: {}일", days);

        Map<Long, Long> gaViewCounts = getCorporationPageViews(days);
        int syncCount = 0;

        for (Map.Entry<Long, Long> entry : gaViewCounts.entrySet()) {
            Long corporationId = entry.getKey();
            Long viewCount = entry.getValue();

            try {
                Corporation corporation = corporationRepository.findById(corporationId).orElse(null);
                if (corporation != null && corporation.getDeletedAt() == null) {
                    corporation.setViewCount(viewCount.intValue());
                    corporationRepository.save(corporation);
                    syncCount++;
                    log.debug("기업 조회수 동기화 - ID: {}, 이름: {}, 조회수: {}",
                            corporationId, corporation.getName(), viewCount);
                }
            } catch (Exception e) {
                log.error("기업 ID {} 조회수 동기화 실패: {}", corporationId, e.getMessage());
            }
        }

        log.info("기업 조회수 GA 동기화 완료 - 동기화된 기업: {}개", syncCount);
        return syncCount;
    }

    /**
     * 전체 기간 조회수 동기화 (GA4 데이터 보관 기간 전체)
     * @return 동기화된 기업 수
     */
    @Transactional
    public int syncAllTimeViewCounts() {
        log.info("전체 기간 GA 조회수 동기화 시작 ({}일)", GA_MAX_DATA_RETENTION_DAYS);
        return syncAllCorporationViewCounts(GA_MAX_DATA_RETENTION_DAYS);
    }

    /**
     * 어제 하루 조회수를 기존 조회수에 추가 (일별 증분 동기화)
     * @return 동기화된 기업 수
     */
    @Transactional
    public int syncDailyViewCounts() {
        if (!isEnabled()) {
            log.warn("Google Analytics가 비활성화되어 있어 일별 동기화를 건너뜁니다.");
            return 0;
        }

        log.info("일별 GA 조회수 동기화 시작 (어제 데이터)");

        // 어제 하루 조회수 조회
        Map<Long, Long> yesterdayViews = getYesterdayPageViews();
        int syncCount = 0;

        for (Map.Entry<Long, Long> entry : yesterdayViews.entrySet()) {
            Long corporationId = entry.getKey();
            Long dailyViewCount = entry.getValue();

            try {
                Corporation corporation = corporationRepository.findById(corporationId).orElse(null);
                if (corporation != null && corporation.getDeletedAt() == null) {
                    // 기존 조회수에 어제 조회수 추가
                    int currentViewCount = corporation.getViewCount() != null ? corporation.getViewCount() : 0;
                    corporation.setViewCount(currentViewCount + dailyViewCount.intValue());
                    corporationRepository.save(corporation);
                    syncCount++;
                    log.debug("기업 일별 조회수 추가 - ID: {}, 이름: {}, 추가: +{}, 총: {}",
                            corporationId, corporation.getName(), dailyViewCount, corporation.getViewCount());
                }
            } catch (Exception e) {
                log.error("기업 ID {} 일별 조회수 동기화 실패: {}", corporationId, e.getMessage());
            }
        }

        log.info("일별 GA 조회수 동기화 완료 - 동기화된 기업: {}개", syncCount);
        return syncCount;
    }

    /**
     * 어제 하루 조회수 조회
     * @return 기업 ID별 어제 조회수 맵
     */
    private Map<Long, Long> getYesterdayPageViews() {
        Map<Long, Long> corporationViews = new HashMap<>();

        if (!isEnabled()) {
            return corporationViews;
        }

        try {
            RunReportRequest request = RunReportRequest.newBuilder()
                .setProperty(propertyId)
                .addDateRanges(DateRange.newBuilder()
                    .setStartDate("yesterday")
                    .setEndDate("yesterday")
                    .build())
                .addDimensions(Dimension.newBuilder()
                    .setName("pagePath")
                    .build())
                .addMetrics(Metric.newBuilder()
                    .setName("screenPageViews")
                    .build())
                .setDimensionFilter(FilterExpression.newBuilder()
                    .setFilter(Filter.newBuilder()
                        .setFieldName("pagePath")
                        .setStringFilter(Filter.StringFilter.newBuilder()
                            .setMatchType(Filter.StringFilter.MatchType.BEGINS_WITH)
                            .setValue("/corporations/")
                            .build())
                        .build())
                    .build())
                .build();

            RunReportResponse response = analyticsDataClient.runReport(request);

            for (Row row : response.getRowsList()) {
                String pagePath = row.getDimensionValues(0).getValue();
                long pageViews = Long.parseLong(row.getMetricValues(0).getValue());

                Matcher matcher = CORPORATION_URL_PATTERN.matcher(pagePath);
                if (matcher.matches()) {
                    Long corporationId = Long.parseLong(matcher.group(1));
                    corporationViews.merge(corporationId, pageViews, Long::sum);
                }
            }

            log.info("어제 GA 조회수 조회 완료 - 기업 수: {}", corporationViews.size());

        } catch (Exception e) {
            log.error("어제 GA 조회수 조회 실패: {}", e.getMessage(), e);
        }

        return corporationViews;
    }

    /**
     * 특정 기업의 조회수 조회
     * @param corporationId 기업 ID
     * @param days 조회 기간 (일)
     * @return 조회수
     */
    public long getCorporationPageViews(Long corporationId, int days) {
        if (!isEnabled()) {
            return 0;
        }

        try {
            String pagePath = "/corporations/" + corporationId;

            RunReportRequest request = RunReportRequest.newBuilder()
                .setProperty(propertyId)
                .addDateRanges(DateRange.newBuilder()
                    .setStartDate(days + "daysAgo")
                    .setEndDate("today")
                    .build())
                .addDimensions(Dimension.newBuilder()
                    .setName("pagePath")
                    .build())
                .addMetrics(Metric.newBuilder()
                    .setName("screenPageViews")
                    .build())
                .setDimensionFilter(FilterExpression.newBuilder()
                    .setFilter(Filter.newBuilder()
                        .setFieldName("pagePath")
                        .setStringFilter(Filter.StringFilter.newBuilder()
                            .setMatchType(Filter.StringFilter.MatchType.EXACT)
                            .setValue(pagePath)
                            .build())
                        .build())
                    .build())
                .build();

            RunReportResponse response = analyticsDataClient.runReport(request);

            if (!response.getRowsList().isEmpty()) {
                return Long.parseLong(response.getRowsList().get(0).getMetricValues(0).getValue());
            }
        } catch (Exception e) {
            log.error("기업 ID {} GA 조회수 조회 실패: {}", corporationId, e.getMessage());
        }

        return 0;
    }
}
