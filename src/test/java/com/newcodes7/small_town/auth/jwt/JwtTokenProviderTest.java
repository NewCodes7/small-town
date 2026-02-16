package com.newcodes7.small_town.auth.jwt;

import com.newcodes7.small_town.auth.entity.Provider;
import com.newcodes7.small_town.auth.entity.Role;
import com.newcodes7.small_town.auth.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@TestPropertySource("classpath:application-test.properties")
public class JwtTokenProviderTest {

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private User testUser;

    @BeforeEach
    void setUp() {
        Role userRole = new Role("USER");
        Provider localProvider = new Provider("LOCAL");
        
        testUser = User.builder()
                .email("test@example.com")
                .password("encodedPassword")
                .nickname("테스트유저")
                .role(userRole)
                .provider(localProvider)
                .build();
    }

    @Test
    @DisplayName("액세스 토큰 생성 - Authentication 객체로 생성")
    void generateAccessToken_WithAuthentication() {
        // given
        Authentication authentication = new UsernamePasswordAuthenticationToken(testUser, null, testUser.getAuthorities());

        // when
        String accessToken = jwtTokenProvider.generateAccessToken(authentication);

        // then
        assertThat(accessToken).isNotBlank();
        assertThat(jwtTokenProvider.validateToken(accessToken)).isTrue();
        assertThat(jwtTokenProvider.isAccessToken(accessToken)).isTrue();
        assertThat(jwtTokenProvider.getEmailFromToken(accessToken)).isEqualTo("test@example.com");
    }

    @Test
    @DisplayName("리프레시 토큰 생성 - Authentication 객체로 생성")
    void generateRefreshToken_WithAuthentication() {
        // given
        Authentication authentication = new UsernamePasswordAuthenticationToken(testUser, null, testUser.getAuthorities());

        // when
        String refreshToken = jwtTokenProvider.generateRefreshToken(authentication);

        // then
        assertThat(refreshToken).isNotBlank();
        assertThat(jwtTokenProvider.validateToken(refreshToken)).isTrue();
        assertThat(jwtTokenProvider.isRefreshToken(refreshToken)).isTrue();
        assertThat(jwtTokenProvider.getEmailFromToken(refreshToken)).isEqualTo("test@example.com");
    }

    @Test
    @DisplayName("액세스 토큰 생성 - 이메일로 직접 생성")
    void generateAccessToken_WithEmail() {
        // given
        String email = "test@example.com";

        // when
        String accessToken = jwtTokenProvider.generateAccessToken(email);

        // then
        assertThat(accessToken).isNotBlank();
        assertThat(jwtTokenProvider.validateToken(accessToken)).isTrue();
        assertThat(jwtTokenProvider.isAccessToken(accessToken)).isTrue();
        assertThat(jwtTokenProvider.getEmailFromToken(accessToken)).isEqualTo(email);
    }

    @Test
    @DisplayName("리프레시 토큰 생성 - 이메일로 직접 생성")
    void generateRefreshToken_WithEmail() {
        // given
        String email = "test@example.com";

        // when
        String refreshToken = jwtTokenProvider.generateRefreshToken(email);

        // then
        assertThat(refreshToken).isNotBlank();
        assertThat(jwtTokenProvider.validateToken(refreshToken)).isTrue();
        assertThat(jwtTokenProvider.isRefreshToken(refreshToken)).isTrue();
        assertThat(jwtTokenProvider.getEmailFromToken(refreshToken)).isEqualTo(email);
    }

    @Test
    @DisplayName("토큰에서 이메일 추출")
    void getEmailFromToken() {
        // given
        String email = "test@example.com";
        String token = jwtTokenProvider.generateAccessToken(email);

        // when
        String extractedEmail = jwtTokenProvider.getEmailFromToken(token);

        // then
        assertThat(extractedEmail).isEqualTo(email);
    }

    @Test
    @DisplayName("토큰 타입 확인 - ACCESS 타입")
    void getTokenType_Access() {
        // given
        String accessToken = jwtTokenProvider.generateAccessToken("test@example.com");

        // when
        String tokenType = jwtTokenProvider.getTokenType(accessToken);

        // then
        assertThat(tokenType).isEqualTo("ACCESS");
        assertThat(jwtTokenProvider.isAccessToken(accessToken)).isTrue();
        assertThat(jwtTokenProvider.isRefreshToken(accessToken)).isFalse();
    }

    @Test
    @DisplayName("토큰 타입 확인 - REFRESH 타입")
    void getTokenType_Refresh() {
        // given
        String refreshToken = jwtTokenProvider.generateRefreshToken("test@example.com");

        // when
        String tokenType = jwtTokenProvider.getTokenType(refreshToken);

        // then
        assertThat(tokenType).isEqualTo("REFRESH");
        assertThat(jwtTokenProvider.isRefreshToken(refreshToken)).isTrue();
        assertThat(jwtTokenProvider.isAccessToken(refreshToken)).isFalse();
    }

    @Test
    @DisplayName("토큰 검증 - 유효한 토큰")
    void validateToken_Valid() {
        // given
        String token = jwtTokenProvider.generateAccessToken("test@example.com");

        // when
        boolean isValid = jwtTokenProvider.validateToken(token);

        // then
        assertThat(isValid).isTrue();
    }

    @Test
    @DisplayName("토큰 검증 - 잘못된 형식의 토큰")
    void validateToken_MalformedToken() {
        // given
        String invalidToken = "invalid.token.format";

        // when
        boolean isValid = jwtTokenProvider.validateToken(invalidToken);

        // then
        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("토큰 검증 - 빈 토큰")
    void validateToken_EmptyToken() {
        // given
        String emptyToken = "";

        // when
        boolean isValid = jwtTokenProvider.validateToken(emptyToken);

        // then
        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("토큰 검증 - null 토큰")
    void validateToken_NullToken() {
        // given
        String nullToken = null;

        // when
        boolean isValid = jwtTokenProvider.validateToken(nullToken);

        // then
        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("액세스 토큰과 리프레시 토큰의 차이 확인")
    void differentTokenTypes() {
        // given
        String email = "test@example.com";

        // when
        String accessToken = jwtTokenProvider.generateAccessToken(email);
        String refreshToken = jwtTokenProvider.generateRefreshToken(email);

        // then
        assertThat(accessToken).isNotEqualTo(refreshToken);
        assertThat(jwtTokenProvider.isAccessToken(accessToken)).isTrue();
        assertThat(jwtTokenProvider.isRefreshToken(refreshToken)).isTrue();
        
        // 둘 다 같은 이메일을 포함
        assertThat(jwtTokenProvider.getEmailFromToken(accessToken))
                .isEqualTo(jwtTokenProvider.getEmailFromToken(refreshToken));
    }

    @Test
    @DisplayName("토큰의 페이로드에 올바른 정보가 포함되는지 확인")
    void tokenContainsCorrectClaims() {
        // given
        String email = "test@example.com";
        String accessToken = jwtTokenProvider.generateAccessToken(email);

        // when
        String extractedEmail = jwtTokenProvider.getEmailFromToken(accessToken);
        String tokenType = jwtTokenProvider.getTokenType(accessToken);

        // then
        assertThat(extractedEmail).isEqualTo(email);
        assertThat(tokenType).isEqualTo("ACCESS");
    }
}
