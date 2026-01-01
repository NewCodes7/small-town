package com.newcodes7.small_town.theme.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ThemeArticleAddRequestDto {

    @NotNull(message = "아티클 ID는 필수입니다")
    private Long articleId;

    private Integer displayOrder;
}
