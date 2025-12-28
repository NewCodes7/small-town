package com.newcodes7.small_town.article.dto;

public interface ArticleResponseDto {

    /**
     * BM25 검색 스코어 (Admin 전용)
     * 기본값은 null (검색이 아닌 일반 목록에서는 스코어 없음)
     */
    default Double getBm25Score() {
        return null;
    }
}
