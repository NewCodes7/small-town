package com.newcodes7.small_town.global.util;

import org.springframework.stereotype.Component;

/**
 * 텍스트의 언어를 감지하는 유틸리티 클래스
 * 한글과 영어 문자의 비율을 기반으로 언어를 판정
 */
@Component
public class LanguageDetector {

    /**
     * 감지된 언어 타입
     */
    public enum Language {
        KOREAN,
        ENGLISH,
        MIXED
    }

    private static final double THRESHOLD = 0.5;

    /**
     * 텍스트의 언어를 감지
     *
     * @param text 분석할 텍스트
     * @return 감지된 언어 (KOREAN, ENGLISH, MIXED)
     */
    public Language detectLanguage(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Language.ENGLISH;
        }

        int koreanCount = 0;
        int englishCount = 0;

        for (char c : text.toCharArray()) {
            if (isKorean(c)) {
                koreanCount++;
            } else if (isEnglish(c)) {
                englishCount++;
            }
        }

        int totalCount = koreanCount + englishCount;
        if (totalCount == 0) {
            return Language.ENGLISH;
        }

        double koreanRatio = (double) koreanCount / totalCount;
        double englishRatio = (double) englishCount / totalCount;

        if (koreanRatio >= THRESHOLD) {
            return Language.KOREAN;
        } else if (englishRatio >= THRESHOLD) {
            return Language.ENGLISH;
        } else {
            return Language.MIXED;
        }
    }

    /**
     * 문자가 한글인지 확인 (가-힣, ㄱ-ㅎ, ㅏ-ㅣ)
     */
    private boolean isKorean(char c) {
        return (c >= '\uAC00' && c <= '\uD7AF') ||  // 한글 음절
               (c >= '\u3131' && c <= '\u314E') ||  // 자음
               (c >= '\u314F' && c <= '\u3163');    // 모음
    }

    /**
     * 문자가 영어인지 확인 (A-Z, a-z)
     */
    private boolean isEnglish(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }
}
