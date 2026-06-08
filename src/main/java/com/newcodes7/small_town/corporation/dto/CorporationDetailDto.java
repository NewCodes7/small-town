package com.newcodes7.small_town.corporation.dto;

import lombok.Getter;

import java.time.LocalDateTime;

import com.newcodes7.small_town.global.entity.Corporation;

@Getter
public class CorporationDetailDto {

    private final Long id;
    private final String name;
    private final String homeLink;
    private final String blogLink;
    private final String crewLink;
    private final String logoUrl;
    private final String logoFilename;
    private final String logoS3Url;
    private final String youtubeUrl;
    private final String youtubeChannelId;
    private final Boolean isDomestic;
    private final LocalDateTime createdAt;
    private final long articleCount;

    public CorporationDetailDto(Corporation corporation, long articleCount) {
        this.id = corporation.getId();
        this.name = corporation.getName();
        this.homeLink = corporation.getHomeLink();
        this.blogLink = corporation.getBlogLink();
        this.crewLink = corporation.getCrewLink();
        this.logoUrl = corporation.getLogoUrl();
        this.logoFilename = corporation.getLogoFilename();
        this.logoS3Url = corporation.getLogoS3Url();
        this.youtubeUrl = corporation.getYoutubeUrl();
        this.youtubeChannelId = corporation.getYoutubeChannelId();
        this.isDomestic = corporation.getIsDomestic();
        this.createdAt = corporation.getCreatedAt();
        this.articleCount = articleCount;
    }
    
    public String getRegionText() {
        return isDomestic ? "국내" : "해외";
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