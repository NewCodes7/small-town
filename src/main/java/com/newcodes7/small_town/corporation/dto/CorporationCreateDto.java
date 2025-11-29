package com.newcodes7.small_town.corporation.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CorporationCreateDto {
    
    @NotBlank(message = "기업명은 필수입니다.")
    @Size(max = 100, message = "기업명은 100자 이내여야 합니다.")
    private String name;

    @Size(max = 100, message = "대체 기업명은 100자 이내여야 합니다.")
    private String alternateName;

    private Integer isDomestic;
    private String homeLink;
    private String blogLink;
    private String crewLink;
    private String logoUrl;
    private String youtubeUrl;

    // 블로그 글 파싱을 위해 필요한 속성 
    private String baseUrl;
    private String article; 
    private String title;
    private String link;
    private String thumbnail;
    private String publish;
    private String publishFormat;
    
    private List<Integer> industryIds;
}