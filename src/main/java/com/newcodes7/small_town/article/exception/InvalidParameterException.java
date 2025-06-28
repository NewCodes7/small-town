package com.newcodes7.small_town.article.exception;

public class InvalidParameterException extends ArticleException {
    
    public InvalidParameterException(String parameterName, Object value) {
        super("INVALID_PARAMETER", "잘못된 파라미터입니다. 파라미터: %s, 값: %s", parameterName, value);
    }
    
    public InvalidParameterException(String parameterName, Object value, String reason) {
        super("INVALID_PARAMETER", "잘못된 파라미터입니다. 파라미터: %s, 값: %s, 이유: %s", parameterName, value, reason);
    }
}