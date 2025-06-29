package com.newcodes7.small_town.crawler.exception;

public class InvalidUrlException extends CrawlerException {
    
    public InvalidUrlException(String url) {
        super("INVALID_URL_FORMAT", "Invalid URL format: %s", url);
    }
    
    public InvalidUrlException(String url, Throwable cause) {
        super("INVALID_URL_MALFORMED", "Malformed URL: %s", cause, url);
    }
    
    public static InvalidUrlException unsupportedProtocol(String url, String protocol) {
        return new InvalidUrlException("INVALID_URL_UNSUPPORTED_PROTOCOL", "Unsupported protocol '%s' for URL: %s", protocol, url);
    }
    
    public static InvalidUrlException domainNotAllowed(String url, String domain) {
        return new InvalidUrlException("INVALID_URL_DOMAIN_NOT_ALLOWED", "Domain '%s' not allowed for URL: %s", domain, url);
    }
    
    public static InvalidUrlException urlTooLong(String url, int maxLength) {
        return new InvalidUrlException("INVALID_URL_TOO_LONG", "URL exceeds maximum length of %d characters: %s", maxLength, url.length() > 100 ? url.substring(0, 100) + "..." : url);
    }
    
    public static InvalidUrlException missingHost(String url) {
        return new InvalidUrlException("INVALID_URL_MISSING_HOST", "URL missing host: %s", url);
    }
    
    public static InvalidUrlException blacklistedUrl(String url) {
        return new InvalidUrlException("INVALID_URL_BLACKLISTED", "URL is blacklisted: %s", url);
    }
    
    public static InvalidUrlException relativePath(String url) {
        return new InvalidUrlException("INVALID_URL_RELATIVE_PATH", "Relative URL not allowed: %s", url);
    }
    
    private InvalidUrlException(String errorCode, String message, Object... args) {
        super(errorCode, message, args);
    }
    
    private InvalidUrlException(String errorCode, String message, Throwable cause, Object... args) {
        super(errorCode, message, cause, args);
    }
}