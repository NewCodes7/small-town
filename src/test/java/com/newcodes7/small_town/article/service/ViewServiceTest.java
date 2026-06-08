package com.newcodes7.small_town.article.service;

import com.newcodes7.small_town.article.exception.ArticleNotFoundException;
import com.newcodes7.small_town.article.exception.InvalidParameterException;
import com.newcodes7.small_town.article.exception.UserNotFoundException;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.article.repository.ViewLogRepository;
import com.newcodes7.small_town.auth.entity.Provider;
import com.newcodes7.small_town.auth.entity.Role;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.ProviderRepository;
import com.newcodes7.small_town.auth.repository.RoleRepository;
import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.auth.util.UserTestHelper;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@TestPropertySource("classpath:application-test.properties")
@Transactional
public class ViewServiceTest {

    @Autowired
    private ViewService viewService;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private ViewLogRepository viewLogRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private CorporationRepository corporationRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Article testArticle;
    private User testUser;
    private Role userRole;
    private Provider localProvider;

    @BeforeEach
    void setUp() {
        // Role과 Provider 설정
        userRole = roleRepository.findByName("USER")
                .orElseGet(() -> roleRepository.save(new Role("USER")));
        
        localProvider = providerRepository.findByName("LOCAL")
                .orElseGet(() -> providerRepository.save(new Provider("LOCAL")));

        // 테스트 사용자 생성
        testUser = UserTestHelper.createTestUser(
                "test@example.com",
                passwordEncoder.encode("password123"),
                "테스트유저",
                userRole,
                localProvider
        );
        testUser = userRepository.save(testUser);

        // 테스트용 Corporation 생성
        Corporation testCorporation = Corporation.builder()
                .name("테스트회사")
                .isDomestic(1)
                .build();
        testCorporation = corporationRepository.save(testCorporation);

        // 테스트용 Article 생성
        testArticle = Article.builder()
                .title("테스트 아티클")
                .link("https://test.com/article")
                .corporation(testCorporation)
                .publishedAt(LocalDateTime.now())
                .viewCount(0)
                .likeCount(0)
                .build();
        testArticle = articleRepository.save(testArticle);
    }

    @Test
    @DisplayName("조회수 증가 - 인증된 사용자")
    void incrementViewCount_AuthenticatedUser() {
        // given
        String ipAddress = "127.0.0.1";

        // when
        boolean result = viewService.incrementViewCount(
                testArticle.getId(), 
                testUser.getEmail(), 
                ipAddress
        );

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("조회수 증가 - 쿨다운 중 재조회")
    void incrementViewCount_DuringCooldown() {
        // given
        String ipAddress = "127.0.0.1";
        viewService.incrementViewCount(testArticle.getId(), testUser.getEmail(), ipAddress);

        // when - 바로 다시 조회
        boolean result = viewService.incrementViewCount(
                testArticle.getId(), 
                testUser.getEmail(), 
                ipAddress
        );

        // then
        assertThat(result).isFalse(); // 쿨다운 중이므로 false
    }

    @Test
    @DisplayName("조회수 증가 - 잘못된 articleId")
    void incrementViewCount_InvalidArticleId() {
        // when & then
        assertThatThrownBy(() -> viewService.incrementViewCount(
                null, 
                testUser.getEmail(), 
                "127.0.0.1"
        )).isInstanceOf(InvalidParameterException.class);

        assertThatThrownBy(() -> viewService.incrementViewCount(
                0L, 
                testUser.getEmail(), 
                "127.0.0.1"
        )).isInstanceOf(InvalidParameterException.class);

        assertThatThrownBy(() -> viewService.incrementViewCount(
                -1L, 
                testUser.getEmail(), 
                "127.0.0.1"
        )).isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @DisplayName("조회수 증가 - 잘못된 userEmail")
    void incrementViewCount_InvalidUserEmail() {
        // when & then
        assertThatThrownBy(() -> viewService.incrementViewCount(
                testArticle.getId(), 
                null, 
                "127.0.0.1"
        )).isInstanceOf(InvalidParameterException.class);

        assertThatThrownBy(() -> viewService.incrementViewCount(
                testArticle.getId(), 
                "", 
                "127.0.0.1"
        )).isInstanceOf(InvalidParameterException.class);

        assertThatThrownBy(() -> viewService.incrementViewCount(
                testArticle.getId(), 
                "   ", 
                "127.0.0.1"
        )).isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @DisplayName("조회수 증가 - 존재하지 않는 사용자")
    void incrementViewCount_UserNotFound() {
        // when & then
        assertThatThrownBy(() -> viewService.incrementViewCount(
                testArticle.getId(), 
                "nonexistent@example.com", 
                "127.0.0.1"
        )).isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("조회수 증가 - 존재하지 않는 아티클")
    void incrementViewCount_ArticleNotFound() {
        // when & then
        assertThatThrownBy(() -> viewService.incrementViewCount(
                999999L, 
                testUser.getEmail(), 
                "127.0.0.1"
        )).isInstanceOf(ArticleNotFoundException.class);
    }

    @Test
    @DisplayName("조회수 증가 - 익명 사용자 (IP 기반)")
    void incrementViewCountByIp_Success() {
        // given
        String ipAddress = "192.168.0.1";

        // when
        boolean result = viewService.incrementViewCountByIp(
                testArticle.getId(), 
                ipAddress
        );

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("조회수 증가 - 익명 사용자 쿨다운")
    void incrementViewCountByIp_DuringCooldown() {
        // given
        String ipAddress = "192.168.0.1";
        viewService.incrementViewCountByIp(testArticle.getId(), ipAddress);

        // when - 바로 다시 조회
        boolean result = viewService.incrementViewCountByIp(
                testArticle.getId(), 
                ipAddress
        );

        // then
        assertThat(result).isFalse(); // 쿨다운 중이므로 false
    }

    @Test
    @DisplayName("조회수 증가 - 익명 사용자 잘못된 IP")
    void incrementViewCountByIp_InvalidIp() {
        // when & then
        assertThatThrownBy(() -> viewService.incrementViewCountByIp(
                testArticle.getId(), 
                null
        )).isInstanceOf(InvalidParameterException.class);

        assertThatThrownBy(() -> viewService.incrementViewCountByIp(
                testArticle.getId(), 
                ""
        )).isInstanceOf(InvalidParameterException.class);

        assertThatThrownBy(() -> viewService.incrementViewCountByIp(
                testArticle.getId(), 
                "   "
        )).isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @DisplayName("여러 사용자가 조회")
    void multipleUsersViewing() {
        // given
        User user2 = UserTestHelper.createTestUser(
                "user2@example.com",
                passwordEncoder.encode("password123"),
                "유저2",
                userRole,
                localProvider
        );
        user2 = userRepository.save(user2);

        // when
        boolean result1 = viewService.incrementViewCount(
                testArticle.getId(), testUser.getEmail(), "192.168.0.1"
        );
        boolean result2 = viewService.incrementViewCount(
                testArticle.getId(), user2.getEmail(), "192.168.0.2"
        );

        // then
        assertThat(result1).isTrue();
        assertThat(result2).isTrue();
    }
}
