package com.newcodes7.small_town.global.service;

/**
 * 영어 품사 태그 enum
 * MorphemeAnalyzer의 termType 필드와 호환
 */
public enum EnglishPosTag {

    /**
     * 일반 영어 명사 (기본값)
     */
    EN_NOUN("EN_NOUN"),

    /**
     * 기술 용어 (사용자 정의 사전에서 추출)
     */
    EN_TECH("EN_TECH"),

    /**
     * 숫자
     */
    EN_NUM("EN_NUM");

    private final String tag;

    EnglishPosTag(String tag) {
        this.tag = tag;
    }

    public String getTag() {
        return tag;
    }

    @Override
    public String toString() {
        return tag;
    }
}
