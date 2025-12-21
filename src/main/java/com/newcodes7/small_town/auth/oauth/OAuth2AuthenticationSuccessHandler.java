package com.newcodes7.small_town.auth.oauth;

import com.newcodes7.small_town.auth.config.CustomAuthenticationEntryPoint;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.jwt.JwtTokenProvider;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtTokenProvider tokenProvider;

    @Value("${app.jwt.access-token-expiration:86400000}")
    private long accessTokenExpiration;

    @Value("${app.jwt.refresh-token-expiration:604800000}")
    private long refreshTokenExpiration;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {

        User user = (User) authentication.getPrincipal();

        // getUsername()을 사용하여 email이 없어도 fallback 값으로 토큰 생성
        String accessToken = tokenProvider.generateAccessToken(user.getUsername());
        String refreshToken = tokenProvider.generateRefreshToken(user.getUsername());

        // 쿠키에 토큰 설정
        setTokenCookies(response, accessToken, refreshToken);

        // 세션에서 원래 요청 URL 확인
        HttpSession session = request.getSession(false);
        String redirectUrl = "/";
        if (session != null) {
            String savedRedirectUrl = (String) session.getAttribute(CustomAuthenticationEntryPoint.REDIRECT_URL_SESSION_ATTRIBUTE);
            if (savedRedirectUrl != null && !savedRedirectUrl.isEmpty()) {
                redirectUrl = savedRedirectUrl;
                // 세션에서 제거
                session.removeAttribute(CustomAuthenticationEntryPoint.REDIRECT_URL_SESSION_ATTRIBUTE);
            }
        }

        // 원래 요청했던 페이지 또는 홈페이지로 리디렉션
        getRedirectStrategy().sendRedirect(request, response, redirectUrl);
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
}