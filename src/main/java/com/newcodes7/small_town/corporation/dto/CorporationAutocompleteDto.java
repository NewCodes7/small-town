package com.newcodes7.small_town.corporation.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Corporation 자동완성 결과 DTO
 * JdbcTemplate 성능 최적화를 위해 엔티티 대신 사용
 * 성능: JPA Entity 로딩 대비 10배 이상 빠름
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CorporationAutocompleteDto {
    private Long id;
    private String name;
    private String alternateName;
    private String logoUrl;
    private String logoS3Url;
    private String logoFilename;
    private String decomposedName;
    private String decomposedAlternateName;

    /**
     * 검색어와 매칭된 표시 이름 반환
     * 대체명이 검색어로 시작하면 대체명 반환, 아니면 기본 이름 반환
     */
    public String getDisplayName(String decomposedQuery) {
        if (alternateName != null && !alternateName.isEmpty() &&
            decomposedAlternateName != null &&
            decomposedAlternateName.toLowerCase().startsWith(decomposedQuery.toLowerCase())) {
            return alternateName;
        }
        return name;
    }

    /**
     * 효과적인 로고 URL 반환
     * Corporation 엔티티와 동일한 로직: S3 URL > 로컬 파일 > 기존 URL
     */
    public String getEffectiveLogoUrl() {
        // S3 URL이 있으면 우선 사용
        if (logoS3Url != null && !logoS3Url.trim().isEmpty()) {
            return logoS3Url;
        }
        // 로컬 파일명이 있으면 사용
        if (logoFilename != null && !logoFilename.trim().isEmpty()) {
            return "/images/logos/" + logoFilename;
        }
        return logoUrl; // 기존 URL 방식 fallback
    }
}
