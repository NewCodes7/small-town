package com.newcodes7.small_town.corporation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CorporationUpdateDto {
    
    @NotBlank(message = "기업명은 필수입니다.")
    @Size(max = 100, message = "기업명은 100자 이내여야 합니다.")
    private String name;
    
    private String homeLink;
    private String blogLink;
    private String crewLink;
    private String logoUrl;
    private String effectiveLogoUrl; // 읽기 전용 필드 (폼에서 표시용)
    private String youtubeUrl;
    private String baseUrl;
    private String article; 
    private String title;
    private String link;
    private String thumbnail;
    private String publish;
    private String publishFormat;
    
    private List<Integer> industryIds;
}