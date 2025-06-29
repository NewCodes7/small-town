package com.newcodes7.small_town.crawler.exception;

public class WebDriverException extends CrawlerException {
    
    public WebDriverException(String message) {
        super("WEBDRIVER_INITIALIZATION_FAILED", "WebDriver initialization failed: %s", message);
    }
    
    public WebDriverException(String message, Throwable cause) {
        super("WEBDRIVER_OPERATION_FAILED", "WebDriver operation failed: %s", cause, message);
    }
    
    public WebDriverException(String url, String operation, Throwable cause) {
        super("WEBDRIVER_PAGE_OPERATION_FAILED", "WebDriver failed to %s for URL: %s", cause, operation, url);
    }
    
    public static WebDriverException chromeDriverNotFound(Throwable cause) {
        return new WebDriverException("WEBDRIVER_CHROME_NOT_FOUND", "Chrome WebDriver not found or not accessible", cause);
    }
    
    public static WebDriverException pageLoadTimeout(String url, int timeoutSeconds) {
        return new WebDriverException("WEBDRIVER_PAGE_LOAD_TIMEOUT", "Page load timeout after %d seconds for URL: %s", timeoutSeconds, url);
    }
    
    public static WebDriverException javascriptExecutionFailed(String script, Throwable cause) {
        return new WebDriverException("WEBDRIVER_JS_EXECUTION_FAILED", "JavaScript execution failed: %s", cause, script);
    }
    
    private WebDriverException(String errorCode, String message, Object... args) {
        super(errorCode, message, args);
    }
    
    private WebDriverException(String errorCode, String message, Throwable cause, Object... args) {
        super(errorCode, message, cause, args);
    }
}