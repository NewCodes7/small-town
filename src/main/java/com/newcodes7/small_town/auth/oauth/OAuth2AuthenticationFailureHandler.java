package com.newcodes7.small_town.auth.oauth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class OAuth2AuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        
        String errorMessage = "소셜 로그인에 실패했습니다.";
        if (exception.getMessage() != null) {
            errorMessage = exception.getMessage();
        }
        
        String encodedMessage = URLEncoder.encode(errorMessage, StandardCharsets.UTF_8);
        String targetUrl = "/auth/login?error=" + encodedMessage;
        
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}