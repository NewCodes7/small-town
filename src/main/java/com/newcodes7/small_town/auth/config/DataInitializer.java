package com.newcodes7.small_town.auth.config;

import com.newcodes7.small_town.auth.entity.Provider;
import com.newcodes7.small_town.auth.entity.Role;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.ProviderRepository;
import com.newcodes7.small_town.auth.repository.RoleRepository;
import com.newcodes7.small_town.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {
    
    private final RoleRepository roleRepository;
    private final ProviderRepository providerRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    
    @Override
    public void run(String... args) throws Exception {
        initializeRoles();
        initializeProviders();
        initializeAdminUser();
    }
    
    private void initializeRoles() {
        if (roleRepository.findByName("USER").isEmpty()) {
            roleRepository.save(new Role("USER"));
        }
        if (roleRepository.findByName("ADMIN").isEmpty()) {
            roleRepository.save(new Role("ADMIN"));
        }
    }
    
    private void initializeProviders() {
        if (providerRepository.findByName("LOCAL").isEmpty()) {
            providerRepository.save(new Provider("LOCAL"));
        }
        if (providerRepository.findByName("GOOGLE").isEmpty()) {
            providerRepository.save(new Provider("GOOGLE"));
        }
        if (providerRepository.findByName("GITHUB").isEmpty()) {
            providerRepository.save(new Provider("GITHUB"));
        }
        if (providerRepository.findByName("KAKAO").isEmpty()) {
            providerRepository.save(new Provider("KAKAO"));
        }
    }
    
    private void initializeAdminUser() {
        // 기본 ADMIN 사용자가 없으면 생성
        if (!userRepository.existsByEmailAndDeletedAtIsNull("admin@smalltown.com")) {
            Role adminRole = roleRepository.findByName("ADMIN")
                    .orElseThrow(() -> new RuntimeException("ADMIN role not found"));
            Provider localProvider = providerRepository.findByName("LOCAL")
                    .orElseThrow(() -> new RuntimeException("LOCAL provider not found"));
            
            User adminUser = User.builder()
                    .email("admin@smalltown.com")
                    .password(passwordEncoder.encode("admin123!"))
                    .nickname("관리자")
                    .role(adminRole)
                    .provider(localProvider)
                    .build();
            
            userRepository.save(adminUser);
            System.out.println("기본 ADMIN 사용자가 생성되었습니다.");
            System.out.println("이메일: admin@smalltown.com");
            System.out.println("비밀번호: admin123!");
        }
    }
}