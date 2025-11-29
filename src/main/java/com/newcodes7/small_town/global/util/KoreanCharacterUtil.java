package com.newcodes7.small_town.global.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 한글 자모 분리 유틸리티
 */
public class KoreanCharacterUtil {

    // 한글 유니코드 범위
    private static final int HANGUL_BASE = 0xAC00;
    private static final int HANGUL_END = 0xD7A3;

    // 초성, 중성, 종성
    private static final char[] CHOSUNG = {
        'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ',
        'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    };

    private static final char[] JUNGSUNG = {
        'ㅏ', 'ㅐ', 'ㅑ', 'ㅒ', 'ㅓ', 'ㅔ', 'ㅕ', 'ㅖ', 'ㅗ', 'ㅘ',
        'ㅙ', 'ㅚ', 'ㅛ', 'ㅜ', 'ㅝ', 'ㅞ', 'ㅟ', 'ㅠ', 'ㅡ', 'ㅢ', 'ㅣ'
    };

    private static final char[] JONGSUNG = {
        '\0', 'ㄱ', 'ㄲ', 'ㄳ', 'ㄴ', 'ㄵ', 'ㄶ', 'ㄷ', 'ㄹ', 'ㄺ',
        'ㄻ', 'ㄼ', 'ㄽ', 'ㄾ', 'ㄿ', 'ㅀ', 'ㅁ', 'ㅂ', 'ㅄ', 'ㅅ',
        'ㅆ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    };

    /**
     * 한글 문자열을 자모로 분리
     * 예: "리액트" → "ㄹㅣㅇㅐㅋㅡㅌㅡ"
     *
     * @param text 한글 문자열
     * @return 자모로 분리된 문자열
     */
    public static String decomposeHangul(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        StringBuilder result = new StringBuilder();

        for (char ch : text.toCharArray()) {
            if (isHangul(ch)) {
                // 한글인 경우 자모 분리
                result.append(decomposeChar(ch));
            } else {
                // 한글이 아닌 경우 그대로 추가
                result.append(ch);
            }
        }

        return result.toString();
    }

    /**
     * 한글 글자 하나를 자모로 분리
     * 예: '가' → "ㄱㅏ"
     */
    private static String decomposeChar(char ch) {
        if (!isHangul(ch)) {
            return String.valueOf(ch);
        }

        int unicode = ch - HANGUL_BASE;

        int chosungIndex = unicode / (21 * 28);
        int jungsungIndex = (unicode % (21 * 28)) / 28;
        int jongsungIndex = unicode % 28;

        StringBuilder result = new StringBuilder();
        result.append(CHOSUNG[chosungIndex]);
        result.append(JUNGSUNG[jungsungIndex]);

        if (jongsungIndex != 0) {
            result.append(JONGSUNG[jongsungIndex]);
        }

        return result.toString();
    }

    /**
     * 한글 여부 확인
     */
    private static boolean isHangul(char ch) {
        return ch >= HANGUL_BASE && ch <= HANGUL_END;
    }

    /**
     * 문자열에 한글이 포함되어 있는지 확인
     */
    public static boolean containsHangul(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        for (char ch : text.toCharArray()) {
            if (isHangul(ch)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 한글 문자열에서 초성만 추출
     * 예: "파이썬" → "ㅍㅇㅅ", "리액트" → "ㄹㅇㅌ"
     * 초성 검색용 (사용자가 "ㅍ"만 입력해도 "파이썬"을 찾을 수 있도록)
     *
     * @param text 한글 문자열
     * @return 초성만 추출된 문자열
     */
    public static String extractChosung(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        StringBuilder result = new StringBuilder();

        for (char ch : text.toCharArray()) {
            if (isHangul(ch)) {
                // 한글인 경우 초성만 추출
                int unicode = ch - HANGUL_BASE;
                int chosungIndex = unicode / (21 * 28);
                result.append(CHOSUNG[chosungIndex]);
            }
            // 한글이 아닌 경우 무시 (초성 검색은 한글에만 적용)
        }

        return result.toString();
    }

    /**
     * 입력 중인 한글 검색을 위한 패턴 생성
     * 마지막 글자에 종성이 있으면 종성을 초성으로 분리한 패턴도 생성
     * 예: "프롲" → ["프롲", "프로ㅈ"]
     * 예: "프로" → ["프로"]
     * 예: "ㅍ" → ["ㅍ"]
     *
     * @param text 검색어
     * @return 검색할 패턴 목록
     */
    public static List<String> generateSearchPatterns(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<String> patterns = new ArrayList<>();

        // 원본은 항상 포함
        patterns.add(text);

        // 마지막 글자가 완성된 한글이고 종성이 있는 경우
        char lastChar = text.charAt(text.length() - 1);
        if (isHangul(lastChar)) {
            int unicode = lastChar - HANGUL_BASE;
            int jongsungIndex = unicode % 28;

            // 종성이 있으면
            if (jongsungIndex != 0) {
                // 종성을 제거한 글자 생성
                int chosungIndex = unicode / (21 * 28);
                int jungsungIndex = (unicode % (21 * 28)) / 28;

                char charWithoutJongsung = (char) (HANGUL_BASE +
                    chosungIndex * 21 * 28 + jungsungIndex * 28);

                // 종성을 초성으로 변환
                char jongsungAsChosung = JONGSUNG[jongsungIndex];

                // 종성을 분리한 패턴 추가
                String patternWithSeparatedJongsung = text.substring(0, text.length() - 1) +
                    charWithoutJongsung + jongsungAsChosung;
                patterns.add(patternWithSeparatedJongsung);
            }
        }

        return patterns;
    }
}
