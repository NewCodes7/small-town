package com.newcodes7.small_town.search.scheduler;

import com.newcodes7.small_town.global.logging.PrewarmLogFilter;
import com.newcodes7.small_town.search.service.ArticleSearchService;
import com.newcodes7.small_town.search.service.AutocompleteService;
import com.newcodes7.small_town.search.service.SearchQueryEmbeddingService;
import com.newcodes7.small_town.search.service.SemanticTermExpansionService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 검색 cold start 방지 스케줄러
 *
 * 문제: 검색 idle 후 첫 요청 시 ~4초 소요 (JIT, DB 커넥션, Lucene, HNSW cold start)
 * 해결: 5분마다 전체 검색 경로를 prewarm
 */
@Component
// 테스트에서는 비활성화 필요: 컨텍스트 기동마다 ApplicationReady prewarm이 실행되어
// 같은 DB를 쓰는 다른 테스트 컨텍스트의 create-drop DDL과 락 교착을 일으킴
@ConditionalOnProperty(name = "search.prewarm.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class SearchPrewarmScheduler {

    private static final List<String> WARM_KEYWORDS = List.of(
            // 단일 키워드 — 자동완성·형태소 분석 경로 warm
            "스프링",
            "redis",
            "kafka",
            "쿠버네티스",
            "도커",
            "elasticsearch",
            "React",
            "TypeScript",
            "Python",
            "Kotlin",
            // 복합 키워드 — 하이브리드 검색·NSF 리랭킹 경로 warm
            "JPA N+1 문제 해결",
            "Kafka 컨슈머 그룹 리밸런싱",
            "Spring Boot 멀티모듈 구성",
            "쿠버네티스 HPA 오토스케일링",
            "MSA 분산 트랜잭션 처리",
            "Redis 캐시 무효화 전략",
            "React Query 서버 상태 관리",
            "TypeScript 제네릭 고급 활용",
            "Docker Compose 로컬 개발 환경",
            "ElasticSearch 인덱스 성능 최적화",
            "GitHub Actions CI/CD 파이프라인",
            "Spring WebFlux 리액티브 프로그래밍",
            "gRPC vs REST API 비교",
            "JWT 토큰 갱신 보안 전략",
            "데이터베이스 샤딩 파티셔닝",
            "모놀리스에서 MSA로 전환",
            "Kotlin 코루틴 동시성 처리",
            "클린 아키텍처 도메인 설계",
            "머신러닝 모델 서빙 배포",
            "서킷브레이커 패턴 구현"
    );

    private final AtomicInteger keywordIndex = new AtomicInteger(0);

    private final AutocompleteService autocompleteService;
    private final SemanticTermExpansionService semanticExpansionService;
    private final ArticleSearchService articleSearchService;
    private final SearchQueryEmbeddingService searchQueryEmbeddingService;

    private final AtomicLong prewarmSuccess = new AtomicLong(0);
    private final AtomicLong prewarmFailure = new AtomicLong(0);

    /**
     * 앱 기동 직후 즉시 1회 실행
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        prewarmSearchPipeline();
    }

    /**
     * 5분마다 전체 검색 시나리오 prewarm
     *
     * Step 0. 임베딩 조회 경로 → search_query_embedding DB 캐시 히트 (Clova API 호출 없음)
     * Step 1. 자동완성 → autocompleteExecutor 스레드 + Corp/Term/Theme covering index warm
     * Step 2. 검색어 확장 → Lucene/Nori JIT + termRepository warm
     * Step 3. 하이브리드 검색 → binary HNSW, halfvec reranking, BM25 pg_search warm
     *
     * ArticleSearchService를 직접 호출하므로 SearchLogService를 거치지 않아 search_log 미저장 보장.
     * 매 실행마다 WARM_KEYWORDS에서 순서대로 키워드를 순환하여 다양한 경로를 warm.
     */
    @Scheduled(fixedDelay = 300_000)
    public void prewarmSearchPipeline() {
        int idx = keywordIndex.getAndUpdate(i -> (i + 1) % WARM_KEYWORDS.size());
        String keyword = WARM_KEYWORDS.get(idx);

        MDC.put(PrewarmLogFilter.MDC_KEY, "true");
        try {
            log.debug("[Prewarm] 검색 파이프라인 prewarm 시작: keyword='{}'", keyword);
            long start = System.currentTimeMillis();

            // Step 0. 임베딩 조회 경로 prewarm (DB 캐시 히트 경로 JIT-warm, Clova API 호출 없음)
            searchQueryEmbeddingService.getEmbeddingWithCacheInfo(keyword, null);

            // Step 1. 자동완성
            String autocompleteQuery = keyword.length() >= 2 ? keyword.substring(0, 2) : keyword;
            autocompleteService.getAutocompleteSuggestions(autocompleteQuery);

            // Step 2. 검색어 확장
            Map<String, Double> expandedTerms = semanticExpansionService.expandSearchTerms(keyword);

            // Step 3. 하이브리드 검색
            articleSearchService.searchArticlesHybrid(
                    keyword, expandedTerms, null, null, 0, 5, "relevance", null, null);

            log.debug("[Prewarm] 검색 파이프라인 prewarm 완료: {}ms", System.currentTimeMillis() - start);
            prewarmSuccess.incrementAndGet();
        } catch (Exception e) {
            log.warn("[Prewarm] 검색 파이프라인 prewarm 실패 (무시): {}", e.getMessage());
            prewarmFailure.incrementAndGet();
        } finally {
            MDC.remove(PrewarmLogFilter.MDC_KEY);
        }
    }

    /**
     * 매시각 30분 — 스케줄러 동작 상태 및 지난 1시간 성공/실패 횟수 로그 후 카운터 초기화
     */
    @Scheduled(cron = "0 30 * * * *")
    public void logHealthCheck() {
        long ps = prewarmSuccess.getAndSet(0);
        long pf = prewarmFailure.getAndSet(0);
        String nextKeyword = WARM_KEYWORDS.get(keywordIndex.get() % WARM_KEYWORDS.size());
        log.info("[Prewarm] 스케줄러 동작 중 — 다음 키워드: '{}' | prewarm 성공: {}, 실패: {}",
                nextKeyword, ps, pf);
    }
}
