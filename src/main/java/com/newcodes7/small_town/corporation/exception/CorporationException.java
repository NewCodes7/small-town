package com.newcodes7.small_town.corporation.exception;

import lombok.Getter;

@Getter
public abstract class CorporationException extends RuntimeException {
    
    private final String errorCode;
    private final Object[] args;
    
    protected CorporationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.args = null;
    }
    
    protected CorporationException(String errorCode, String message, Object... args) {
        super(String.format(message, args));
        this.errorCode = errorCode;
        this.args = args;
    }
    
    protected CorporationException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.args = null;
    }
    
    protected CorporationException(String errorCode, String message, Throwable cause, Object... args) {
        super(String.format(message, args), cause);
        this.errorCode = errorCode;
        this.args = args;
    }
}