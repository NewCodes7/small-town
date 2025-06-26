package com.newcodes7.small_town.auth.service;

import com.newcodes7.small_town.auth.dto.*;
import com.newcodes7.small_town.auth.entity.Provider;
import com.newcodes7.small_town.auth.entity.Role;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.jwt.JwtTokenProvider;
import com.newcodes7.small_town.auth.repository.ProviderRepository;
import com.newcodes7.small_town.auth.repository.RoleRepository;
import com.newcodes7.small_town.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {
    
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ProviderRepository providerRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    
    public JwtResponseDto signup(SignupRequestDto signupRequest) {
        if (!signupRequest.getPassword().equals(signupRequest.getConfirmPassword())) {
            throw new RuntimeException("비밀번호가 일치하지 않습니다.");
        }
        
        if (userRepository.existsByEmailAndDeletedAtIsNull(signupRequest.getEmail())) {
            throw new RuntimeException("이미 존재하는 이메일입니다.");
        }
        
        Role userRole = roleRepository.findByName("USER")
                .orElseThrow(() -> new RuntimeException("USER 역할을 찾을 수 없습니다."));
        
        Provider localProvider = providerRepository.findByName("LOCAL")
                .orElseThrow(() -> new RuntimeException("LOCAL 제공자를 찾을 수 없습니다."));
        
        User user = User.builder()
                .email(signupRequest.getEmail())
                .password(passwordEncoder.encode(signupRequest.getPassword()))
                .nickname(signupRequest.getNickname())
                .role(userRole)
                .provider(localProvider)
                .build();
        
        User savedUser = userRepository.save(user);
        
        String accessToken = tokenProvider.generateAccessToken(savedUser.getEmail());
        String refreshToken = tokenProvider.generateRefreshToken(savedUser.getEmail());
        
        return new JwtResponseDto(accessToken, refreshToken, savedUser.getEmail(), 
                                 savedUser.getNickname(), savedUser.getRole().getName());
    }
    
    public JwtResponseDto login(LoginRequestDto loginRequest) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginRequest.getEmail(),
                            loginRequest.getPassword()
                    )
            );
            
            User user = (User) authentication.getPrincipal();
            user.updateLastLoginAt();
            userRepository.save(user); // 변경사항 저장
            
            String accessToken = tokenProvider.generateAccessToken(authentication);
            String refreshToken = tokenProvider.generateRefreshToken(authentication);
            
            return new JwtResponseDto(accessToken, refreshToken, user.getEmail(), 
                                     user.getNickname(), user.getRole().getName());
        } catch (Exception e) {
            System.err.println("Login error: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("로그인에 실패했습니다: " + e.getMessage());
        }
    }
    
    public JwtResponseDto refreshToken(TokenRefreshRequestDto request) {
        String refreshToken = request.getRefreshToken();
        
        if (!tokenProvider.validateToken(refreshToken) || !tokenProvider.isRefreshToken(refreshToken)) {
            throw new RuntimeException("유효하지 않은 리프레시 토큰입니다.");
        }
        
        String email = tokenProvider.getEmailFromToken(refreshToken);
        User user = userRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
        
        String newAccessToken = tokenProvider.generateAccessToken(email);
        String newRefreshToken = tokenProvider.generateRefreshToken(email);
        
        return new JwtResponseDto(newAccessToken, newRefreshToken, user.getEmail(), 
                                 user.getNickname(), user.getRole().getName());
    }
    
    public void withdraw(String email) {
        User user = userRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
        
        user.withdraw();
    }
}