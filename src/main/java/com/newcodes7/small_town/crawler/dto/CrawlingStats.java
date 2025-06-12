package com.newcodes7.small_town.crawler.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class CrawlingStats {
    private long totalCorporations;
    private long totalNewArticles;
    private LocalDateTime lastCrawledAt;
}