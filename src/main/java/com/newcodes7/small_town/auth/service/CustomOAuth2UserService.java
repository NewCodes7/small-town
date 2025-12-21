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

        if (!StringUtils.hasText(oauth2UserInfo.getEmail())) {
            throw new OAuth2AuthenticationException("OAuth2 제공자에서 이메일을 찾을 수 없습니다.");
        }

        Optional<User> userOptional = userRepository.findByEmailWithRoleAndProvider(oauth2UserInfo.getEmail());
        User user;

        if (userOptional.isPresent()) {
            user = userOptional.get();

            // 삭제된 계정이면 재활성화
            if (user.getDeletedAt() != null) {
                user = reactivateUser(user, oauth2UserInfo, registrationId);
            } else {
                user = updateExistingUser(user, oauth2UserInfo, registrationId);
            }
        } else {
            user = registerNewUser(oauth2UserInfo, registrationId);
        }

        return user;
    }

    private User registerNewUser(OAuth2UserInfo oauth2UserInfo, String registrationId) {
        Role userRole = roleRepository.findByName("USER")
                .orElseThrow(() -> new RuntimeException("USER 역할을 찾을 수 없습니다."));

        Provider provider = getProviderByRegistrationId(registrationId);

        User user = User.builder()
                .email(oauth2UserInfo.getEmail())
                .nickname(oauth2UserInfo.getName())
                .profileImageUrl(oauth2UserInfo.getImageUrl())
                .role(userRole)
                .provider(provider)
                .build();

        // GitHub 추가 정보 저장
        if (oauth2UserInfo instanceof GitHubOAuth2UserInfo) {
            GitHubOAuth2UserInfo githubInfo = (GitHubOAuth2UserInfo) oauth2UserInfo;
            user.updateFullOAuth2Profile(
                oauth2UserInfo.getName(),
                oauth2UserInfo.getImageUrl(),
                provider,
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
            log.info("GitHub 사용자 등록: {}, login: {}, followers: {}",
                oauth2UserInfo.getEmail(), githubInfo.getLogin(), githubInfo.getFollowers());
        }

        return userRepository.save(user);
    }

    private User reactivateUser(User deletedUser, OAuth2UserInfo oauth2UserInfo, String registrationId) {
        Provider provider = getProviderByRegistrationId(registrationId);

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
            log.info("GitHub 사용자 재활성화: {}, login: {}",
                oauth2UserInfo.getEmail(), githubInfo.getLogin());
        } else {
            // 기본 프로필 정보 업데이트 (Google 등)
            deletedUser.updateOAuth2Profile(
                oauth2UserInfo.getName(),
                oauth2UserInfo.getImageUrl(),
                newProvider
            );
        }

        // 마지막 로그인 시간 업데이트
        deletedUser.updateLastLoginAt();

        return userRepository.save(deletedUser);
    }

    private User updateExistingUser(User existingUser, OAuth2UserInfo oauth2UserInfo, String registrationId) {
        Provider provider = getProviderByRegistrationId(registrationId);

        // 기존 사용자가 다른 제공자로 가입한 경우 예외 처리
        if (existingUser.getProvider() != null &&
            !existingUser.getProvider().getName().equals(provider.getName()) &&
            !existingUser.getProvider().getName().equals("LOCAL")) {
            throw new OAuth2AuthenticationException(
                "이미 " + existingUser.getProvider().getName() + " 계정으로 가입된 이메일입니다."
            );
        }

        // GitHub 정보 업데이트 (로그인할 때마다 최신 정보로 갱신)
        if (oauth2UserInfo instanceof GitHubOAuth2UserInfo) {
            GitHubOAuth2UserInfo githubInfo = (GitHubOAuth2UserInfo) oauth2UserInfo;
            existingUser.updateFullOAuth2Profile(
                oauth2UserInfo.getName(),
                oauth2UserInfo.getImageUrl(),
                provider,
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
            log.debug("GitHub 사용자 정보 갱신: {}, login: {}, followers: {}",
                oauth2UserInfo.getEmail(), githubInfo.getLogin(), githubInfo.getFollowers());
        } else {
            // 기본 프로필 정보 업데이트 (Google 등)
            existingUser.updateOAuth2Profile(
                oauth2UserInfo.getName(),
                oauth2UserInfo.getImageUrl(),
                provider
            );
        }

        // 마지막 로그인 시간 업데이트
        existingUser.updateLastLoginAt();

        return userRepository.save(existingUser);
    }

    private Provider getProviderByRegistrationId(String registrationId) {
        String providerName = registrationId.toUpperCase();
        return providerRepository.findByName(providerName)
                .orElseThrow(() -> new RuntimeException(providerName + " 제공자를 찾을 수 없습니다."));
    }
}