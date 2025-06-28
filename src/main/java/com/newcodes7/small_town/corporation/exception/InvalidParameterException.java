package com.newcodes7.small_town.corporation.exception;

public class InvalidParameterException extends CorporationException {
    
    public InvalidParameterException(String parameterName, Object value) {
        super("INVALID_PARAMETER", "잘못된 파라미터입니다. %s: %s", parameterName, value);
    }
    
    public InvalidParameterException(String parameterName, Object value, String reason) {
        super("INVALID_PARAMETER", "잘못된 파라미터입니다. %s: %s, 사유: %s", parameterName, value, reason);
    }
}