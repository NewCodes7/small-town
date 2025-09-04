package com.newcodes7.small_town.crawler.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.newcodes7.small_town.global.entity.Category;
import com.newcodes7.small_town.global.entity.Tag;

import lombok.Data;

@Data
public class ArticleAnalysisResponse {
    private List<String> checklist;
    private String summary;
    private String category;
    private List<String> tags;
    
    @JsonProperty("reasoning_effort")
    private String reasoningEffort;
    
    @JsonProperty("self_check")
    private String selfCheck;

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
