package com.newcodes7.small_town.search.service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.embedding.dto.ModelEmbeddingResult;
import com.newcodes7.small_town.embedding.service.EmbeddingApiService;
import com.newcodes7.small_town.search.entity.SearchLog;
import com.newcodes7.small_town.search.entity.SearchQueryEmbedding;
import com.newcodes7.small_town.search.repository.SearchQueryEmbeddingRepository;
import com.newcodes7.small_town.search.repository.SearchLogRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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

    @Transactional
    public CachedEmbeddingResult getEmbeddingWithCacheInfo(String keyword, SearchLog searchLog) {
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
        ModelEmbeddingResult embResult = embeddingApiService.generateEmbedding(keyword, null);
        long embedMs = System.currentTimeMillis() - embedStart;
        if (!embResult.isSuccess() || embResult.getEmbedding() == null) {
            log.warn("검색어 임베딩 생성 실패: {}", embResult.getErrorMessage());
            return CachedEmbeddingResult.miss(null, lookupMs, embedMs);
        }

        float[] embedding = embResult.getEmbedding();
        scheduleSaveAsync(keyword, normalized, embedding, searchLog);
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
