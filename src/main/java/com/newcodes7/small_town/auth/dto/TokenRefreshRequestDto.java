package com.newcodes7.small_town.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class TokenRefreshRequestDto {
    
    @NotBlank(message = "리프레시 토큰은 필수입니다.")
    private String refreshToken;
}