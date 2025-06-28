package com.newcodes7.small_town.auth.exception;

public class DuplicateEmailException extends AuthException {
    
    public DuplicateEmailException(String email) {
        super("DUPLICATE_EMAIL", "이미 사용 중인 이메일입니다. 이메일: %s", email);
    }
}