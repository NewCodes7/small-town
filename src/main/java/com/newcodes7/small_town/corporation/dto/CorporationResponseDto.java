package com.newcodes7.small_town.corporation.dto;

import com.newcodes7.small_town.corporation.entity.Corporation;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Data
public class CorporationResponseDto {
    private Long id;
    private String name;
    private String homeLink;
    private String blogLink;
    private String crewLink;
    private String logoUrl;
    private String logoFilename;
    private Boolean isDomestic;
    private Long articleCount;
    private List<String> industries;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    public String getRegionText() {
        return Boolean.TRUE.equals(isDomestic) ? "국내" : "해외";
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
    
    public static CorporationResponseDto from(Corporation corporation) {
        CorporationResponseDto dto = new CorporationResponseDto();
        dto.setId(corporation.getId());
        dto.setName(corporation.getName());
        dto.setHomeLink(corporation.getHomeLink());
        dto.setBlogLink(corporation.getBlogLink());
        dto.setCrewLink(corporation.getCrewLink());
        dto.setLogoUrl(corporation.getLogoUrl());
        dto.setLogoFilename(corporation.getLogoFilename());
        // Default to domestic if not specified (corporation module doesn't have isDomestic field)
        dto.setIsDomestic(true);
        dto.setArticleCount(0L); // Default article count
        dto.setCreatedAt(corporation.getCreatedAt());
        dto.setUpdatedAt(corporation.getUpdatedAt());
        dto.setIndustries(
            corporation.getCorporationIndustries().stream()
                .map(ci -> ci.getIndustry().getName())
                .collect(Collectors.toList())
        );
        return dto;
    }
}