package com.newcodes7.small_town.auth.exception;

public class InvalidTokenException extends AuthException {
    
    public InvalidTokenException(String tokenType) {
        super("INVALID_TOKEN", "유효하지 않은 %s 토큰입니다.", tokenType);
    }
    
    public InvalidTokenException(String tokenType, String reason) {
        super("INVALID_TOKEN", "유효하지 않은 %s 토큰입니다. 사유: %s", tokenType, reason);
    }
}