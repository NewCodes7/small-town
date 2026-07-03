package com.newcodes7.small_town.auth.service;

import static org.assertj.core.api.Assertions.*;

import com.newcodes7.small_town.auth.dto.*;
import com.newcodes7.small_town.auth.entity.Provider;
import com.newcodes7.small_town.auth.entity.Role;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.exception.*;
import com.newcodes7.small_town.auth.jwt.JwtTokenProvider;
import com.newcodes7.small_town.auth.repository.ProviderRepository;
import com.newcodes7.small_town.auth.repository.RoleRepository;
import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.auth.util.UserTestHelper;
import com.newcodes7.small_town.config.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

public class AuthServiceTest extends IntegrationTestBase {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Role userRole;
    private Provider localProvider;

    @BeforeEach
    void setUp() {
        // 기본 Role과 Provider 설정
        userRole = roleRepository.findByName("USER")
                .orElseGet(() -> roleRepository.save(new Role("USER")));

        localProvider = providerRepository.findByName("LOCAL")
                .orElseGet(() -> providerRepository.save(new Provider("LOCAL")));

        // 기존 사용자 데이터 정리
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("회원가입 성공 - 정상적인 회원가입 처리")
    void signup_Success() {
        // given
        SignupRequestDto signupRequest = new SignupRequestDto();
        signupRequest.setEmail("test@example.com");
        signupRequest.setPassword("password123");
        signupRequest.setConfirmPassword("password123");
        signupRequest.setNickname("테스트유저");

        // when
        JwtResponseDto response = authService.signup(signupRequest);

        // then
        assertThat(response).isNotNull();
        assertThat(response.getEmail()).isEqualTo("test@example.com");
        assertThat(response.getNickname()).isEqualTo("테스트유저");
        assertThat(response.getRole()).isEqualTo("USER");
        assertThat(response.getAccessToken()).isNotBlank();
        assertThat(response.getRefreshToken()).isNotBlank();

        // 토큰 검증
        assertThat(jwtTokenProvider.validateToken(response.getAccessToken())).isTrue();
        assertThat(jwtTokenProvider.validateToken(response.getRefreshToken())).isTrue();
        assertThat(jwtTokenProvider.isAccessToken(response.getAccessToken())).isTrue();
        assertThat(jwtTokenProvider.isRefreshToken(response.getRefreshToken())).isTrue();

        // DB 저장 확인
        User savedUser = userRepository.findByUsernameAndDeletedAtIsNull("test@example.com").orElse(null);
        assertThat(savedUser).isNotNull();
        assertThat(savedUser.getEmail()).isEqualTo("test@example.com");
        assertThat(savedUser.getNickname()).isEqualTo("테스트유저");
        assertThat(passwordEncoder.matches("password123", savedUser.getPassword())).isTrue();
    }

    @Test
    @DisplayName("회원가입 실패 - 비밀번호 불일치")
    void signup_PasswordMismatch() {
        // given
        SignupRequestDto signupRequest = new SignupRequestDto();
        signupRequest.setEmail("test@example.com");
        signupRequest.setPassword("password123");
        signupRequest.setConfirmPassword("differentPassword");
        signupRequest.setNickname("테스트유저");

        // when & then
        assertThatThrownBy(() -> authService.signup(signupRequest))
                .isInstanceOf(PasswordMismatchException.class);
    }

    @Test
    @DisplayName("회원가입 실패 - 중복된 이메일")
    void signup_DuplicateEmail() {
        // given
        User existingUser = UserTestHelper.createTestUser(
                "test@example.com",
                passwordEncoder.encode("password123"),
                "기존유저",
                userRole,
                localProvider
        );
        userRepository.save(existingUser);

        SignupRequestDto signupRequest = new SignupRequestDto();
        signupRequest.setEmail("test@example.com");
        signupRequest.setPassword("password123");
        signupRequest.setConfirmPassword("password123");
        signupRequest.setNickname("신규유저");

        // when & then
        assertThatThrownBy(() -> authService.signup(signupRequest))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    @DisplayName("로그인 성공 - 정상적인 로그인 처리")
    void login_Success() {
        // given
        User user = UserTestHelper.createTestUser(
                "test@example.com",
                passwordEncoder.encode("password123"),
                "테스트유저",
                userRole,
                localProvider
        );
        userRepository.save(user);

        LoginRequestDto loginRequest = new LoginRequestDto();
        loginRequest.setEmail("test@example.com");
        loginRequest.setPassword("password123");

        // when
        JwtResponseDto response = authService.login(loginRequest);

        // then
        assertThat(response).isNotNull();
        assertThat(response.getEmail()).isEqualTo("test@example.com");
        assertThat(response.getNickname()).isEqualTo("테스트유저");
        assertThat(response.getRole()).isEqualTo("USER");
        assertThat(response.getAccessToken()).isNotBlank();
        assertThat(response.getRefreshToken()).isNotBlank();

        // 마지막 로그인 시간 업데이트 확인
        User updatedUser = userRepository.findByUsernameAndDeletedAtIsNull("test@example.com").orElse(null);
        assertThat(updatedUser).isNotNull();
        assertThat(updatedUser.getLastLoginAt()).isNotNull();
    }

    @Test
    @DisplayName("로그인 실패 - 잘못된 비밀번호")
    void login_WrongPassword() {
        // given
        User user = UserTestHelper.createTestUser(
                "test@example.com",
                passwordEncoder.encode("password123"),
                "테스트유저",
                userRole,
                localProvider
        );
        userRepository.save(user);

        LoginRequestDto loginRequest = new LoginRequestDto();
        loginRequest.setEmail("test@example.com");
        loginRequest.setPassword("wrongPassword");

        // when & then
        assertThatThrownBy(() -> authService.login(loginRequest))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    @DisplayName("로그인 실패 - 존재하지 않는 사용자")
    void login_UserNotFound() {
        // given
        LoginRequestDto loginRequest = new LoginRequestDto();
        loginRequest.setEmail("nonexistent@example.com");
        loginRequest.setPassword("password123");

        // when & then
        assertThatThrownBy(() -> authService.login(loginRequest))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    @DisplayName("토큰 갱신 성공 - 유효한 리프레시 토큰으로 갱신")
    void refreshToken_Success() {
        // given
        User user = UserTestHelper.createTestUser(
                "test@example.com",
                passwordEncoder.encode("password123"),
                "테스트유저",
                userRole,
                localProvider
        );
        userRepository.save(user);

        String refreshToken = jwtTokenProvider.generateRefreshToken("test@example.com");

        TokenRefreshRequestDto refreshRequest = new TokenRefreshRequestDto();
        refreshRequest.setRefreshToken(refreshToken);

        // when
        JwtResponseDto response = authService.refreshToken(refreshRequest);

        // then
        assertThat(response).isNotNull();
        assertThat(response.getEmail()).isEqualTo("test@example.com");
        assertThat(response.getNickname()).isEqualTo("테스트유저");
        assertThat(response.getAccessToken()).isNotBlank();
        assertThat(response.getRefreshToken()).isNotBlank();
        assertThat(jwtTokenProvider.validateToken(response.getAccessToken())).isTrue();
        assertThat(jwtTokenProvider.validateToken(response.getRefreshToken())).isTrue();
    }

    @Test
    @DisplayName("토큰 갱신 실패 - 액세스 토큰으로 갱신 시도")
    void refreshToken_WithAccessToken() {
        // given
        User user = UserTestHelper.createTestUser(
                "test@example.com",
                passwordEncoder.encode("password123"),
                "테스트유저",
                userRole,
                localProvider
        );
        userRepository.save(user);

        String accessToken = jwtTokenProvider.generateAccessToken("test@example.com");

        TokenRefreshRequestDto refreshRequest = new TokenRefreshRequestDto();
        refreshRequest.setRefreshToken(accessToken);

        // when & then
        assertThatThrownBy(() -> authService.refreshToken(refreshRequest))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("토큰 갱신 실패 - 존재하지 않는 사용자")
    void refreshToken_UserNotFound() {
        // given
        String refreshToken = jwtTokenProvider.generateRefreshToken("nonexistent@example.com");

        TokenRefreshRequestDto refreshRequest = new TokenRefreshRequestDto();
        refreshRequest.setRefreshToken(refreshToken);

        // when & then
        assertThatThrownBy(() -> authService.refreshToken(refreshRequest))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("회원 탈퇴 성공")
    void withdraw_Success() {
        // given
        User user = UserTestHelper.createTestUser(
                "test@example.com",
                passwordEncoder.encode("password123"),
                "테스트유저",
                userRole,
                localProvider
        );
        userRepository.save(user);

        // when
        authService.withdraw("test@example.com");

        // then
        User withdrawnUser = userRepository.findById(user.getId()).orElse(null);
        assertThat(withdrawnUser).isNotNull();
        assertThat(withdrawnUser.getStatus()).isEqualTo(User.UserStatus.WITHDRAWN);
        assertThat(withdrawnUser.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("회원 탈퇴 실패 - 존재하지 않는 사용자")
    void withdraw_UserNotFound() {
        // when & then
        assertThatThrownBy(() -> authService.withdraw("nonexistent@example.com"))
                .isInstanceOf(UserNotFoundException.class);
    }
}
