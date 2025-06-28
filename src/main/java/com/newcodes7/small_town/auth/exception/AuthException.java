package com.newcodes7.small_town.auth.exception;

import lombok.Getter;

@Getter
public abstract class AuthException extends RuntimeException {
    
    private final String errorCode;
    private final Object[] args;
    
    protected AuthException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.args = null;
    }
    
    protected AuthException(String errorCode, String message, Object... args) {
        super(String.format(message, args));
        this.errorCode = errorCode;
        this.args = args;
    }
    
    protected AuthException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.args = null;
    }
    
    protected AuthException(String errorCode, String message, Throwable cause, Object... args) {
        super(String.format(message, args), cause);
        this.errorCode = errorCode;
        this.args = args;
    }
}