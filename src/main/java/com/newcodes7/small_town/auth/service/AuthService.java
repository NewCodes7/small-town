package com.newcodes7.small_town.auth.service;

import com.newcodes7.small_town.auth.dto.*;
import com.newcodes7.small_town.auth.entity.Provider;
import com.newcodes7.small_town.auth.entity.Role;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.exception.*;
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
            throw new PasswordMismatchException();
        }
        
        if (userRepository.existsByEmailAndDeletedAtIsNull(signupRequest.getEmail())) {
            throw new DuplicateEmailException(signupRequest.getEmail());
        }
        
        Role userRole = roleRepository.findByName("USER")
                .orElseThrow(() -> new RoleNotFoundException("USER"));
        
        Provider localProvider = providerRepository.findByName("LOCAL")
                .orElseThrow(() -> new ProviderNotFoundException("LOCAL"));
        
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
            userRepository.save(user);
            
            String accessToken = tokenProvider.generateAccessToken(authentication);
            String refreshToken = tokenProvider.generateRefreshToken(authentication);
            
            return new JwtResponseDto(accessToken, refreshToken, user.getEmail(), 
                                     user.getNickname(), user.getRole().getName());
        } catch (Exception e) {
            throw new AuthenticationFailedException(loginRequest.getEmail(), e);
        }
    }
    
    public JwtResponseDto refreshToken(TokenRefreshRequestDto request) {
        String refreshToken = request.getRefreshToken();
        
        if (!tokenProvider.validateToken(refreshToken) || !tokenProvider.isRefreshToken(refreshToken)) {
            throw new InvalidTokenException("리프레시");
        }
        
        String email = tokenProvider.getEmailFromToken(refreshToken);
        User user = userRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> new UserNotFoundException(email));
        
        String newAccessToken = tokenProvider.generateAccessToken(email);
        String newRefreshToken = tokenProvider.generateRefreshToken(email);
        
        return new JwtResponseDto(newAccessToken, newRefreshToken, user.getEmail(), 
                                 user.getNickname(), user.getRole().getName());
    }
    
    public void withdraw(String email) {
        User user = userRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> new UserNotFoundException(email));
        
        user.withdraw();
    }
}