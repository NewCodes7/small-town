package com.newcodes7.small_town.auth.exception;

public class UnauthorizedException extends AuthException {
    
    public UnauthorizedException() {
        super("UNAUTHORIZED", "인증이 필요합니다.");
    }
    
    public UnauthorizedException(String resource) {
        super("UNAUTHORIZED", "%s에 접근하려면 인증이 필요합니다.", resource);
    }
}