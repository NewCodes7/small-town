package com.newcodes7.small_town.crawler.dto;

import com.newcodes7.small_town.crawler.entity.Article;
import com.newcodes7.small_town.crawler.entity.Corporation;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CrawlResult {
    private Corporation corporation;
    private List<Article> articles;
    private boolean success;
    private String errorMessage;
    private LocalDateTime crawledAt;
    private int totalArticles;
    private int newArticles;
    
    public static CrawlResult success(Corporation corporation, List<Article> articles, int newArticles) {
        CrawlResult result = new CrawlResult();
        result.setCorporation(corporation);
        result.setArticles(articles);
        result.setSuccess(true);
        result.setCrawledAt(LocalDateTime.now());
        result.setTotalArticles(articles.size());
        result.setNewArticles(newArticles);
        return result;
    }
    
    public static CrawlResult failure(Corporation corporation, String errorMessage) {
        CrawlResult result = new CrawlResult();
        result.setCorporation(corporation);
        result.setSuccess(false);
        result.setErrorMessage(errorMessage);
        result.setCrawledAt(LocalDateTime.now());
        return result;
    }
}