package com.newcodes7.small_town.auth.exception;

public class AuthenticationFailedException extends AuthException {
    
    public AuthenticationFailedException(String email) {
        super("AUTHENTICATION_FAILED", "인증에 실패했습니다. 이메일: %s", email);
    }
    
    public AuthenticationFailedException(String email, Throwable cause) {
        super("AUTHENTICATION_FAILED", "인증에 실패했습니다. 이메일: %s", cause, email);
    }
}