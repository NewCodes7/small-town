package com.newcodes7.small_town.auth.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                        AuthenticationException authException) throws IOException, ServletException {
        
        String requestURI = request.getRequestURI();
        String acceptHeader = request.getHeader("Accept");
        
        // API 요청이고 JSON을 Accept하는 경우 401 응답
        if (requestURI.startsWith("/api/") && 
            acceptHeader != null && acceptHeader.contains("application/json")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Authentication required\",\"message\":\"로그인이 필요합니다.\"}");
        } else {
            // 일반 웹 요청은 로그인 페이지로 리다이렉트
            response.sendRedirect("/auth/login");
        }
    }
}