package com.newcodes7.small_town.crawler.exception;

public class CrawlerTimeoutException extends CrawlerException {
    
    public CrawlerTimeoutException(String url, int timeoutSeconds, boolean isUrl) {
        super("CRAWLER_TIMEOUT", "Crawler timeout after %d seconds for URL: %s", timeoutSeconds, url);
    }
    
    public CrawlerTimeoutException(String operation, int timeoutSeconds) {
        super("CRAWLER_OPERATION_TIMEOUT", "Crawler operation '%s' timeout after %d seconds", operation, timeoutSeconds);
    }
    
    public static CrawlerTimeoutException pageLoadTimeout(String url, int timeoutSeconds) {
        return new CrawlerTimeoutException("CRAWLER_PAGE_LOAD_TIMEOUT", "Page load timeout after %d seconds for URL: %s", timeoutSeconds, url);
    }
    
    public static CrawlerTimeoutException elementWaitTimeout(String url, String element, int timeoutSeconds) {
        return new CrawlerTimeoutException("CRAWLER_ELEMENT_WAIT_TIMEOUT", "Element wait timeout for '%s' after %d seconds on URL: %s", element, timeoutSeconds, url);
    }
    
    public static CrawlerTimeoutException scriptExecutionTimeout(String url, int timeoutSeconds) {
        return new CrawlerTimeoutException("CRAWLER_SCRIPT_TIMEOUT", "Script execution timeout after %d seconds for URL: %s", timeoutSeconds, url);
    }
    
    public static CrawlerTimeoutException crawlJobTimeout(Long corporationId, int timeoutMinutes) {
        return new CrawlerTimeoutException("CRAWLER_JOB_TIMEOUT", "Crawl job timeout after %d minutes for corporation: %d", timeoutMinutes, corporationId);
    }
    
    public static CrawlerTimeoutException batchProcessingTimeout(int batchSize, int timeoutMinutes) {
        return new CrawlerTimeoutException("CRAWLER_BATCH_TIMEOUT", "Batch processing timeout after %d minutes for batch size: %d", timeoutMinutes, batchSize);
    }
    
    private CrawlerTimeoutException(String errorCode, String message, Object... args) {
        super(errorCode, message, args);
    }
}