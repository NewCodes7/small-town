package com.newcodes7.small_town.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class JwtResponseDto {
    
    private String accessToken;
    private String refreshToken;
    private String tokenType = "Bearer";
    private String email;
    private String nickname;
    private String role;
    
    public JwtResponseDto(String accessToken, String refreshToken, String email, String nickname, String role) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.email = email;
        this.nickname = nickname;
        this.role = role;
    }
}