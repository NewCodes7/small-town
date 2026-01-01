package com.newcodes7.small_town.theme.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ThemeUpdateRequestDto {

    private String name;

    private String description;

    private String emoji;

    private String thumbnailUrl;

    private Integer displayOrder;

    private Boolean isActive;
}
