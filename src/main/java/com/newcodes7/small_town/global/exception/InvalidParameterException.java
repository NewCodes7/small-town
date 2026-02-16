package com.newcodes7.small_town.global.exception;

public class InvalidParameterException extends RuntimeException {
    
    private final String errorCode;
    
    public InvalidParameterException(String parameterName, Object value) {
        super(String.format("잘못된 파라미터입니다. 파라미터: %s, 값: %s", parameterName, value));
        this.errorCode = "INVALID_PARAMETER";
    }
    
    public InvalidParameterException(String parameterName, Object value, String reason) {
        super(String.format("잘못된 파라미터입니다. 파라미터: %s, 값: %s, 이유: %s", parameterName, value, reason));
        this.errorCode = "INVALID_PARAMETER";
    }
    
    public String getErrorCode() {
        return errorCode;
    }
}
