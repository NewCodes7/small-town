package com.newcodes7.small_town.article.entity;

/**
 * 아티클 클릭 유입경로(referrer source).
 * 사용자가 어느 섹션에서 아티클로 진입했는지를 식별한다.
 */
public enum ReferralSource {
    SEARCH_RESULT,         // 검색 결과 클릭
    LIST_VIEW,             // home 리스트 뷰
    GROUPED_VIEW,          // home 그룹(기업 묶음) 뷰
    POPULAR_HOME,          // new-home 이번 주 인기글
    LATEST_HOME,           // new-home 최신 블로그
    RELATED_ARTICLE,       // 아티클 상세 관련글 (context_id = 출발 article id)
    LIKED_ARTICLES,        // 좋아요 모음 페이지
    CORPORATION_ARTICLES,  // 기업 상세 목록 (context_id = corporation id)
    THEME_ARTICLES,        // 테마 상세 목록 (context_id = theme id)
    DIRECT;                // 직접/알 수 없음 (fallback)

    /**
     * 문자열을 enum으로 파싱하되, 알 수 없는 값이면 DIRECT로 대체한다.
     */
    public static ReferralSource fromString(String value) {
        if (value == null) {
            return DIRECT;
        }
        try {
            return ReferralSource.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DIRECT;
        }
    }
}
