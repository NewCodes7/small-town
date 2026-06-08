package com.newcodes7.small_town.activity.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 아티클 유입경로 클릭 추적 요청 바디.
 * {@code source}는 {@link com.newcodes7.small_town.activity.entity.ReferralSource} 값,
 * {@code sourceContextId}는 출발지 컨텍스트 ID(관련글의 출발 article, 테마/기업 id 등, nullable).
 */
@Getter
@NoArgsConstructor
public class ArticleReferralRequestDto {
    private String source;
    private Long sourceContextId;
}
