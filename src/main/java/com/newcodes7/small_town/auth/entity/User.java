package com.newcodes7.small_town.auth.entity;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_email", columnList = "email"),
    @Index(name = "idx_status", columnList = "status"),
    @Index(name = "idx_deleted_at", columnList = "deleted_at"),
    @Index(name = "idx_provider_oauth_id", columnList = "provider_id,oauth_provider_id", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User implements UserDetails, OAuth2User {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "nickname", nullable = false, length = 50)
    private String nickname;

    @Column(name = "email", length = 100)
    private String email;

    @Column(name = "password", length = 255)
    private String password;

    // OAuth provider의 고유 ID (GitHub: id, Google: sub 등)
    @Column(name = "oauth_provider_id", length = 100)
    private String oauthProviderId;
    
    @Convert(converter = UserStatusConverter.class)
    @Column(name = "status", nullable = false)
    private UserStatus status;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_id")
    private Provider provider;
    
    @Column(name = "profile_image_url", length = 255)
    private String profileImageUrl;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    // OAuth2 추가 정보 (GitHub, Google 등)
    @Column(name = "oauth_username", length = 100)
    private String oauthUsername; // GitHub: login, Google: sub

    @Column(name = "bio", columnDefinition = "TEXT")
    private String bio; // GitHub: bio

    @Column(name = "blog_url", length = 255)
    private String blogUrl; // GitHub: blog

    @Column(name = "company", length = 100)
    private String company; // GitHub: company

    @Column(name = "location", length = 100)
    private String location; // GitHub: location

    @Column(name = "profile_url", length = 255)
    private String profileUrl; // GitHub: html_url

    @Column(name = "public_repos")
    private Integer publicRepos; // GitHub: public_repos

    @Column(name = "followers")
    private Integer followers; // GitHub: followers

    @Column(name = "following")
    private Integer following; // GitHub: following

    @Column(name = "twitter_username", length = 100)
    private String twitterUsername; // GitHub: twitter_username

    @Column(name = "hireable")
    private Boolean hireable; // GitHub: hireable

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
    
    @Builder
    public User(String nickname, String email, String password, Role role, Provider provider, String profileImageUrl, String oauthProviderId) {
        this.nickname = nickname;
        this.email = email;
        this.password = password;
        this.role = role;
        this.provider = provider;
        this.profileImageUrl = profileImageUrl;
        this.oauthProviderId = oauthProviderId;
        this.status = UserStatus.ACTIVE;
    }
    
    public void updateLastLoginAt() {
        this.lastLoginAt = LocalDateTime.now();
    }
    
    public void withdraw() {
        this.status = UserStatus.WITHDRAWN;
        this.deletedAt = LocalDateTime.now();
    }
    
    public void ban() {
        this.status = UserStatus.BANNED;
    }
    
    public void activate() {
        this.status = UserStatus.ACTIVE;
        this.deletedAt = null;
    }

    public void updateOAuth2Profile(String nickname, String profileImageUrl, Provider provider) {
        if (nickname != null) {
            this.nickname = nickname;
        }
        if (profileImageUrl != null) {
            this.profileImageUrl = profileImageUrl;
        }
        if (provider != null) {
            this.provider = provider;
        }
    }

    /**
     * OAuth2 전체 프로필 정보 업데이트 (GitHub 등)
     */
    public void updateFullOAuth2Profile(
            String nickname,
            String profileImageUrl,
            Provider provider,
            String oauthProviderId,
            String oauthUsername,
            String bio,
            String blogUrl,
            String company,
            String location,
            String profileUrl,
            Integer publicRepos,
            Integer followers,
            Integer following,
            String twitterUsername,
            Boolean hireable) {

        // 기본 정보
        if (nickname != null) {
            this.nickname = nickname;
        }
        if (profileImageUrl != null) {
            this.profileImageUrl = profileImageUrl;
        }
        if (provider != null) {
            this.provider = provider;
        }

        // OAuth provider 고유 ID
        if (oauthProviderId != null) {
            this.oauthProviderId = oauthProviderId;
        }

        // 추가 정보
        this.oauthUsername = oauthUsername;
        this.bio = bio;
        this.blogUrl = blogUrl;
        this.company = company;
        this.location = location;
        this.profileUrl = profileUrl;
        this.publicRepos = publicRepos;
        this.followers = followers;
        this.following = following;
        this.twitterUsername = twitterUsername;
        this.hireable = hireable;
    }

    public boolean isActive() {
        return this.status == UserStatus.ACTIVE;
    }
    
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (role == null) {
            return Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
        }
        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role.getName()));
    }
    
    @Override
    public String getUsername() {
        // Spring Security에서 사용자 식별자로 사용되므로 null이면 안 됨
        // 우선순위: email → oauthUsername → oauthProviderId → "user_" + id
        if (email != null && !email.isEmpty()) {
            return email;
        }
        if (oauthUsername != null && !oauthUsername.isEmpty()) {
            return oauthUsername;
        }
        if (oauthProviderId != null && !oauthProviderId.isEmpty()) {
            return oauthProviderId;
        }
        // 최후의 수단: user ID 사용
        return "user_" + (id != null ? id : "unknown");
    }
    
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }
    
    @Override
    public boolean isAccountNonLocked() {
        return status != UserStatus.BANNED;
    }
    
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
    
    @Override
    public boolean isEnabled() {
        return status == UserStatus.ACTIVE;
    }
    
    // OAuth2User 인터페이스 구현
    @Override
    public Map<String, Object> getAttributes() {
        return Collections.emptyMap();
    }

    @Override
    public String getName() {
        // principalName으로 사용되므로 null이면 안 됨
        // 우선순위: nickname → email → oauthUsername → oauthProviderId → "user_" + id
        if (nickname != null && !nickname.isEmpty()) {
            return nickname;
        }
        if (email != null && !email.isEmpty()) {
            return email;
        }
        if (oauthUsername != null && !oauthUsername.isEmpty()) {
            return oauthUsername;
        }
        if (oauthProviderId != null && !oauthProviderId.isEmpty()) {
            return oauthProviderId;
        }
        // 최후의 수단: user ID 사용
        return "user_" + (id != null ? id : "unknown");
    }
    
    public enum UserStatus {
        ACTIVE("active"),
        BANNED("banned"), 
        WITHDRAWN("탈퇴");
        
        private final String value;
        
        UserStatus(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
    }
}