package com.newcodes7.small_town.global.exception;

public class UserNotFoundException extends RuntimeException {
    
    private final String errorCode;
    
    public UserNotFoundException(String email) {
        super(String.format("사용자를 찾을 수 없습니다. 이메일: %s", email));
        this.errorCode = "USER_NOT_FOUND";
    }
    
    public UserNotFoundException(Long userId) {
        super(String.format("사용자를 찾을 수 없습니다. ID: %d", userId));
        this.errorCode = "USER_NOT_FOUND";
    }
    
    public String getErrorCode() {
        return errorCode;
    }
}
