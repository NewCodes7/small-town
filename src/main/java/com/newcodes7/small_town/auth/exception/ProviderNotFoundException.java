package com.newcodes7.small_town.auth.exception;

public class ProviderNotFoundException extends AuthException {
    
    public ProviderNotFoundException(String providerName) {
        super("PROVIDER_NOT_FOUND", "인증 제공자를 찾을 수 없습니다. 제공자명: %s", providerName);
    }
}