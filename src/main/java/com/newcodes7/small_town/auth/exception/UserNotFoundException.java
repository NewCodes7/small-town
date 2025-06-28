package com.newcodes7.small_town.auth.exception;

public class UserNotFoundException extends AuthException {
    
    public UserNotFoundException(String email) {
        super("USER_NOT_FOUND", "사용자를 찾을 수 없습니다. 이메일: %s", email);
    }
    
    public UserNotFoundException(Long userId) {
        super("USER_NOT_FOUND", "사용자를 찾을 수 없습니다. ID: %d", userId);
    }
}