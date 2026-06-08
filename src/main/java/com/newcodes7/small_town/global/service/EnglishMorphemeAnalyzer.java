package com.newcodes7.small_town.global.service;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.stereotype.Service;

import com.newcodes7.small_town.term.repository.StopwordRepository;
import com.newcodes7.small_town.global.service.MorphemeAnalyzer.TermInfo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 영어 형태소 분석기
 * Apache Lucene EnglishAnalyzer를 사용하여 영어 텍스트에서 term 추출
 * MorphemeAnalyzer와 동일한 인터페이스 제공
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EnglishMorphemeAnalyzer {

    private final EnglishAnalyzer englishAnalyzer;
    private final Set<String> englishTechTerms;
    private final StopwordRepository stopwordRepository;

    // 불용어 캐시
    private Set<String> stopwordCache = null;

    /**
     * 영어 텍스트에서 형태소를 분석하여 term 목록 추출
     *
     * @param text 분석할 텍스트
     * @return term과 정보 Map (term -> TermInfo)
     */
    public Map<String, TermInfo> extractTerms(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Map.of();
        }

        try {
            loadStopwordCacheIfNeeded();

            Map<String, TermInfo> termMap = new HashMap<>();

            // Lucene Analyzer로 토큰화
            try (TokenStream tokenStream = englishAnalyzer.tokenStream("content", new StringReader(text))) {
                CharTermAttribute charTermAttribute = tokenStream.addAttribute(CharTermAttribute.class);
                tokenStream.reset();

                while (tokenStream.incrementToken()) {
                    String token = charTermAttribute.toString().toLowerCase();

                    // 유효한 term인지 확인
                    if (isValidTerm(token) && !isStopword(token)) {
                        String posTag = determinePosTag(token);

                        termMap.merge(
                            token,
                            new TermInfo(token, posTag, 1),
                            (existing, newInfo) -> {
                                existing.incrementFrequency();
                                return existing;
                            }
                        );
                    }
                }

                tokenStream.end();
            }

            log.debug("영어 텍스트 분석 완료: {} -> {} terms",
                text.substring(0, Math.min(50, text.length())), termMap.size());
            return termMap;

        } catch (IOException e) {
            log.error("영어 형태소 분석 중 오류 발생: {}", e.getMessage(), e);
            return Map.of();
        }
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
     * term이 유효한지 검사
     * - 2글자 이상
     * - 영문자로 시작
     * - 영문자, 숫자, 하이픈, 언더스코어만 허용
     */
    private boolean isValidTerm(String token) {
        if (token == null || token.length() < 2) {
            return false;
        }

        // 첫 글자가 영문자여야 함
        char first = token.charAt(0);
        if (!((first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z'))) {
            return false;
        }

        // 허용된 문자만 포함하는지 확인
        for (char c : token.toCharArray()) {
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                  (c >= '0' && c <= '9') || c == '-' || c == '_')) {
                return false;
            }
        }

        return true;
    }

    /**
     * 품사 태그 결정
     * - 기술 용어 사전에 있으면 EN_TECH
     * - 숫자 포함이면 EN_NUM
     * - 그 외 EN_NOUN
     */
    private String determinePosTag(String token) {
        // 기술 용어 사전 확인
        if (englishTechTerms.contains(token.toLowerCase())) {
            return EnglishPosTag.EN_TECH.getTag();
        }

        // 숫자 포함 여부 확인
        for (char c : token.toCharArray()) {
            if (Character.isDigit(c)) {
                return EnglishPosTag.EN_NUM.getTag();
            }
        }

        return EnglishPosTag.EN_NOUN.getTag();
    }

    /**
     * 불용어 캐시 로드
     */
    private void loadStopwordCacheIfNeeded() {
        if (stopwordCache == null) {
            stopwordCache = stopwordRepository.findAllTerms();
            log.info("영어 불용어 캐시 로드 완료: {} 개", stopwordCache.size());
        }
    }

    /**
     * 불용어 여부 확인
     */
    private boolean isStopword(String term) {
        return stopwordCache != null && stopwordCache.contains(term.toLowerCase());
    }

    /**
     * 불용어 캐시 갱신
     */
    public void refreshStopwordCache() {
        stopwordCache = stopwordRepository.findAllTerms();
        log.info("영어 불용어 캐시 갱신 완료: {} 개", stopwordCache.size());
    }
}
