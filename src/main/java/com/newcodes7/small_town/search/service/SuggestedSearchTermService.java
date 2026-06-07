package com.newcodes7.small_town.search.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.global.entity.SuggestedSearchTerm;
import com.newcodes7.small_town.search.repository.SuggestedSearchTermRepository;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class SuggestedSearchTermService {

    private static final int MAX_TERMS = 8;

    private final SuggestedSearchTermRepository repository;

    private volatile List<String> cache = List.of();

    @PostConstruct
    public void init() {
        refreshCache();
    }

    public List<String> getActiveKeywords() {
        return cache;
    }

    public List<SuggestedSearchTerm> getAll() {
        return repository.findAll();
    }

    @Transactional
    public void updateKeywords(List<String> keywords) {
        List<String> sanitized = keywords.stream()
            .map(String::trim)
            .filter(k -> !k.isEmpty())
            .limit(MAX_TERMS)
            .toList();

        repository.deleteAll();
        for (int i = 0; i < sanitized.size(); i++) {
            repository.save(SuggestedSearchTerm.builder()
                .keyword(sanitized.get(i))
                .displayOrder(i)
                .active(true)
                .build());
        }
        refreshCache();
        log.info("[추천 검색어] 업데이트 완료: {}개", sanitized.size());
    }

    private void refreshCache() {
        try {
            List<String> loaded = repository.findByActiveTrueOrderByDisplayOrderAsc()
                .stream()
                .map(SuggestedSearchTerm::getKeyword)
                .toList();
            this.cache = loaded;
        } catch (Exception e) {
            log.error("[추천 검색어] DB 로드 실패, 기존 캐시 유지: {}", e.getMessage());
        }
    }
}
