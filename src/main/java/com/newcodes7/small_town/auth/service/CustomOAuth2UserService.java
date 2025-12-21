package com.newcodes7.small_town.auth.service;

import com.newcodes7.small_town.auth.entity.Provider;
import com.newcodes7.small_town.auth.entity.Role;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.oauth.GitHubOAuth2UserInfo;
import com.newcodes7.small_town.auth.oauth.OAuth2UserInfo;
import com.newcodes7.small_town.auth.oauth.OAuth2UserInfoFactory;
import com.newcodes7.small_town.auth.repository.ProviderRepository;
import com.newcodes7.small_town.auth.repository.RoleRepository;
import com.newcodes7.small_town.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ProviderRepository providerRepository;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oauth2User = super.loadUser(userRequest);

        try {
            return processOAuth2User(userRequest, oauth2User);
        } catch (Exception ex) {
            throw new OAuth2AuthenticationException("OAuth2 사용자 처리 중 오류가 발생했습니다: " + ex.getMessage());
        }
    }

    private OAuth2User processOAuth2User(OAuth2UserRequest userRequest, OAuth2User oauth2User) {
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        OAuth2UserInfo oauth2UserInfo = OAuth2UserInfoFactory.getOAuth2UserInfo(registrationId, oauth2User.getAttributes());

        log.info("[OAuth2 로그인 시작] Provider: {}, OAuth ID: {}, Email: {}, Name: {}",
                registrationId, oauth2UserInfo.getId(), oauth2UserInfo.getEmail(), oauth2UserInfo.getName());

        // OAuth provider ID가 없으면 처리 불가
        if (!StringUtils.hasText(oauth2UserInfo.getId())) {
            log.error("[OAuth2 로그인 실패] Provider ID가 없습니다. Provider: {}", registrationId);
            throw new OAuth2AuthenticationException("OAuth2 제공자에서 고유 ID를 찾을 수 없습니다.");
        }

        Provider provider = getProviderByRegistrationId(registrationId);
        Optional<User> userOptional = userRepository.findByProviderAndOauthProviderId(provider, oauth2UserInfo.getId());

        // OAuth provider ID로 사용자를 찾지 못한 경우, 이메일로 기존 사용자 검색 (마이그레이션 지원)
        if (!userOptional.isPresent() && StringUtils.hasText(oauth2UserInfo.getEmail())) {
            log.debug("[OAuth2 로그인] Provider ID로 사용자를 찾지 못함. 이메일로 검색 시도: {}", oauth2UserInfo.getEmail());
            userOptional = userRepository.findByEmailWithRoleAndProvider(oauth2UserInfo.getEmail());
        }

        User user;

        if (userOptional.isPresent()) {
            user = userOptional.get();
            log.info("[OAuth2 로그인] 기존 사용자 발견: ID={}, Email={}, Status={}",
                    user.getId(), user.getEmail(), user.getStatus());

            // 삭제된 계정이면 재활성화
            if (user.getDeletedAt() != null) {
                log.info("[OAuth2 로그인] 삭제된 계정 재활성화: ID={}", user.getId());
                user = reactivateUser(user, oauth2UserInfo, registrationId);
            } else {
                user = updateExistingUser(user, oauth2UserInfo, registrationId);
            }
        } else {
            log.info("[OAuth2 로그인] 신규 사용자 등록: Provider={}, OAuth ID={}", registrationId, oauth2UserInfo.getId());
            user = registerNewUser(oauth2UserInfo, registrationId);
        }

        log.info("[OAuth2 로그인 완료] User ID: {}, Email: {}, Provider: {}",
                user.getId(), user.getEmail(), user.getProvider().getName());

        return user;
    }

    private User registerNewUser(OAuth2UserInfo oauth2UserInfo, String registrationId) {
        Role userRole = roleRepository.findByName("USER")
                .orElseThrow(() -> new RuntimeException("USER 역할을 찾을 수 없습니다."));

        Provider provider = getProviderByRegistrationId(registrationId);

        // 이메일이 없는 경우 username을 기반으로 임시 이메일 생성
        String email = oauth2UserInfo.getEmail();
        if (!StringUtils.hasText(email)) {
            email = null;
            log.warn("[OAuth2 신규 등록] 이메일이 없는 사용자: Provider={}, OAuth ID={}, Name={}",
                    registrationId, oauth2UserInfo.getId(), oauth2UserInfo.getName());
        }

        User user = User.builder()
                .email(email)
                .nickname(oauth2UserInfo.getName())
                .profileImageUrl(oauth2UserInfo.getImageUrl())
                .role(userRole)
                .provider(provider)
                .oauthProviderId(oauth2UserInfo.getId())
                .build();

        // GitHub 추가 정보 저장
        if (oauth2UserInfo instanceof GitHubOAuth2UserInfo) {
            GitHubOAuth2UserInfo githubInfo = (GitHubOAuth2UserInfo) oauth2UserInfo;
            user.updateFullOAuth2Profile(
                oauth2UserInfo.getName(),
                oauth2UserInfo.getImageUrl(),
                provider,
                oauth2UserInfo.getId(),
                githubInfo.getLogin(),
                githubInfo.getBio(),
                githubInfo.getBlog(),
                githubInfo.getCompany(),
                githubInfo.getLocation(),
                githubInfo.getHtmlUrl(),
                githubInfo.getPublicRepos(),
                githubInfo.getFollowers(),
                githubInfo.getFollowing(),
                githubInfo.getTwitterUsername(),
                githubInfo.getHireable()
            );
            log.info("[GitHub 신규 등록] User ID: {}, OAuth ID: {}, Login: {}, Email: {}, Followers: {}",
                user.getId(), oauth2UserInfo.getId(), githubInfo.getLogin(), email, githubInfo.getFollowers());
        } else {
            log.info("[OAuth2 신규 등록] Provider: {}, OAuth ID: {}, Email: {}",
                    registrationId, oauth2UserInfo.getId(), email);
        }

        User savedUser = userRepository.save(user);
        log.info("[OAuth2 신규 등록 완료] User ID: {}, Provider: {}, OAuth ID: {}",
                savedUser.getId(), provider.getName(), savedUser.getOauthProviderId());

        return savedUser;
    }

    private User reactivateUser(User deletedUser, OAuth2UserInfo oauth2UserInfo, String registrationId) {
        Provider provider = getProviderByRegistrationId(registrationId);

        log.info("[OAuth2 재활성화] User ID: {}, Email: {}, Provider: {}",
                deletedUser.getId(), deletedUser.getEmail(), provider.getName());

        // 계정 재활성화
        deletedUser.activate();

        // provider 정보가 없거나 LOCAL이면 소셜 provider로 업데이트
        Provider newProvider = null;
        if (deletedUser.getProvider() == null ||
            deletedUser.getProvider().getName().equals("LOCAL")) {
            newProvider = provider;
        }

        // GitHub 전체 정보 업데이트
        if (oauth2UserInfo instanceof GitHubOAuth2UserInfo) {
            GitHubOAuth2UserInfo githubInfo = (GitHubOAuth2UserInfo) oauth2UserInfo;
            deletedUser.updateFullOAuth2Profile(
                oauth2UserInfo.getName(),
                oauth2UserInfo.getImageUrl(),
                newProvider != null ? newProvider : deletedUser.getProvider(),
                oauth2UserInfo.getId(),
                githubInfo.getLogin(),
                githubInfo.getBio(),
                githubInfo.getBlog(),
                githubInfo.getCompany(),
                githubInfo.getLocation(),
                githubInfo.getHtmlUrl(),
                githubInfo.getPublicRepos(),
                githubInfo.getFollowers(),
                githubInfo.getFollowing(),
                githubInfo.getTwitterUsername(),
                githubInfo.getHireable()
            );
            log.info("[GitHub 재활성화] User ID: {}, OAuth ID: {}, Login: {}, Email: {}",
                deletedUser.getId(), oauth2UserInfo.getId(), githubInfo.getLogin(), oauth2UserInfo.getEmail());
        } else {
            // 기본 프로필 정보 업데이트 (Google 등)
            deletedUser.updateOAuth2Profile(
                oauth2UserInfo.getName(),
                oauth2UserInfo.getImageUrl(),
                newProvider
            );
            log.info("[OAuth2 재활성화] User ID: {}, OAuth ID: {}, Email: {}",
                    deletedUser.getId(), oauth2UserInfo.getId(), oauth2UserInfo.getEmail());
        }

        // 마지막 로그인 시간 업데이트
        deletedUser.updateLastLoginAt();

        User savedUser = userRepository.save(deletedUser);
        log.info("[OAuth2 재활성화 완료] User ID: {}", savedUser.getId());

        return savedUser;
    }

    private User updateExistingUser(User existingUser, OAuth2UserInfo oauth2UserInfo, String registrationId) {
        Provider provider = getProviderByRegistrationId(registrationId);

        log.debug("[OAuth2 정보 갱신] User ID: {}, Email: {}, Provider: {}",
                existingUser.getId(), existingUser.getEmail(), provider.getName());

        // 기존 사용자가 다른 제공자로 가입한 경우 예외 처리
        if (existingUser.getProvider() != null &&
            !existingUser.getProvider().getName().equals(provider.getName()) &&
            !existingUser.getProvider().getName().equals("LOCAL")) {
            log.error("[OAuth2 로그인 실패] 다른 Provider로 가입된 사용자: 기존 Provider={}, 시도한 Provider={}",
                    existingUser.getProvider().getName(), provider.getName());
            throw new OAuth2AuthenticationException(
                "이미 " + existingUser.getProvider().getName() + " 계정으로 가입된 사용자입니다."
            );
        }

        // GitHub 정보 업데이트 (로그인할 때마다 최신 정보로 갱신)
        if (oauth2UserInfo instanceof GitHubOAuth2UserInfo) {
            GitHubOAuth2UserInfo githubInfo = (GitHubOAuth2UserInfo) oauth2UserInfo;
            existingUser.updateFullOAuth2Profile(
                oauth2UserInfo.getName(),
                oauth2UserInfo.getImageUrl(),
                provider,
                oauth2UserInfo.getId(),
                githubInfo.getLogin(),
                githubInfo.getBio(),
                githubInfo.getBlog(),
                githubInfo.getCompany(),
                githubInfo.getLocation(),
                githubInfo.getHtmlUrl(),
                githubInfo.getPublicRepos(),
                githubInfo.getFollowers(),
                githubInfo.getFollowing(),
                githubInfo.getTwitterUsername(),
                githubInfo.getHireable()
            );
            log.debug("[GitHub 정보 갱신] User ID: {}, OAuth ID: {}, Login: {}, Email: {}, Followers: {}",
                existingUser.getId(), oauth2UserInfo.getId(), githubInfo.getLogin(), oauth2UserInfo.getEmail(), githubInfo.getFollowers());
        } else {
            // 기본 프로필 정보 업데이트 (Google 등)
            existingUser.updateOAuth2Profile(
                oauth2UserInfo.getName(),
                oauth2UserInfo.getImageUrl(),
                provider
            );
            log.debug("[OAuth2 정보 갱신] User ID: {}, OAuth ID: {}, Email: {}",
                    existingUser.getId(), oauth2UserInfo.getId(), oauth2UserInfo.getEmail());
        }

        // 마지막 로그인 시간 업데이트
        existingUser.updateLastLoginAt();

        User savedUser = userRepository.save(existingUser);
        log.debug("[OAuth2 정보 갱신 완료] User ID: {}", savedUser.getId());

        return savedUser;
    }

    private Provider getProviderByRegistrationId(String registrationId) {
        String providerName = registrationId.toUpperCase();
        return providerRepository.findByName(providerName)
                .orElseThrow(() -> new RuntimeException(providerName + " 제공자를 찾을 수 없습니다."));
    }
}