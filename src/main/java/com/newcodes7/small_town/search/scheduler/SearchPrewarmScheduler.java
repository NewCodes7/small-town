package com.newcodes7.small_town.search.scheduler;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.newcodes7.small_town.embedding.service.EmbeddingApiService;
import com.newcodes7.small_town.search.repository.SearchQueryEmbeddingRepository;
import com.newcodes7.small_town.search.service.ArticleSearchService;
import com.newcodes7.small_town.search.service.AutocompleteService;
import com.newcodes7.small_town.search.service.SearchQueryEmbeddingService;
import com.newcodes7.small_town.search.service.SemanticTermExpansionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 검색 cold start 방지 스케줄러
 *
 * 문제: 검색 idle 후 첫 요청 시 ~4초 소요 (JIT, DB 커넥션, Lucene, HNSW cold start)
 * 해결: 5분마다 전체 검색 경로를 prewarm하고, 4분마다 Clova API HTTP 커넥션을 유지
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SearchPrewarmScheduler {

    private static final String FALLBACK_KEYWORD = "스프링";

    private final AutocompleteService autocompleteService;
    private final SemanticTermExpansionService semanticExpansionService;
    private final ArticleSearchService articleSearchService;
    private final EmbeddingApiService embeddingApiService;
    private final SearchQueryEmbeddingService searchQueryEmbeddingService;
    private final SearchQueryEmbeddingRepository searchQueryEmbeddingRepository;

    private final AtomicReference<String> warmKeyword = new AtomicReference<>(null);

    private final AtomicLong prewarmSuccess = new AtomicLong(0);
    private final AtomicLong prewarmFailure = new AtomicLong(0);
    private final AtomicLong keepaliveSuccess = new AtomicLong(0);
    private final AtomicLong keepaliveFailure = new AtomicLong(0);

    /**
     * 앱 기동 직후 즉시 1회 실행 — warm 키워드 초기화 + 검색 경로 prewarm
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        initWarmKeyword();
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
     */
    @Scheduled(fixedDelay = 300_000)
    public void prewarmSearchPipeline() {
        String keyword = warmKeyword.get();
        if (keyword == null) {
            keyword = FALLBACK_KEYWORD;
        }

        try {
            log.debug("[Prewarm] 검색 파이프라인 prewarm 시작: keyword='{}'", keyword);
            long start = System.currentTimeMillis();

            // Step 0. 임베딩 조회 경로 prewarm
            // warm 키워드는 search_query_embedding에 캐시된 키워드 → DB 캐시 히트 → Clova API 호출 없음
            // SearchQueryEmbeddingService → findByNormalizedKeyword → embedding 반환 경로를 JIT-warm
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
        }
    }

    /**
     * 4분마다 Clova API TCP+TLS 커넥션 keepalive
     *
     * Apache HttpClient 5 풀 keepAlive(270초)와 조합하여 5분 prewarm 시점에도
     * TCP 재연결 없이 커넥션 재사용 가능.
     * 최소 텍스트("가", 1토큰)로 호출하여 API 비용 최소화.
     */
    @Scheduled(fixedDelay = 240_000)
    public void keepaliveClovaConnection() {
        try {
            log.debug("[Prewarm] Clova API 커넥션 keepalive 실행");
            embeddingApiService.generateEmbedding("가", null);
            keepaliveSuccess.incrementAndGet();
        } catch (Exception e) {
            log.warn("[Prewarm] Clova API keepalive 실패 (무시): {}", e.getMessage());
            keepaliveFailure.incrementAndGet();
        }
    }

    /**
     * 매시각 30분 — 스케줄러 동작 상태 및 지난 1시간 성공/실패 횟수 로그 후 카운터 초기화
     */
    @Scheduled(cron = "0 30 * * * *")
    public void logHealthCheck() {
        long ps = prewarmSuccess.getAndSet(0);
        long pf = prewarmFailure.getAndSet(0);
        long ks = keepaliveSuccess.getAndSet(0);
        long kf = keepaliveFailure.getAndSet(0);
        log.info("[Prewarm] 스케줄러 동작 중 — warm 키워드: '{}' | prewarm 성공: {}, 실패: {} | keepalive 성공: {}, 실패: {}",
                warmKeyword.get(), ps, pf, ks, kf);
    }

    /**
     * search_query_embedding 테이블에서 임베딩이 있는 레코드 1개를 warm 키워드로 사용.
     * 없으면 한국어 fallback "스프링" 사용.
     */
    private void initWarmKeyword() {
        try {
            searchQueryEmbeddingRepository.findFirstByEmbeddingIsNotNull()
                    .ifPresentOrElse(
                            entry -> warmKeyword.set(entry.getSearchKeyword()),
                            () -> warmKeyword.set(FALLBACK_KEYWORD)
                    );
            log.debug("[Prewarm] warm 키워드 초기화: '{}'", warmKeyword.get());
        } catch (Exception e) {
            log.warn("[Prewarm] warm 키워드 초기화 실패, fallback 사용: {}", e.getMessage());
            warmKeyword.set(FALLBACK_KEYWORD);
        }
    }
}
