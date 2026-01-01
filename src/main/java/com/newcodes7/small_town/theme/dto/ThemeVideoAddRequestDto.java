package com.newcodes7.small_town.theme.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ThemeVideoAddRequestDto {

    @NotNull(message = "비디오 ID는 필수입니다")
    private Long videoId;

    private Integer displayOrder;
}
