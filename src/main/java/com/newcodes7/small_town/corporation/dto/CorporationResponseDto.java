package com.newcodes7.small_town.corporation.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import com.newcodes7.small_town.crawler.entity.ParsingSelector;
import com.newcodes7.small_town.global.entity.Corporation;

import lombok.Data;

@Data
public class CorporationResponseDto {
    private Long id;
    private String name;
    private String alternateName;
    private String homeLink;
    private String blogLink;
    private String crewLink;
    private String logoUrl;
    private String logoFilename;
    private String logoS3Url;
    private String youtubeUrl;
    private String youtubeChannelId;
    private Boolean isDomestic;
    private Long articleCount;
    private Integer viewCount;
    private String baseUrl;
    private String article; 
    private String title;
    private String link;
    private String thumbnail;
    private String publish;
    private String publishFormat;
    private String paginationType;
    private String pageUrlPattern;
    private String nextPageSelector;
    private Integer maxPages;
    private List<String> industries;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastFullCrawledAt;
    private String lastFullCrawlStatus;

    public String getRegionText() {
        return Boolean.TRUE.equals(isDomestic) ? "국내" : "해외";
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
    
    public static CorporationResponseDto from(Corporation corporation, ParsingSelector parsingSelector) {
        CorporationResponseDto dto = new CorporationResponseDto();
        dto.setId(corporation.getId());
        dto.setName(corporation.getName());
        dto.setAlternateName(corporation.getAlternateName());
        dto.setHomeLink(corporation.getHomeLink());
        dto.setBlogLink(corporation.getBlogLink());
        dto.setCrewLink(corporation.getCrewLink());
        dto.setLogoUrl(corporation.getLogoUrl());
        dto.setLogoFilename(corporation.getLogoFilename());
        dto.setLogoS3Url(corporation.getLogoS3Url());
        dto.setYoutubeUrl(corporation.getYoutubeUrl());
        dto.setYoutubeChannelId(corporation.getYoutubeChannelId());
        dto.setIsDomestic(corporation.getIsDomestic());
        dto.setViewCount(corporation.getViewCount());
        dto.setBaseUrl(parsingSelector.getBaseUrl());
        dto.setArticle(parsingSelector.getArticle());
        dto.setTitle(parsingSelector.getTitle());
        dto.setLink(parsingSelector.getLink());
        dto.setThumbnail(parsingSelector.getThumbnail());
        dto.setPublish(parsingSelector.getPublish());
        dto.setPublishFormat(parsingSelector.getPublishFormat());
        dto.setPaginationType(parsingSelector.getPaginationType());
        dto.setPageUrlPattern(parsingSelector.getPageUrlPattern());
        dto.setNextPageSelector(parsingSelector.getNextPageSelector());
        dto.setMaxPages(parsingSelector.getMaxPages());
        dto.setArticleCount(0L); // Default article count
        dto.setCreatedAt(corporation.getCreatedAt());
        dto.setUpdatedAt(corporation.getUpdatedAt());
        dto.setLastFullCrawledAt(corporation.getLastFullCrawledAt());
        dto.setLastFullCrawlStatus(corporation.getLastFullCrawlStatus());
        dto.setIndustries(
            corporation.getCorporationIndustries().stream()
                .map(ci -> ci.getIndustry().getName())
                .collect(Collectors.toList())
        );
        return dto;
    }

    public static CorporationResponseDto from(Corporation corporation) {
        CorporationResponseDto dto = new CorporationResponseDto();
        dto.setId(corporation.getId());
        dto.setName(corporation.getName());
        dto.setAlternateName(corporation.getAlternateName());
        dto.setHomeLink(corporation.getHomeLink());
        dto.setBlogLink(corporation.getBlogLink());
        dto.setCrewLink(corporation.getCrewLink());
        dto.setLogoUrl(corporation.getLogoUrl());
        dto.setLogoFilename(corporation.getLogoFilename());
        dto.setLogoS3Url(corporation.getLogoS3Url());
        dto.setYoutubeUrl(corporation.getYoutubeUrl());
        dto.setYoutubeChannelId(corporation.getYoutubeChannelId());
        dto.setIsDomestic(corporation.getIsDomestic());
        dto.setViewCount(corporation.getViewCount());
        dto.setArticleCount(0L); // Default article count
        dto.setCreatedAt(corporation.getCreatedAt());
        dto.setUpdatedAt(corporation.getUpdatedAt());
        dto.setLastFullCrawledAt(corporation.getLastFullCrawledAt());
        dto.setLastFullCrawlStatus(corporation.getLastFullCrawlStatus());
        dto.setIndustries(
            corporation.getCorporationIndustries().stream()
                .map(ci -> ci.getIndustry().getName())
                .collect(Collectors.toList())
        );
        return dto;
    }
}