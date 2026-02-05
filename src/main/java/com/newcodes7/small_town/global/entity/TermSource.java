package com.newcodes7.small_town.global.entity;

/**
 * Term이 추출된 소스를 나타내는 enum
 * BM25 인덱스 생성 시 구분하여 사용
 */
public enum TermSource {
    /**
     * title 또는 translatedTitle에서만 추출
     */
    TITLE,

    /**
     * content에서만 추출
     */
    CONTENT,

    /**
     * title과 content 모두에서 추출
     */
    BOTH
}
