package com.newcodes7.small_town.auth.config;

import com.newcodes7.small_town.auth.jwt.JwtAuthenticationFilter;
import com.newcodes7.small_town.auth.oauth.OAuth2AuthenticationFailureHandler;
import com.newcodes7.small_town.auth.oauth.OAuth2AuthenticationSuccessHandler;
import com.newcodes7.small_town.auth.service.CustomOAuth2UserService;
import com.newcodes7.small_town.auth.service.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    
    private final CustomUserDetailsService customUserDetailsService;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;
    private final OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;
    private final CustomAuthenticationEntryPoint customAuthenticationEntryPoint;
    private final CorsConfigurationSource corsConfigurationSource;
    
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
    
    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(customUserDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }
    
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/api/auth/**", "/auth/**", "/api/user-info", "/api/auth/me").permitAll()
                .requestMatchers("/", "/home", "/articles/**", "/about", "/corporations", "/corporations/**").permitAll()
                .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers("/sitemap.xml", "/robots.txt").permitAll()

                // 이미지 파일에 대한 접근은 모두 허용 (이미지 성능 테스트용으로 임시 추가)
                .requestMatchers("/*.jpeg").permitAll()

                // 글 목록 API는 모든 사용자 허용
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/articles/**").permitAll()
                // 카테고리 목록 조회는 모든 사용자 허용
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/categories").permitAll()
                
                // 좋아요 관련 API - 좋아요 토글은 인증 필요, 상태 조회는 모든 사용자 허용
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/articles/*/like").authenticated()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/articles/*/like-status").permitAll()
                // 조회수 증가 및 상태 조회는 모든 사용자 허용
                .requestMatchers("/api/articles/*/view").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/articles/*/view-status").permitAll()
                
                // 피드백 제출은 모든 사용자 허용
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/feedback").permitAll()
                
                // 기업 정보 조회는 모든 사용자 허용
                .requestMatchers("/corporations/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/corporations/**").permitAll()
                
                // ADMIN 전용 경로들
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/crawling/**").hasRole("ADMIN")
                .requestMatchers("/crawling/**").hasRole("ADMIN")
                .requestMatchers("/actuator/**").permitAll() // NginX 단에서 미리 막아뒀고, admin 서버에서는 접근 가능해야 하기에 permitAll
                
                // 기업 등록/수정/삭제는 ADMIN만 가능
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/corporations/**").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.PUT, "/api/corporations/**").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/api/corporations/**").hasRole("ADMIN")
                
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .loginPage("/auth/login")
                .userInfoEndpoint(userInfo -> userInfo
                    .userService(customOAuth2UserService)
                )
                .successHandler(oAuth2AuthenticationSuccessHandler)
                .failureHandler(oAuth2AuthenticationFailureHandler)
            )
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(customAuthenticationEntryPoint)
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        
        return http.build();
    }
}