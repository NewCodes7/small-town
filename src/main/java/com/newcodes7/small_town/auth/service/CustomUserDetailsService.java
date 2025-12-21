package com.newcodes7.small_town.auth.service;

import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {
    
    private final UserRepository userRepository;
    
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        System.out.println("Loading user by username: " + username);

        User user = null;

        // 1. email로 찾기
        if (username != null && !username.startsWith("user_")) {
            user = userRepository.findByEmailAndDeletedAtIsNull(username).orElse(null);
        }

        // 2. username이 "user_숫자" 형태면 ID로 찾기
        if (user == null && username != null && username.startsWith("user_")) {
            try {
                String idStr = username.substring(5); // "user_" 제거
                if (!idStr.equals("unknown")) {
                    Long userId = Long.parseLong(idStr);
                    user = userRepository.findById(userId)
                            .filter(u -> u.getDeletedAt() == null)
                            .orElse(null);
                }
            } catch (NumberFormatException e) {
                // ID 파싱 실패 시 무시
            }
        }

        if (user == null) {
            System.err.println("User not found with username: " + username);
            throw new UsernameNotFoundException("User not found with username: " + username);
        }

        System.out.println("Found user: ID=" + user.getId() + ", email=" + user.getEmail() +
                          ", status: " + user.getStatus() +
                          ", role: " + (user.getRole() != null ? user.getRole().getName() : "null"));

        return user;
    }
}