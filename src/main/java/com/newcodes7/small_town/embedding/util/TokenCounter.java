package com.newcodes7.small_town.embedding.util;

import org.springframework.stereotype.Component;

/**
 * 토큰 수 추정 유틸리티
 * 정확한 토큰화 라이브러리 없이 대략적인 토큰 수를 추정
 */
@Component
public class TokenCounter {

    // 평균적으로 한글은 2-3자당 1토큰, 영어는 4자당 1토큰
    private static final double KOREAN_CHARS_PER_TOKEN = 2.5;
    private static final double ENGLISH_CHARS_PER_TOKEN = 4.0;

    /**
     * 텍스트의 토큰 수 추정
     *
     * @param text 텍스트
     * @return 추정 토큰 수
     */
    public int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int koreanCount = 0;
        int otherCount = 0;

        for (char c : text.toCharArray()) {
            if (isKorean(c)) {
                koreanCount++;
            } else {
                otherCount++;
            }
        }

        // 한글과 영어/기타 문자의 토큰 수를 각각 계산하여 합산
        int koreanTokens = (int) Math.ceil(koreanCount / KOREAN_CHARS_PER_TOKEN);
        int otherTokens = (int) Math.ceil(otherCount / ENGLISH_CHARS_PER_TOKEN);

        return koreanTokens + otherTokens;
    }

    /**
     * 한글 문자 여부 확인
     */
    private boolean isKorean(char c) {
        return (c >= 0xAC00 && c <= 0xD7A3) ||  // 한글 음절
               (c >= 0x1100 && c <= 0x11FF) ||  // 한글 자모
               (c >= 0x3130 && c <= 0x318F);    // 한글 호환 자모
    }
}
