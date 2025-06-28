package com.newcodes7.small_town.article.exception;

public class CorporationNotFoundException extends ArticleException {
    
    public CorporationNotFoundException(Long corporationId) {
        super("CORPORATION_NOT_FOUND", "기업을 찾을 수 없습니다. ID: %d", corporationId);
    }
    
    public CorporationNotFoundException(String corporationName) {
        super("CORPORATION_NOT_FOUND", "기업을 찾을 수 없습니다. 이름: %s", corporationName);
    }
}