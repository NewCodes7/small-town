package com.newcodes7.small_town.global.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.newcodes7.small_town.article.repository.StopwordRepository;

import kr.co.shineware.nlp.komoran.core.Komoran;
import kr.co.shineware.nlp.komoran.model.Token;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class MorphemeAnalyzer {

    private final Komoran komoran;
    private final StopwordRepository stopwordRepository;

    // 불용어 캐시 (성능 최적화)
    private Set<String> stopwordCache = null;

    /**
     * 텍스트에서 형태소를 분석하여 term 목록 추출
     *
     * @param text 분석할 텍스트
     * @return term과 빈도수 Map (term -> frequency)
     */
    public Map<String, TermInfo> extractTerms(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Map.of();
        }

        try {
            // 불용어 캐시 로드 (최초 1회만)
            loadStopwordCacheIfNeeded();

            List<Token> tokens = komoran.analyze(text).getTokenList();

            Map<String, TermInfo> termMap = new HashMap<>();

            for (Token token : tokens) {
                String morph = token.getMorph();
                String pos = token.getPos();

                // 필터링: 명사(NN*), 동사(VV), 영어(SL), 숫자(SN)만 추출
                // 불용어는 제외
                if (isValidTerm(morph, pos) && !isStopword(morph)) {
                    termMap.merge(
                        morph,
                        new TermInfo(morph, pos, 1),
                        (existing, newInfo) -> {
                            existing.incrementFrequency();
                            return existing;
                        }
                    );
                }
            }

            log.debug("텍스트 분석 완료: {} -> {} terms", text.substring(0, Math.min(50, text.length())), termMap.size());
            return termMap;

        } catch (Exception e) {
            log.error("형태소 분석 중 오류 발생: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    /**
     * term이 유효한지 검사
     * - 2글자 이상
     * - 명사, 동사, 영어, 숫자만 허용
     * - 특수문자, 조사 등 제외
     */
    private boolean isValidTerm(String morph, String pos) {
        // 길이 체크 (1글자 제외)
        if (morph.length() < 2) {
            return false;
        }

        // 품사 체크
        return pos.startsWith("NN") ||  // 명사 (NNG: 일반명사, NNP: 고유명사, NNB: 의존명사)
               pos.startsWith("VV") ||  // 동사
               pos.equals("SL") ||      // 영어
               pos.equals("SN");        // 숫자
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
     * 불용어 캐시 로드 (최초 1회만)
     */
    private void loadStopwordCacheIfNeeded() {
        if (stopwordCache == null) {
            stopwordCache = stopwordRepository.findAllTerms();
            log.info("불용어 캐시 로드 완료: {} 개", stopwordCache.size());
        }
    }

    /**
     * 불용어 여부 확인
     */
    private boolean isStopword(String term) {
        return stopwordCache != null && stopwordCache.contains(term);
    }

    /**
     * 불용어 캐시 갱신 (불용어 추가/삭제 시 호출)
     */
    public void refreshStopwordCache() {
        stopwordCache = stopwordRepository.findAllTerms();
        log.info("불용어 캐시 갱신 완료: {} 개", stopwordCache.size());
    }

    /**
     * Term 정보를 담는 내부 클래스
     */
    public static class TermInfo {
        private final String term;
        private final String termType;
        private int frequency;

        public TermInfo(String term, String termType, int frequency) {
            this.term = term;
            this.termType = termType;
            this.frequency = frequency;
        }

        public String getTerm() {
            return term;
        }

        public String getTermType() {
            return termType;
        }

        public int getFrequency() {
            return frequency;
        }

        public void setFrequency(int frequency) {
            this.frequency = frequency;
        }

        public void incrementFrequency() {
            this.frequency++;
        }
    }
}
