package com.newcodes7.small_town.theme.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ThemeCreateRequestDto {

    @NotBlank(message = "테마 이름은 필수입니다")
    private String name;

    private String description;

    private String emoji;

    private String thumbnailUrl;

    private Integer displayOrder;
}
