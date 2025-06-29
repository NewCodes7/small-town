package com.newcodes7.small_town.crawler.exception;

public class NetworkAccessException extends CrawlerException {
    
    public NetworkAccessException(String url, int statusCode) {
        super("NETWORK_HTTP_ERROR", "HTTP error %d for URL: %s", statusCode, url);
    }
    
    public NetworkAccessException(String url, Throwable cause) {
        super("NETWORK_CONNECTION_FAILED", "Network connection failed for URL: %s", cause, url);
    }
    
    public static NetworkAccessException dnsResolutionFailed(String url, Throwable cause) {
        return new NetworkAccessException("NETWORK_DNS_RESOLUTION_FAILED", "DNS resolution failed for URL: %s", cause, url);
    }
    
    public static NetworkAccessException sslCertificateError(String url, Throwable cause) {
        return new NetworkAccessException("NETWORK_SSL_CERTIFICATE_ERROR", "SSL certificate error for URL: %s", cause, url);
    }
    
    public static NetworkAccessException connectionTimeout(String url, int timeoutSeconds) {
        return new NetworkAccessException("NETWORK_CONNECTION_TIMEOUT", "Connection timeout after %d seconds for URL: %s", timeoutSeconds, url);
    }
    
    public static NetworkAccessException forbidden(String url) {
        return new NetworkAccessException("NETWORK_FORBIDDEN", "Access forbidden (403) for URL: %s", url);
    }
    
    public static NetworkAccessException notFound(String url) {
        return new NetworkAccessException("NETWORK_NOT_FOUND", "Resource not found (404) for URL: %s", url);
    }
    
    public static NetworkAccessException serverError(String url, int statusCode) {
        return new NetworkAccessException("NETWORK_SERVER_ERROR", "Server error %d for URL: %s", statusCode, url);
    }
    
    private NetworkAccessException(String errorCode, String message, Object... args) {
        super(errorCode, message, args);
    }
    
    private NetworkAccessException(String errorCode, String message, Throwable cause, Object... args) {
        super(errorCode, message, cause, args);
    }
}