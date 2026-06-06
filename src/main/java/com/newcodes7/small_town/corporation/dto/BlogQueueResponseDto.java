package com.newcodes7.small_town.corporation.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcodes7.small_town.crawler.entity.CorporationBlogQueue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlogQueueResponseDto {

    private Long id;
    private String blogUrl;
    private String companyName;
    private String homeLink;
    private String logoUrl;
    private String crewLink;
    private String alternateName;
    private Integer isDomestic;
    private String blogType;
    private String status;
    private BlogAnalysisResultDto analysisResult;
    private String analysisResultJson;
    private String errorMessage;
    private LocalDateTime createdAt;

    public static BlogQueueResponseDto from(CorporationBlogQueue entity, ObjectMapper mapper) {
        BlogAnalysisResultDto result = null;
        String resultJson = entity.getAnalysisResult();
        if (resultJson != null) {
            try {
                result = mapper.readValue(resultJson, BlogAnalysisResultDto.class);
            } catch (Exception ignored) {}
        }
        return BlogQueueResponseDto.builder()
                .id(entity.getId())
                .blogUrl(entity.getBlogUrl())
                .companyName(entity.getCompanyName())
                .homeLink(entity.getHomeLink())
                .logoUrl(entity.getLogoUrl())
                .crewLink(entity.getCrewLink())
                .alternateName(entity.getAlternateName())
                .isDomestic(entity.getIsDomestic())
                .blogType(entity.getBlogType())
                .status(entity.getStatus())
                .analysisResult(result)
                .analysisResultJson(resultJson)
                .errorMessage(entity.getErrorMessage())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
