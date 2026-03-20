package com.newcodes7.small_town.search.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.newcodes7.small_town.article.dto.TermAutocompleteDto;
import com.newcodes7.small_town.article.repository.TermAutocompleteJpaRepository;
import com.newcodes7.small_town.article.repository.TermAutocompleteJpaRepository.TermAutocompleteProjection;
import com.newcodes7.small_town.corporation.repository.CorporationAutocompleteJpaRepository;
import com.newcodes7.small_town.corporation.repository.CorporationAutocompleteJpaRepository.CorporationAutocompleteProjection;
import com.newcodes7.small_town.corporation.dto.CorporationAutocompleteDto;
import com.newcodes7.small_town.theme.repository.ThemeAutocompleteJpaRepository;
import com.newcodes7.small_town.theme.repository.ThemeAutocompleteJpaRepository.ThemeAutocompleteProjection;
import com.newcodes7.small_town.theme.dto.ThemeAutocompleteDto;
import com.newcodes7.small_town.global.util.KoreanCharacterUtil;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class AutocompleteService {

    private final ExecutorService autocompleteExecutor;
    private final TermAutocompleteJpaRepository termAutocompleteJpaRepository;
    private final CorporationAutocompleteJpaRepository corporationAutocompleteJpaRepository;
    private final ThemeAutocompleteJpaRepository themeAutocompleteJpaRepository;

    public AutocompleteService(
            @Qualifier("autocompleteExecutor") ExecutorService autocompleteExecutor,
            TermAutocompleteJpaRepository termAutocompleteJpaRepository,
            CorporationAutocompleteJpaRepository corporationAutocompleteJpaRepository,
            ThemeAutocompleteJpaRepository themeAutocompleteJpaRepository) {
        this.autocompleteExecutor = autocompleteExecutor;
        this.termAutocompleteJpaRepository = termAutocompleteJpaRepository;
        this.corporationAutocompleteJpaRepository = corporationAutocompleteJpaRepository;
        this.themeAutocompleteJpaRepository = themeAutocompleteJpaRepository;
    }

    /**
     * 자동완성 제안 가져오기
     * Corporation, Theme, Term을 병렬로 검색
     *
     * @param query 검색어
     * @return 자동완성 제안 리스트
     */
    public List<Object> getAutocompleteSuggestions(String query) {
        long startTime = System.currentTimeMillis();
        List<Object> suggestions = new ArrayList<>();

        if (query == null || query.trim().isEmpty() || query.trim().length() < 1) {
            return suggestions;
        }

        String trimmedQuery = query.trim().toLowerCase();

        // 검색어를 자모 분해 (예: "프로제" → "ㅍㅡㄹㅗㅈㅔ", "톳" → "ㅌㅗㅅ")
        String decomposedQuery = KoreanCharacterUtil.decomposeHangul(trimmedQuery);
        long prepTime = System.currentTimeMillis();
        log.debug("[자동완성] 준비 시간: {}ms", prepTime - startTime);

        // ===== 병렬 검색 (CompletableFuture 사용) =====
        long parallelStartTime = System.currentTimeMillis();

        // 검색 패턴 준비
        String query1Param = trimmedQuery + "%";
        String query2Param = decomposedQuery + "%";

        // 1. Corporation 검색 (비동기, JPA)
        CompletableFuture<List<Object>> corporationFuture = CompletableFuture.supplyAsync(() -> {
            long corpStartTime = System.currentTimeMillis();
            List<Object> corpResults = new ArrayList<>();

            List<CorporationAutocompleteProjection> projections = corporationAutocompleteJpaRepository
                .findAutocompleteCorporationsWithTwoPatterns(query1Param, query2Param, 2);

            for (CorporationAutocompleteProjection proj : projections) {
                CorporationAutocompleteDto corp = new CorporationAutocompleteDto(
                    proj.getId(),
                    proj.getName(),
                    proj.getAlternateName(),
                    proj.getLogoUrl(),
                    proj.getLogoS3Url(),
                    proj.getLogoFilename(),
                    proj.getDecomposedName(),
                    proj.getDecomposedAlternateName()
                );
                String displayName = corp.getDisplayName(decomposedQuery);
                // [0, name, id, logoUrl] - 명시적 배열 사용
                corpResults.add(new Object[]{0, displayName, corp.getId(), corp.getEffectiveLogoUrl()});
            }

            long corpTime = System.currentTimeMillis() - corpStartTime;
            log.debug("[자동완성] Corporation 검색 (JPA): {}ms", corpTime);
            return corpResults;
        }, autocompleteExecutor).exceptionally(ex -> {
            log.error("[자동완성] Corporation 검색 실패", ex);
            return new ArrayList<>();
        });

        // 2. Theme 검색 (비동기, JPA)
        CompletableFuture<List<Object>> themeFuture = CompletableFuture.supplyAsync(() -> {
            long themeStartTime = System.currentTimeMillis();
            List<Object> themeResults = new ArrayList<>();

            List<ThemeAutocompleteProjection> projections = themeAutocompleteJpaRepository
                .findAutocompleteThemesSimple(query1Param, query2Param, 2);

            for (ThemeAutocompleteProjection proj : projections) {
                ThemeAutocompleteDto theme = new ThemeAutocompleteDto(proj.getId(), proj.getName());
                // [1, id, name] - 명시적 배열 사용
                themeResults.add(new Object[]{1, theme.getId(), theme.getName()});
            }

            long themeTime = System.currentTimeMillis() - themeStartTime;
            log.debug("[자동완성] Theme 검색 (JPA): {}ms", themeTime);
            return themeResults;
        }, autocompleteExecutor).exceptionally(ex -> {
            log.error("[자동완성] Theme 검색 실패", ex);
            return new ArrayList<>();
        });

        // 3. Term 검색 (비동기, JPA)
        CompletableFuture<List<Object>> termFuture = CompletableFuture.supplyAsync(() -> {
            long termStartTime = System.currentTimeMillis();
            List<Object> termResults = new ArrayList<>();

            // Term 검색 (최대 4개)
            String termQuery = decomposedQuery.toLowerCase() + "%";
            List<TermAutocompleteProjection> projections = termAutocompleteJpaRepository
                .findAutocompleteTerms(termQuery, 4);

            for (TermAutocompleteProjection proj : projections) {
                TermAutocompleteDto termDto = new TermAutocompleteDto(proj.getTerm(), proj.getTotalFrequency());
                // Just the term string
                termResults.add(termDto.getTerm());
            }

            long termTime = System.currentTimeMillis() - termStartTime;
            log.debug("[자동완성] Term 검색 (JPA): {}ms", termTime);
            return termResults;
        }, autocompleteExecutor).exceptionally(ex -> {
            log.error("[자동완성] Term 검색 실패", ex);
            return new ArrayList<>();
        });

        // 모든 비동기 작업 완료 대기 및 결과 합치기
        CompletableFuture.allOf(corporationFuture, themeFuture, termFuture).join();

        // 순서대로 결과 추가 (Corporation → Theme → Term)
        suggestions.addAll(corporationFuture.join());
        suggestions.addAll(themeFuture.join());
        suggestions.addAll(termFuture.join());

        long parallelTime = System.currentTimeMillis() - parallelStartTime;
        long totalTime = System.currentTimeMillis() - startTime;
        log.info("[자동완성] 총 실행 시간: {}ms (준비: {}ms, 병렬검색: {}ms) - 검색어: '{}'",
                totalTime,
                prepTime - startTime,
                parallelTime,
                query);

        return suggestions;
    }
}
