package com.newcodes7.small_town.corporation.exception;

import java.util.List;

public class IndustryNotFoundException extends CorporationException {
    
    public IndustryNotFoundException(Long industryId) {
        super("INDUSTRY_NOT_FOUND", "업종을 찾을 수 없습니다. ID: %d", industryId);
    }
    
    public IndustryNotFoundException(List<Integer> industryIds) {
        super("INDUSTRY_NOT_FOUND", "일부 업종을 찾을 수 없습니다. IDs: %s", industryIds.toString());
    }
}