package com.newcodes7.small_town.crawler.exception;

import com.newcodes7.small_town.global.exception.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

@Slf4j
@RestControllerAdvice(basePackages = "com.newcodes7.small_town.crawler")
public class CrawlerExceptionHandler {
    
    @ExceptionHandler(CorporationCrawlingException.class)
    public ResponseEntity<ErrorResponse> handleCorporationCrawlingException(CorporationCrawlingException e) {
        log.warn("Corporation crawling error: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }
    
    @ExceptionHandler(WebDriverException.class)
    public ResponseEntity<ErrorResponse> handleWebDriverException(WebDriverException e) {
        log.error("WebDriver error: {}", e.getMessage(), e);
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }
    
    @ExceptionHandler(ContentParsingException.class)
    public ResponseEntity<ErrorResponse> handleContentParsingException(ContentParsingException e) {
        log.warn("Content parsing error: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }
    
    @ExceptionHandler(NetworkAccessException.class)
    public ResponseEntity<ErrorResponse> handleNetworkAccessException(NetworkAccessException e) {
        log.warn("Network access error: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        HttpStatus status = determineHttpStatusFromNetworkError(e.getErrorCode());
        return ResponseEntity.status(status).body(errorResponse);
    }
    
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<ErrorResponse> handleRateLimitException(RateLimitException e) {
        log.warn("Rate limit exceeded: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(errorResponse);
    }
    
    @ExceptionHandler(CrawlerConfigurationException.class)
    public ResponseEntity<ErrorResponse> handleCrawlerConfigurationException(CrawlerConfigurationException e) {
        log.error("Crawler configuration error: {}", e.getMessage(), e);
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }
    
    @ExceptionHandler(ArticleDuplicationException.class)
    public ResponseEntity<ErrorResponse> handleArticleDuplicationException(ArticleDuplicationException e) {
        log.debug("Article duplication detected: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }
    
    @ExceptionHandler(CrawlerTimeoutException.class)
    public ResponseEntity<ErrorResponse> handleCrawlerTimeoutException(CrawlerTimeoutException e) {
        log.warn("Crawler timeout: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT).body(errorResponse);
    }
    
    @ExceptionHandler(InvalidUrlException.class)
    public ResponseEntity<ErrorResponse> handleInvalidUrlException(InvalidUrlException e) {
        log.warn("Invalid URL: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }
    
    @ExceptionHandler(RssFeedParsingException.class)
    public ResponseEntity<ErrorResponse> handleRssFeedParsingException(RssFeedParsingException e) {
        log.warn("RSS feed parsing error: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }
    
    @ExceptionHandler(CrawlerSchedulingException.class)
    public ResponseEntity<ErrorResponse> handleCrawlerSchedulingException(CrawlerSchedulingException e) {
        log.error("Crawler scheduling error: {}", e.getMessage(), e);
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }
    
    @ExceptionHandler(CrawlerNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCrawlerNotFoundException(CrawlerNotFoundException e) {
        log.warn("Crawler not found: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }
    
    @ExceptionHandler(CrawlerException.class)
    public ResponseEntity<ErrorResponse> handleCrawlerException(CrawlerException e) {
        log.error("General crawler error: {}", e.getMessage(), e);
        
        ErrorResponse errorResponse = ErrorResponse.of(
                e.getErrorCode(),
                e.getMessage(),
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("Unexpected error in crawler: {}", e.getMessage(), e);
        
        ErrorResponse errorResponse = ErrorResponse.of(
                "CRAWLER_INTERNAL_ERROR",
                "Crawler internal error occurred",
                getCurrentPath()
        );
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }
    
    private HttpStatus determineHttpStatusFromNetworkError(String errorCode) {
        return switch (errorCode) {
            case "NETWORK_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "NETWORK_FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "NETWORK_CONNECTION_TIMEOUT" -> HttpStatus.REQUEST_TIMEOUT;
            case "NETWORK_SERVER_ERROR" -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
    
    private String getCurrentPath() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            return request.getRequestURI();
        }
        return "unknown";
    }
}