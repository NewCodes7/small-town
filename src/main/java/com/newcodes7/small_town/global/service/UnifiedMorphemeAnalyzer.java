package com.newcodes7.small_town.global.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.newcodes7.small_town.global.service.MorphemeAnalyzer.TermInfo;
import com.newcodes7.small_town.global.util.LanguageDetector;
import com.newcodes7.small_town.global.util.LanguageDetector.Language;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 통합 형태소 분석기 (Facade)
 * 언어를 자동 감지하여 적절한 분석기로 위임
 * - 한국어: MorphemeAnalyzer (Nori)
 * - 영어: EnglishMorphemeAnalyzer (Lucene)
 * - 혼합: 두 분석기 병렬 실행 후 병합
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UnifiedMorphemeAnalyzer {

    private final MorphemeAnalyzer koreanAnalyzer;
    private final EnglishMorphemeAnalyzer englishAnalyzer;
    private final LanguageDetector languageDetector;

    /**
     * 텍스트에서 형태소를 분석하여 term 목록 추출
     * 언어를 자동 감지하여 적절한 분석기 사용
     *
     * @param text 분석할 텍스트
     * @return term과 정보 Map (term -> TermInfo)
     */
    public Map<String, TermInfo> extractTerms(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Map.of();
        }

        Language language = languageDetector.detectLanguage(text);
        log.debug("언어 감지 결과: {} (텍스트: {})", language, text.substring(0, Math.min(30, text.length())));

        return switch (language) {
            case KOREAN -> koreanAnalyzer.extractTerms(text);
            case ENGLISH -> englishAnalyzer.extractTerms(text);
            case MIXED -> extractTermsMixed(text);
        };
    }

    /**
     * 여러 텍스트를 합쳐서 분석
     */
    public Map<String, TermInfo> extractTermsFromMultipleTexts(List<String> texts) {
        Map<String, TermInfo> combinedTerms = new HashMap<>();

        for (String text : texts) {
            if (text != null && !text.trim().isEmpty()) {
                Map<String, TermInfo> terms = extractTerms(text);
                terms.forEach((term, info) ->
                    combinedTerms.merge(term, info, (existing, newInfo) -> {
                        existing.setFrequency(existing.getFrequency() + newInfo.getFrequency());
                        return existing;
                    })
                );
            }
        }

        return combinedTerms;
    }

    /**
     * 혼합 언어 텍스트 분석
     * 한국어와 영어 분석기를 모두 실행하여 결과 병합
     */
    private Map<String, TermInfo> extractTermsMixed(String text) {
        Map<String, TermInfo> combinedTerms = new HashMap<>();

        // 한국어 분석 (Nori는 영어도 SL 태그로 추출)
        Map<String, TermInfo> koreanTerms = koreanAnalyzer.extractTerms(text);
        combinedTerms.putAll(koreanTerms);

        // 영어 분석 (Lucene으로 추가 분석)
        Map<String, TermInfo> englishTerms = englishAnalyzer.extractTerms(text);

        // 영어 term 병합 (한국어 분석에서 나온 term과 중복 방지)
        for (Map.Entry<String, TermInfo> entry : englishTerms.entrySet()) {
            String term = entry.getKey();
            TermInfo info = entry.getValue();

            // 이미 존재하는 경우 빈도수만 합산
            combinedTerms.merge(term, info, (existing, newInfo) -> {
                existing.setFrequency(existing.getFrequency() + newInfo.getFrequency());
                return existing;
            });
        }

        log.debug("혼합 언어 분석 완료: 한국어 {} terms, 영어 {} terms, 총 {} terms",
            koreanTerms.size(), englishTerms.size(), combinedTerms.size());

        return combinedTerms;
    }

    /**
     * 불용어 캐시 갱신 (두 분석기 모두)
     */
    public void refreshStopwordCache() {
        koreanAnalyzer.refreshStopwordCache();
        englishAnalyzer.refreshStopwordCache();
        log.info("통합 형태소 분석기 불용어 캐시 갱신 완료");
    }
}
