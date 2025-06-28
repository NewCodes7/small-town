package com.newcodes7.small_town.corporation.exception;

public class DuplicateCorporationNameException extends CorporationException {
    
    public DuplicateCorporationNameException(String corporationName) {
        super("DUPLICATE_CORPORATION_NAME", "이미 존재하는 기업명입니다. 기업명: %s", corporationName);
    }
}