package com.newcodes7.small_town.crawler.exception;

public class RateLimitException extends CrawlerException {
    
    public RateLimitException(String url) {
        super("RATE_LIMIT_EXCEEDED", "Rate limit exceeded for URL: %s", url);
    }
    
    public RateLimitException(String url, int retryAfterSeconds) {
        super("RATE_LIMIT_RETRY_AFTER", "Rate limit exceeded for URL: %s. Retry after %d seconds", url, retryAfterSeconds);
    }
    
    public static RateLimitException botDetected(String url) {
        return new RateLimitException("BOT_DETECTION_TRIGGERED", "Bot detection triggered for URL: %s", url);
    }
    
    public static RateLimitException captchaRequired(String url) {
        return new RateLimitException("CAPTCHA_REQUIRED", "CAPTCHA verification required for URL: %s", url);
    }
    
    public static RateLimitException ipBlocked(String url) {
        return new RateLimitException("IP_BLOCKED", "IP address blocked for URL: %s", url);
    }
    
    public static RateLimitException tooManyRequests(String url, String retryAfter) {
        return new RateLimitException("TOO_MANY_REQUESTS", "Too many requests (429) for URL: %s. Retry after: %s", url, retryAfter);
    }
    
    private RateLimitException(String errorCode, String message, Object... args) {
        super(errorCode, message, args);
    }
}