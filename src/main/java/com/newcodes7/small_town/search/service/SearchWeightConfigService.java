package com.newcodes7.small_town.search.service;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.global.entity.SearchWeightConfig;
import com.newcodes7.small_town.search.repository.SearchWeightConfigRepository;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchWeightConfigService {

    private final SearchWeightConfigRepository repository;

    public record WeightEntry(double titleMultiplier, double bm25NsfWeight, double vectorNsfWeight) {}

    private static final Map<String, WeightEntry> DEFAULTS = Map.of(
        "SIMPLE",   new WeightEntry(3.0, 0.6, 0.4),
        "MODERATE", new WeightEntry(2.0, 0.5, 0.5),
        "COMPLEX",  new WeightEntry(1.0, 0.4, 0.6)
    );

    private volatile Map<String, WeightEntry> cache = new HashMap<>(DEFAULTS);

    @PostConstruct
    public void init() {
        refreshCache();
    }

    public WeightEntry getWeights(SemanticTermExpansionService.QueryComplexity complexity) {
        return cache.getOrDefault(complexity.name(), DEFAULTS.get(complexity.name()));
    }

    public Map<String, WeightEntry> getAllWeights() {
        return Map.copyOf(cache);
    }

    @Transactional
    public void updateWeights(String complexity, double titleMultiplier,
                              double bm25NsfWeight, double vectorNsfWeight, String updatedBy) {
        SearchWeightConfig config = repository.findByComplexity(complexity)
            .orElseThrow(() -> new IllegalArgumentException("Unknown complexity: " + complexity));
        config.setTitleMultiplier(titleMultiplier);
        config.setBm25NsfWeight(bm25NsfWeight);
        config.setVectorNsfWeight(vectorNsfWeight);
        config.setUpdatedBy(updatedBy);
        repository.save(config);
        refreshCache();
        log.info("[검색 가중치] {} 업데이트 완료: title={}, bm25={}, vector={}",
                 complexity, titleMultiplier, bm25NsfWeight, vectorNsfWeight);
    }

    private void refreshCache() {
        try {
            Map<String, WeightEntry> newCache = new HashMap<>(DEFAULTS);
            repository.findAll().forEach(c ->
                newCache.put(c.getComplexity(),
                    new WeightEntry(c.getTitleMultiplier(), c.getBm25NsfWeight(), c.getVectorNsfWeight()))
            );
            this.cache = newCache;
            log.info("[검색 가중치] 캐시 로드 완료: {}", newCache.keySet());
        } catch (Exception e) {
            log.error("[검색 가중치] DB 로드 실패, 기존 캐시 유지: {}", e.getMessage());
        }
    }
}
