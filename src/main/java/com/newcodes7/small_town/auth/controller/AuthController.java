package com.newcodes7.small_town.auth.controller;

import com.newcodes7.small_town.auth.dto.*;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.exception.AuthException;
import com.newcodes7.small_town.auth.exception.InvalidTokenException;
import com.newcodes7.small_town.auth.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    
    private final AuthService authService;
    
    @Value("${app.jwt.access-token-expiration:86400000}")
    private long accessTokenExpiration;
    
    @Value("${app.jwt.refresh-token-expiration:604800000}")
    private long refreshTokenExpiration;
    
    @PostMapping("/signup")
    public ResponseEntity<JwtResponseDto> signup(@Valid @RequestBody SignupRequestDto signupRequest, 
                                                 HttpServletResponse response) {
        try {
            JwtResponseDto jwtResponse = authService.signup(signupRequest);
            
            // 쿠키에 토큰 설정
            setTokenCookies(response, jwtResponse.getAccessToken(), jwtResponse.getRefreshToken());
            
            return ResponseEntity.ok(jwtResponse);
        } catch (AuthException e) {
            throw e;
        }
    }
    
    @PostMapping("/login")
    public ResponseEntity<JwtResponseDto> login(@Valid @RequestBody LoginRequestDto loginRequest, 
                                                HttpServletResponse response) {
        try {
            JwtResponseDto jwtResponse = authService.login(loginRequest);
            
            // 쿠키에 토큰 설정
            setTokenCookies(response, jwtResponse.getAccessToken(), jwtResponse.getRefreshToken());
            
            return ResponseEntity.ok(jwtResponse);
        } catch (AuthException e) {
            throw e;
        }
    }
    
    @PostMapping("/refresh")
    public ResponseEntity<JwtResponseDto> refreshToken(@CookieValue(name = "refreshToken", required = false) String refreshToken,
                                                       HttpServletResponse response) {
        if (refreshToken == null) {
            throw new InvalidTokenException("리프레시", "토큰이 제공되지 않음");
        }
        
        try {
            
            TokenRefreshRequestDto request = new TokenRefreshRequestDto();
            request.setRefreshToken(refreshToken);
            
            JwtResponseDto jwtResponse = authService.refreshToken(request);
            
            // 새로운 토큰을 쿠키에 설정
            setTokenCookies(response, jwtResponse.getAccessToken(), jwtResponse.getRefreshToken());
            
            return ResponseEntity.ok(jwtResponse);
        } catch (AuthException e) {
            throw e;
        }
    }
    
    @PostMapping("/logout")
    public ResponseEntity<String> logout(HttpServletResponse response,
                                        jakarta.servlet.http.HttpServletRequest request) {
        // 세션 무효화
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        // 쿠키 삭제
        clearTokenCookies(response);

        return ResponseEntity.ok("로그아웃되었습니다.");
    }
    
    @GetMapping("/me")
    public ResponseEntity<JwtResponseDto> getCurrentUser(@AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(401).build();
        }
        
        User user = (User) userDetails;
        JwtResponseDto response = new JwtResponseDto();
        response.setEmail(user.getEmail());
        response.setNickname(user.getNickname());
        response.setRole(user.getRole().getName());
        
        return ResponseEntity.ok(response);
    }
    
    @DeleteMapping("/withdraw")
    public ResponseEntity<String> withdraw(@AuthenticationPrincipal UserDetails userDetails,
                                           HttpServletResponse response) {
        authService.withdraw(userDetails.getUsername());
        clearTokenCookies(response);
        return ResponseEntity.ok("회원탈퇴가 완료되었습니다.");
    }
    
    private void setTokenCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        // Access Token 쿠키 설정
        Cookie accessTokenCookie = new Cookie("accessToken", accessToken);
        accessTokenCookie.setHttpOnly(true);
        accessTokenCookie.setSecure(false); // HTTPS 환경에서는 true로 설정
        accessTokenCookie.setPath("/");
        accessTokenCookie.setMaxAge((int) (accessTokenExpiration / 1000)); // 초 단위
        response.addCookie(accessTokenCookie);
        
        // Refresh Token 쿠키 설정
        Cookie refreshTokenCookie = new Cookie("refreshToken", refreshToken);
        refreshTokenCookie.setHttpOnly(true);
        refreshTokenCookie.setSecure(false); // HTTPS 환경에서는 true로 설정
        refreshTokenCookie.setPath("/");
        refreshTokenCookie.setMaxAge((int) (refreshTokenExpiration / 1000)); // 초 단위
        response.addCookie(refreshTokenCookie);
    }
    
    private void clearTokenCookies(HttpServletResponse response) {
        // Access Token 쿠키 삭제
        Cookie accessTokenCookie = new Cookie("accessToken", null);
        accessTokenCookie.setHttpOnly(true);
        accessTokenCookie.setSecure(false);
        accessTokenCookie.setPath("/");
        accessTokenCookie.setMaxAge(0);
        response.addCookie(accessTokenCookie);

        // Refresh Token 쿠키 삭제
        Cookie refreshTokenCookie = new Cookie("refreshToken", null);
        refreshTokenCookie.setHttpOnly(true);
        refreshTokenCookie.setSecure(false);
        refreshTokenCookie.setPath("/");
        refreshTokenCookie.setMaxAge(0);
        response.addCookie(refreshTokenCookie);

        // JSESSIONID 쿠키 삭제
        Cookie jsessionIdCookie = new Cookie("JSESSIONID", null);
        jsessionIdCookie.setHttpOnly(true);
        jsessionIdCookie.setSecure(false);
        jsessionIdCookie.setPath("/");
        jsessionIdCookie.setMaxAge(0);
        response.addCookie(jsessionIdCookie);
    }
}