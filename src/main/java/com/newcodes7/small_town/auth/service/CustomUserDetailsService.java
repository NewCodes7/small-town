package com.newcodes7.small_town.auth.service;

import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {
    
    private final UserRepository userRepository;
    
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        log.debug("Loading user by username: {}", username);

        User user = null;

        // 1. email로 찾기
        user = userRepository.findByEmailAndDeletedAtIsNull(username).orElse(null);

        // 2. oauthUsername으로 찾기 (GitHub login 등)
        if (user == null) {
            user = userRepository.findByOauthUsernameAndDeletedAtIsNull(username).orElse(null);
        }

        // 3. oauthProviderId로 찾기
        if (user == null) {
            user = userRepository.findByOauthProviderIdAndDeletedAtIsNull(username).orElse(null);
        }

        // 4. username이 "user_숫자" 형태면 ID로 찾기
        if (user == null && username != null && username.startsWith("user_")) {
            try {
                String idStr = username.substring(5); // "user_" 제거
                if (!idStr.equals("unknown")) {
                    Long userId = Long.parseLong(idStr);
                    // JOIN FETCH를 사용하여 role과 provider를 함께 로드
                    user = userRepository.findByIdWithRoleAndProvider(userId)
                            .filter(u -> u.getDeletedAt() == null)
                            .orElse(null);
                }
            } catch (NumberFormatException e) {
                // ID 파싱 실패 시 무시
            }
        }

        if (user == null) {
            log.warn("User not found with username: {}", username);
            throw new UsernameNotFoundException("User not found with username: " + username);
        }

        log.debug("Found user: ID={}, email={}, status={}, role={}",
                user.getId(), user.getEmail(), user.getStatus(),
                user.getRole() != null ? user.getRole().getName() : "null");

        return user;
    }
}