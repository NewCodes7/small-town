package com.newcodes7.small_town.crawler.exception;

public class CrawlerNotFoundException extends CrawlerException {
    
    public CrawlerNotFoundException(String blogUrl) {
        super("CRAWLER_NOT_FOUND", "No suitable crawler found for blog URL: %s", blogUrl);
    }
    
    public CrawlerNotFoundException(String blogUrl, String requiredCrawlerType) {
        super("CRAWLER_TYPE_NOT_FOUND", "Crawler type '%s' not found for blog URL: %s", requiredCrawlerType, blogUrl);
    }
    
    public static CrawlerNotFoundException noCrawlerImplementation(String domain) {
        return new CrawlerNotFoundException("CRAWLER_NO_IMPLEMENTATION", "No crawler implementation available for domain: %s", domain);
    }
    
    public static CrawlerNotFoundException crawlerDisabled(String crawlerName, String blogUrl) {
        return new CrawlerNotFoundException("CRAWLER_DISABLED", "Crawler '%s' is disabled for blog URL: %s", crawlerName, blogUrl);
    }
    
    public static CrawlerNotFoundException unsupportedBlogPlatform(String platform, String blogUrl) {
        return new CrawlerNotFoundException("CRAWLER_UNSUPPORTED_PLATFORM", "Unsupported blog platform '%s' for URL: %s", platform, blogUrl);
    }
    
    public static CrawlerNotFoundException crawlerInitializationFailed(String crawlerName, Throwable cause) {
        return new CrawlerNotFoundException("CRAWLER_INITIALIZATION_FAILED", "Failed to initialize crawler '%s'", cause, crawlerName);
    }
    
    private CrawlerNotFoundException(String errorCode, String message, Object... args) {
        super(errorCode, message, args);
    }
    
    private CrawlerNotFoundException(String errorCode, String message, Throwable cause, Object... args) {
        super(errorCode, message, cause, args);
    }
}