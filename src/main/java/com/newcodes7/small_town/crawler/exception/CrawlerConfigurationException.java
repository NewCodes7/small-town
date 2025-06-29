package com.newcodes7.small_town.crawler.exception;

public class CrawlerConfigurationException extends CrawlerException {
    
    public CrawlerConfigurationException(String configProperty, String message) {
        super("CRAWLER_CONFIG_INVALID", "Invalid crawler configuration for property '%s': %s", configProperty, message);
    }
    
    public CrawlerConfigurationException(String configProperty, String expectedValue, String actualValue) {
        super("CRAWLER_CONFIG_VALUE_INVALID", "Invalid value for configuration property '%s'. Expected: %s, Actual: %s", configProperty, expectedValue, actualValue);
    }
    
    public static CrawlerConfigurationException missingProperty(String propertyName) {
        return new CrawlerConfigurationException("CRAWLER_CONFIG_MISSING", "Required crawler configuration property missing: %s", propertyName);
    }
    
    public static CrawlerConfigurationException invalidTimeout(String propertyName, int value, int minValue, int maxValue) {
        return new CrawlerConfigurationException("CRAWLER_CONFIG_INVALID_TIMEOUT", "Invalid timeout value for property '%s': %d. Must be between %d and %d seconds", propertyName, value, minValue, maxValue);
    }
    
    public static CrawlerConfigurationException invalidThreadPool(int coreSize, int maxSize) {
        return new CrawlerConfigurationException("CRAWLER_CONFIG_INVALID_THREAD_POOL", "Invalid thread pool configuration. Core size: %d, Max size: %d. Core size must be <= max size", coreSize, maxSize);
    }
    
    public static CrawlerConfigurationException invalidCronExpression(String cronExpression, Throwable cause) {
        return new CrawlerConfigurationException("CRAWLER_CONFIG_INVALID_CRON", "Invalid cron expression: %s", cause, cronExpression);
    }
    
    public static CrawlerConfigurationException webDriverConfigError(String message, Throwable cause) {
        return new CrawlerConfigurationException("CRAWLER_CONFIG_WEBDRIVER_ERROR", "WebDriver configuration error: %s", cause, message);
    }
    
    private CrawlerConfigurationException(String errorCode, String message, Object... args) {
        super(errorCode, message, args);
    }
    
    private CrawlerConfigurationException(String errorCode, String message, Throwable cause, Object... args) {
        super(errorCode, message, cause, args);
    }
}