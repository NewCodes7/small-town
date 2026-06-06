package com.newcodes7.small_town.corporation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlogAnalysisResultDto {

    private String baseUrl;
    private String article;
    private String title;
    private String link;
    private String thumbnail;
    private String publish;
    private String publishFormat;
    private String publishType;
    private String innerPublishSelector;
    private String paginationType;
    private String pageUrlPattern;
    private String nextPageSelector;
    private Integer maxPages;

    private List<PreviewArticleDto> previewArticles;
    private String confidence;
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PreviewArticleDto {
        private String title;
        private String link;
        private String thumbnail;
        private String publishDate;
    }
}
