package com.newcodes7.small_town.crawler.exception;

public class ArticleDuplicationException extends CrawlerException {
    
    public ArticleDuplicationException(String articleUrl) {
        super("ARTICLE_DUPLICATE_URL", "Duplicate article URL detected: %s", articleUrl);
    }
    
    public ArticleDuplicationException(String title, String url) {
        super("ARTICLE_DUPLICATE_TITLE", "Duplicate article title '%s' detected for URL: %s", title, url);
    }
    
    public static ArticleDuplicationException duplicateContent(String url, String contentHash) {
        return new ArticleDuplicationException("ARTICLE_DUPLICATE_CONTENT", "Duplicate article content detected for URL: %s (hash: %s)", url, contentHash);
    }
    
    public static ArticleDuplicationException duplicateWithinCorporation(String url, Long corporationId) {
        return new ArticleDuplicationException("ARTICLE_DUPLICATE_WITHIN_CORPORATION", "Duplicate article within corporation %d for URL: %s", corporationId, url);
    }
    
    public static ArticleDuplicationException duplicateProcessing(String url) {
        return new ArticleDuplicationException("ARTICLE_DUPLICATE_PROCESSING", "Article is already being processed: %s", url);
    }
    
    private ArticleDuplicationException(String errorCode, String message, Object... args) {
        super(errorCode, message, args);
    }
}