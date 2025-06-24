package com.newcodes7.small_town.article.dto;

import com.newcodes7.small_town.article.entity.Corporation;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class CorporationDetailDto {
    
    private final Long id;
    private final String name;
    private final String homeLink;
    private final String blogLink;
    private final String crewLink;
    private final String logoUrl;
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
        this.isDomestic = corporation.getIsDomestic();
        this.createdAt = corporation.getCreatedAt();
        this.articleCount = articleCount;
    }
    
    public String getRegionText() {
        return isDomestic ? "국내" : "해외";
    }
}