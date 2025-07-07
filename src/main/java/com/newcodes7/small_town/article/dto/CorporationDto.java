package com.newcodes7.small_town.article.dto;

import com.newcodes7.small_town.article.entity.Corporation;
import lombok.Getter;

@Getter
public class CorporationDto {
    
    private final Long id;
    private final String name;
    private final String logoUrl;
    private final String logoFilename;
    private final String logoS3Url;
    
    public CorporationDto(Corporation corporation) {
        this.id = corporation.getId();
        this.name = corporation.getName();
        this.logoUrl = corporation.getLogoUrl();
        this.logoFilename = corporation.getLogoFilename();
        this.logoS3Url = corporation.getLogoS3Url();
    }
    
    /**
     * 효과적인 로고 URL을 반환합니다.
     * S3 URL > 로컬 파일 경로 > 외부 URL 순서로 우선순위를 가집니다.
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