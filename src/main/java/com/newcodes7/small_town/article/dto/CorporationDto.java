package com.newcodes7.small_town.article.dto;

import com.newcodes7.small_town.article.entity.Corporation;
import lombok.Getter;

@Getter
public class CorporationDto {
    
    private final Long id;
    private final String name;
    private final String logoUrl;
    private final String logoFilename;
    
    public CorporationDto(Corporation corporation) {
        this.id = corporation.getId();
        this.name = corporation.getName();
        this.logoUrl = corporation.getLogoUrl();
        this.logoFilename = corporation.getLogoFilename();
    }
    
    /**
     * 효과적인 로고 URL을 반환합니다.
     * logoFilename이 있으면 로컬 파일 경로를, 없으면 logoUrl을 반환합니다.
     */
    public String getEffectiveLogoUrl() {
        if (logoFilename != null && !logoFilename.trim().isEmpty()) {
            return "/images/logos/" + logoFilename;
        }
        return logoUrl; // 기존 URL 방식 fallback
    }
}