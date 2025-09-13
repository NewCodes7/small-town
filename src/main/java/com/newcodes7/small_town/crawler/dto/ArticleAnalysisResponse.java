package com.newcodes7.small_town.crawler.dto;

import java.util.List;

import com.newcodes7.small_town.global.entity.ArticleSummary;
import com.newcodes7.small_town.global.entity.Category;
import com.newcodes7.small_town.global.entity.Tag;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ArticleAnalysisResponse {
    private List<ArticleSummary> summaries;
    private String category;
    private List<String> tags;

    public Category toCategoryEntity() {
        return Category.builder()
                .name(this.category)
                .build();
    }

    public List<Tag> toTagEntities() {
        return this.tags.stream()
                .map(tagName -> Tag.builder().keyword(tagName).requestCount(0).build())
                .toList();
    }
}
