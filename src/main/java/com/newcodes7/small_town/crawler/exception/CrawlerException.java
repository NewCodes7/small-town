package com.newcodes7.small_town.crawler.exception;

import lombok.Getter;

@Getter
public abstract class CrawlerException extends RuntimeException {
    
    private final String errorCode;
    private final Object[] args;
    
    protected CrawlerException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.args = null;
    }
    
    protected CrawlerException(String errorCode, String message, Object... args) {
        super(String.format(message, args));
        this.errorCode = errorCode;
        this.args = args;
    }
    
    protected CrawlerException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.args = null;
    }
    
    protected CrawlerException(String errorCode, String message, Throwable cause, Object... args) {
        super(String.format(message, args), cause);
        this.errorCode = errorCode;
        this.args = args;
    }
}