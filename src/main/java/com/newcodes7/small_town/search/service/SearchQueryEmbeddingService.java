package com.newcodes7.small_town.search.service;

import com.newcodes7.small_town.embedding.dto.ModelEmbeddingResult;
import com.newcodes7.small_town.embedding.service.EmbeddingApiService;
import com.newcodes7.small_town.search.entity.SearchLog;
import com.newcodes7.small_town.search.entity.SearchQueryEmbedding;
import com.newcodes7.small_town.search.repository.SearchLogRepository;
import com.newcodes7.small_town.search.repository.SearchQueryEmbeddingRepository;
import io.micrometer.observation.annotation.Observed;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchQueryEmbeddingService {

    private final SearchQueryEmbeddingRepository repository;
    private final EmbeddingApiService embeddingApiService;
    @Qualifier("searchExecutor")
    private final ExecutorService searchExecutor;
    private final SearchLogRepository searchLogRepository;

    @Transactional(readOnly = true)
    public Optional<SearchQueryEmbedding> findByKeyword(String keyword) {
        String normalized = normalizeKeyword(keyword);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return repository.findByNormalizedKeyword(normalized);
    }

    @Transactional
    public float[] getOrCreateEmbedding(String keyword) {
        return getEmbeddingWithCacheInfo(keyword, null).embedding;
    }

    @Transactional
    public float[] getOrCreateEmbedding(String keyword, SearchLog searchLog) {
        return getEmbeddingWithCacheInfo(keyword, searchLog).embedding;
    }

    @Observed(name = "search.query-embedding", contextualName = "query-embedding")
    public CachedEmbeddingResult getEmbeddingWithCacheInfo(String keyword, SearchLog searchLog) {
        return getEmbeddingWithCacheInfo(keyword, searchLog, false);
    }

    /**
     * useMockEmbedding=true(부하테스트 경로)면 실 Clova 대신 mock 엔드포인트로 호출하고,
     * mock 벡터가 실사용자 검색에 서빙되지 않도록 DB 캐시(search_query_embedding) 저장을 건너뛴다.
     * DB lookup은 그대로 수행해 실측과 동일한 조회 부하를 유지한다.
     *
     * 의도적으로 @Transactional 미사용: 이전에는 이 메서드 전체가 한 트랜잭션이라 캐시 미스 시
     * findByNormalizedKeyword가 잡은 HikariCP 커넥션(5개뿐)을 그 아래 Clova 동기 HTTP 호출이 끝날
     * 때까지 계속 붙잡고 있었다(2026-08-06 하이브리드 검색 ramp 부하테스트 병목 분석 참고,
     * load-test/results/2026-08-06-search-ramp-limit-finder.md). @Transactional을 떼면
     * findByNormalizedKeyword 호출은 Spring Data JPA(SimpleJpaRepository)가 자체적으로 여는
     * 짧은 read-only 트랜잭션 안에서 커넥션을 즉시 반납하고, 그 뒤의 Clova 호출은 커넥션을
     * 전혀 점유하지 않은 채 진행된다. OSIV(open-in-view, 기본 true)가 켜져 있어 캐시 히트 시
     * cached.get().getSearchLog()의 지연로딩 접근도 세션이 살아있어 안전하다.
     */
    public CachedEmbeddingResult getEmbeddingWithCacheInfo(String keyword, SearchLog searchLog, boolean useMockEmbedding) {
        String normalized = normalizeKeyword(keyword);
        if (normalized.isEmpty()) {
            return CachedEmbeddingResult.empty();
        }

        long lookupStart = System.currentTimeMillis();
        Optional<SearchQueryEmbedding> cached = repository.findByNormalizedKeyword(normalized);
        long lookupMs = System.currentTimeMillis() - lookupStart;
        if (cached.isPresent() && cached.get().getEmbedding() != null) {
            if (searchLog != null && cached.get().getSearchLog() == null) {
                updateSearchLogAsync(cached.get(), searchLog);
            }
            return CachedEmbeddingResult.hit(cached.get().getEmbedding(), lookupMs);
        }

        long embedStart = System.currentTimeMillis();
        ModelEmbeddingResult embResult = embeddingApiService.generateEmbedding(keyword, null, useMockEmbedding);
        long embedMs = System.currentTimeMillis() - embedStart;
        if (!embResult.isSuccess() || embResult.getEmbedding() == null) {
            log.warn("검색어 임베딩 생성 실패: {}", embResult.getErrorMessage());
            return CachedEmbeddingResult.miss(null, lookupMs, embedMs);
        }

        float[] embedding = embResult.getEmbedding();
        if (!useMockEmbedding) {
            scheduleSaveAsync(keyword, normalized, embedding, searchLog);
        }
        return CachedEmbeddingResult.miss(embedding, lookupMs, embedMs);
    }

    @Transactional
    public void updateSearchLogIfExists(String keyword, SearchLog searchLog) {
        if (searchLog == null) {
            return;
        }
        String normalized = normalizeKeyword(keyword);
        if (normalized.isEmpty()) {
            return;
        }
        repository.findByNormalizedKeyword(normalized)
                .ifPresent(entry -> updateSearchLogAsync(entry, searchLog));
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return "";
        }
        String trimmed = keyword.trim().toLowerCase();
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.replaceAll("\\s+", " ");
    }

    private void scheduleSaveAsync(String keyword, String normalized, float[] embedding, SearchLog searchLog) {
        CompletableFuture.runAsync(() -> persistEmbedding(keyword, normalized, embedding, searchLog), searchExecutor)
                .exceptionally(ex -> {
                    log.warn("검색어 임베딩 비동기 저장 실패: {}", ex.getMessage());
                    return null;
                });
    }

    private void persistEmbedding(String keyword, String normalized, float[] embedding, SearchLog searchLog) {
        try {
            repository.upsertEmbedding(keyword, normalized, toVectorString(embedding), LocalDateTime.now());

            SearchLog resolvedLog = searchLog != null ? searchLog
                    : searchLogRepository.findFirstBySearchKeywordOrderByCreatedAtDesc(keyword).orElse(null);
            if (resolvedLog != null) {
                SearchLog finalLog = resolvedLog;
                repository.findByNormalizedKeyword(normalized).ifPresent(entry -> {
                    entry.setSearchLog(finalLog);
                    repository.save(entry);
                });
            }
        } catch (Exception e) {
            log.warn("검색어 임베딩 저장 예외 (무시): {}", e.getMessage());
        }
    }

    private String toVectorString(float[] array) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < array.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(array[i]);
        }
        return sb.append("]").toString();
    }

    private void updateSearchLogAsync(SearchQueryEmbedding entry, SearchLog searchLog) {
        CompletableFuture.runAsync(() -> {
            try {
                entry.setSearchLog(searchLog);
                repository.save(entry);
            } catch (Exception e) {
                log.warn("검색어 임베딩 검색 로그 업데이트 실패: {}", e.getMessage());
            }
        }, searchExecutor);
    }

    public static class CachedEmbeddingResult {
        private final float[] embedding;
        private final boolean cacheHit;
        private final long cacheLookupMs;
        private final long embeddingMs;

        private CachedEmbeddingResult(float[] embedding, boolean cacheHit, long cacheLookupMs, long embeddingMs) {
            this.embedding = embedding;
            this.cacheHit = cacheHit;
            this.cacheLookupMs = cacheLookupMs;
            this.embeddingMs = embeddingMs;
        }

        public static CachedEmbeddingResult hit(float[] embedding, long cacheLookupMs) {
            return new CachedEmbeddingResult(embedding, true, cacheLookupMs, 0);
        }

        public static CachedEmbeddingResult miss(float[] embedding, long cacheLookupMs, long embeddingMs) {
            return new CachedEmbeddingResult(embedding, false, cacheLookupMs, embeddingMs);
        }

        public static CachedEmbeddingResult empty() {
            return new CachedEmbeddingResult(null, false, 0, 0);
        }

        public float[] getEmbedding() {
            return embedding;
        }

        public boolean isCacheHit() {
            return cacheHit;
        }

        public long getCacheLookupMs() {
            return cacheLookupMs;
        }

        public long getEmbeddingMs() {
            return embeddingMs;
        }
    }
}
