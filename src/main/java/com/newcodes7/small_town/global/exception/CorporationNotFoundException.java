package com.newcodes7.small_town.global.exception;

public class CorporationNotFoundException extends RuntimeException {
    
    private final String errorCode;
    
    public CorporationNotFoundException(Long corporationId) {
        super(String.format("기업을 찾을 수 없습니다. ID: %d", corporationId));
        this.errorCode = "CORPORATION_NOT_FOUND";
    }
    
    public CorporationNotFoundException(String corporationName) {
        super(String.format("기업을 찾을 수 없습니다. 이름: %s", corporationName));
        this.errorCode = "CORPORATION_NOT_FOUND";
    }
    
    public String getErrorCode() {
        return errorCode;
    }
}
