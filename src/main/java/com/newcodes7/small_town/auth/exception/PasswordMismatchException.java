package com.newcodes7.small_town.auth.exception;

public class PasswordMismatchException extends AuthException {
    
    public PasswordMismatchException() {
        super("PASSWORD_MISMATCH", "비밀번호가 일치하지 않습니다.");
    }
}