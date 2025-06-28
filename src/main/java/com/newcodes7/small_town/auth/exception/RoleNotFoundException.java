package com.newcodes7.small_town.auth.exception;

public class RoleNotFoundException extends AuthException {
    
    public RoleNotFoundException(String roleName) {
        super("ROLE_NOT_FOUND", "역할을 찾을 수 없습니다. 역할명: %s", roleName);
    }
}