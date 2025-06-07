package com.newcodes7.small_town.article.dto;

import com.newcodes7.small_town.article.entity.Corporation;
import lombok.Getter;

@Getter
public class CorporationDto {
    
    private final Long id;
    private final String name;
    private final String logoUrl;
    
    public CorporationDto(Corporation corporation) {
        this.id = corporation.getId();
        this.name = corporation.getName();
        this.logoUrl = corporation.getLogoUrl();
    }
}