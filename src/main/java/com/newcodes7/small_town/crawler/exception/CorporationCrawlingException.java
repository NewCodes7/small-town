package com.newcodes7.small_town.crawler.exception;

public class CorporationCrawlingException extends CrawlerException {
    
    public CorporationCrawlingException(Long corporationId) {
        super("CORPORATION_NOT_FOUND", "Corporation not found: %d", corporationId);
    }
    
    public CorporationCrawlingException(String corporationName) {
        super("CORPORATION_INACTIVE", "Corporation is inactive or soft-deleted: %s", corporationName);
    }
    
    public CorporationCrawlingException(Long corporationId, String blogUrl) {
        super("CORPORATION_INVALID_BLOG_URL", "Corporation %d has invalid blog URL: %s", corporationId, blogUrl);
    }
    
    public CorporationCrawlingException(String errorCode, String message, Object... args) {
        super(errorCode, message, args);
    }
    
    public CorporationCrawlingException(String errorCode, String message, Throwable cause, Object... args) {
        super(errorCode, message, cause, args);
    }
}