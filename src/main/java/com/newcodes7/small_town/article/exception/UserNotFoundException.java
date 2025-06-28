package com.newcodes7.small_town.article.exception;

public class UserNotFoundException extends ArticleException {
    
    public UserNotFoundException(String userEmail) {
        super("USER_NOT_FOUND", "사용자를 찾을 수 없습니다. 이메일: %s", userEmail);
    }
    
    public UserNotFoundException(Long userId) {
        super("USER_NOT_FOUND", "사용자를 찾을 수 없습니다. ID: %d", userId);
    }
}