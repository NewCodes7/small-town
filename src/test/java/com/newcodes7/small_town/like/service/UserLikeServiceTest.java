package com.newcodes7.small_town.like.service;

import static org.assertj.core.api.Assertions.*;

import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.dto.MigrationResultDto;
import com.newcodes7.small_town.article.exception.ArticleNotFoundException;
import com.newcodes7.small_town.article.exception.InvalidParameterException;
import com.newcodes7.small_town.article.exception.UserNotFoundException;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.auth.entity.Provider;
import com.newcodes7.small_town.auth.entity.Role;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.ProviderRepository;
import com.newcodes7.small_town.auth.repository.RoleRepository;
import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.auth.util.UserTestHelper;
import com.newcodes7.small_town.config.IntegrationTestBase;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.like.repository.LikeLogRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * UserLikeService 통합 테스트
 * - 인증된 사용자의 좋아요 기능 (LikeLog 기반)
 * - IP 기반 익명 좋아요 폴백
 */
public class UserLikeServiceTest extends IntegrationTestBase {

    @Autowired
    private UserLikeService userLikeService;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private LikeLogRepository likeLogRepository;

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
    @DisplayName("좋아요 토글 - 추가")
    void toggleLike_Add() {
        // when
        boolean result = userLikeService.toggleLike(testArticle.getId(), testUser.getEmail());

        // then
        assertThat(result).isTrue();
        assertThat(userLikeService.hasLiked(testArticle.getId(), testUser.getEmail())).isTrue();
        assertThat(userLikeService.getLikeCount(testArticle.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("좋아요 토글 - 취소")
    void toggleLike_Remove() {
        // given
        userLikeService.toggleLike(testArticle.getId(), testUser.getEmail());

        // when
        boolean result = userLikeService.toggleLike(testArticle.getId(), testUser.getEmail());

        // then
        assertThat(result).isFalse();
        assertThat(userLikeService.hasLiked(testArticle.getId(), testUser.getEmail())).isFalse();
        assertThat(userLikeService.getLikeCount(testArticle.getId())).isEqualTo(0);
    }

    @Test
    @DisplayName("좋아요 토글 - 여러 번 토글")
    void toggleLike_MultipleTimes() {
        // when & then
        assertThat(userLikeService.toggleLike(testArticle.getId(), testUser.getEmail())).isTrue();
        assertThat(userLikeService.toggleLike(testArticle.getId(), testUser.getEmail())).isFalse();
        assertThat(userLikeService.toggleLike(testArticle.getId(), testUser.getEmail())).isTrue();

        // 최종 상태 확인
        assertThat(userLikeService.hasLiked(testArticle.getId(), testUser.getEmail())).isTrue();
    }

    @Test
    @DisplayName("좋아요 토글 - 잘못된 articleId")
    void toggleLike_InvalidArticleId() {
        // when & then
        assertThatThrownBy(() -> userLikeService.toggleLike(null, testUser.getEmail()))
                .isInstanceOf(InvalidParameterException.class);

        assertThatThrownBy(() -> userLikeService.toggleLike(0L, testUser.getEmail()))
                .isInstanceOf(InvalidParameterException.class);

        assertThatThrownBy(() -> userLikeService.toggleLike(-1L, testUser.getEmail()))
                .isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @DisplayName("좋아요 토글 - 잘못된 userEmail")
    void toggleLike_InvalidUserEmail() {
        // when & then
        assertThatThrownBy(() -> userLikeService.toggleLike(testArticle.getId(), null))
                .isInstanceOf(InvalidParameterException.class);

        assertThatThrownBy(() -> userLikeService.toggleLike(testArticle.getId(), ""))
                .isInstanceOf(InvalidParameterException.class);

        assertThatThrownBy(() -> userLikeService.toggleLike(testArticle.getId(), "   "))
                .isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @DisplayName("좋아요 토글 - 존재하지 않는 사용자")
    void toggleLike_UserNotFound() {
        // when & then
        assertThatThrownBy(() -> userLikeService.toggleLike(testArticle.getId(), "nonexistent@example.com"))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("좋아요 토글 - 존재하지 않는 아티클")
    void toggleLike_ArticleNotFound() {
        // when & then
        assertThatThrownBy(() -> userLikeService.toggleLike(999999L, testUser.getEmail()))
                .isInstanceOf(ArticleNotFoundException.class);
    }

    @Test
    @DisplayName("좋아요 상태 확인 - 좋아요하지 않은 상태")
    void hasLiked_NotLiked() {
        // when
        boolean hasLiked = userLikeService.hasLiked(testArticle.getId(), testUser.getEmail());

        // then
        assertThat(hasLiked).isFalse();
    }

    @Test
    @DisplayName("좋아요 상태 확인 - 좋아요한 상태")
    void hasLiked_Liked() {
        // given
        userLikeService.toggleLike(testArticle.getId(), testUser.getEmail());

        // when
        boolean hasLiked = userLikeService.hasLiked(testArticle.getId(), testUser.getEmail());

        // then
        assertThat(hasLiked).isTrue();
    }

    @Test
    @DisplayName("좋아요 상태 확인 - 존재하지 않는 사용자")
    void hasLiked_UserNotFound() {
        // when
        boolean hasLiked = userLikeService.hasLiked(testArticle.getId(), "nonexistent@example.com");

        // then
        assertThat(hasLiked).isFalse(); // Should return false, not throw exception
    }

    @Test
    @DisplayName("여러 사용자가 좋아요")
    void multipleLikes_DifferentUsers() {
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
        userLikeService.toggleLike(testArticle.getId(), testUser.getEmail());
        userLikeService.toggleLike(testArticle.getId(), user2.getEmail());

        // then
        assertThat(userLikeService.hasLiked(testArticle.getId(), testUser.getEmail())).isTrue();
        assertThat(userLikeService.hasLiked(testArticle.getId(), user2.getEmail())).isTrue();
        assertThat(userLikeService.getLikeCount(testArticle.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("IP 기반 좋아요 토글 - 추가")
    void toggleLikeByIp_Add() {
        // given
        String ipAddress = "127.0.0.1";

        // when
        boolean result = userLikeService.toggleLikeByIp(testArticle.getId(), ipAddress);

        // then
        assertThat(result).isTrue();
        assertThat(userLikeService.hasLikedByIp(testArticle.getId(), ipAddress)).isTrue();
    }

    @Test
    @DisplayName("IP 기반 좋아요 토글 - 취소")
    void toggleLikeByIp_Remove() {
        // given
        String ipAddress = "127.0.0.1";
        userLikeService.toggleLikeByIp(testArticle.getId(), ipAddress);

        // when
        boolean result = userLikeService.toggleLikeByIp(testArticle.getId(), ipAddress);

        // then
        assertThat(result).isFalse();
        assertThat(userLikeService.hasLikedByIp(testArticle.getId(), ipAddress)).isFalse();
    }

    @Test
    @DisplayName("IP 기반 좋아요 - 잘못된 IP")
    void toggleLikeByIp_InvalidIp() {
        // when & then
        assertThatThrownBy(() -> userLikeService.toggleLikeByIp(testArticle.getId(), null))
                .isInstanceOf(InvalidParameterException.class);

        assertThatThrownBy(() -> userLikeService.toggleLikeByIp(testArticle.getId(), ""))
                .isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @DisplayName("배치 좋아요 상태 조회 - 사용자")
    void getLikeStatusBatchByUser() {
        // given
        Article article2 = Article.builder()
                .title("아티클2")
                .link("https://test.com/article2")
                .corporation(testArticle.getCorporation())
                .publishedAt(LocalDateTime.now())
                .build();
        article2 = articleRepository.save(article2);

        userLikeService.toggleLike(testArticle.getId(), testUser.getEmail());
        // article2는 좋아요 안함

        // when
        Map<Long, Boolean> statusMap = userLikeService.getLikeStatusBatchByUser(
                List.of(testArticle.getId(), article2.getId()),
                testUser.getEmail()
        );

        // then
        assertThat(statusMap).hasSize(2);
        assertThat(statusMap.get(testArticle.getId())).isTrue();
        assertThat(statusMap.get(article2.getId())).isFalse();
    }

    @Test
    @DisplayName("배치 좋아요 상태 조회 - IP")
    void getLikeStatusBatchByIp() {
        // given
        Article article2 = Article.builder()
                .title("아티클2")
                .link("https://test.com/article2")
                .corporation(testArticle.getCorporation())
                .publishedAt(LocalDateTime.now())
                .build();
        article2 = articleRepository.save(article2);

        String ipAddress = "127.0.0.1";
        userLikeService.toggleLikeByIp(testArticle.getId(), ipAddress);

        // when
        Map<Long, Boolean> statusMap = userLikeService.getLikeStatusBatchByIp(
                List.of(testArticle.getId(), article2.getId()),
                ipAddress
        );

        // then
        assertThat(statusMap).hasSize(2);
        assertThat(statusMap.get(testArticle.getId())).isTrue();
        assertThat(statusMap.get(article2.getId())).isFalse();
    }

    // ===================== 추가 테스트 =====================

    @Test
    @DisplayName("좋아요한 게시글 조회 - 성공")
    void getLikedArticles_Success() {
        // given
        userLikeService.toggleLike(testArticle.getId(), testUser.getEmail());

        // when
        Page<ArticleListResponseDto> result = userLikeService.getLikedArticles(
                testUser.getEmail(), 0, 10);

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getId()).isEqualTo(testArticle.getId());
    }

    @Test
    @DisplayName("좋아요한 게시글 조회 - 좋아요 없는 경우")
    void getLikedArticles_NoLikes() {
        // when
        Page<ArticleListResponseDto> result = userLikeService.getLikedArticles(
                testUser.getEmail(), 0, 10);

        // then
        assertThat(result.getTotalElements()).isEqualTo(0);
    }

    @Test
    @DisplayName("좋아요한 게시글 조회 - 존재하지 않는 사용자")
    void getLikedArticles_UserNotFound() {
        assertThatThrownBy(() -> userLikeService.getLikedArticles("nonexistent@example.com", 0, 10))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("좋아요 마이그레이션 - 성공")
    void migrateLikesFromLocalStorage_Success() {
        // when
        MigrationResultDto result = userLikeService.migrateLikesFromLocalStorage(
                testUser.getEmail(), List.of(testArticle.getId()));

        // then
        assertThat(result.getTotalCount()).isEqualTo(1);
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getId()).isEqualTo(testArticle.getId());

        // verify like was actually created
        assertThat(userLikeService.hasLiked(testArticle.getId(), testUser.getEmail())).isTrue();
    }

    @Test
    @DisplayName("좋아요 마이그레이션 - 빈 리스트")
    void migrateLikesFromLocalStorage_EmptyList() {
        // when
        MigrationResultDto result = userLikeService.migrateLikesFromLocalStorage(
                testUser.getEmail(), List.of());

        // then
        assertThat(result.getTotalCount()).isEqualTo(0);
        assertThat(result.getItems()).isEmpty();
    }

    @Test
    @DisplayName("좋아요 마이그레이션 - null 리스트")
    void migrateLikesFromLocalStorage_NullList() {
        // when
        MigrationResultDto result = userLikeService.migrateLikesFromLocalStorage(
                testUser.getEmail(), null);

        // then
        assertThat(result.getTotalCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("좋아요 마이그레이션 - 이미 좋아요한 글은 건너뜀")
    void migrateLikesFromLocalStorage_SkipExisting() {
        // given - 이미 좋아요 추가
        userLikeService.toggleLike(testArticle.getId(), testUser.getEmail());

        // when
        MigrationResultDto result = userLikeService.migrateLikesFromLocalStorage(
                testUser.getEmail(), List.of(testArticle.getId()));

        // then - 이미 좋아요된 글은 마이그레이션하지 않음
        assertThat(result.getTotalCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("좋아요 마이그레이션 - 존재하지 않는 사용자")
    void migrateLikesFromLocalStorage_UserNotFound() {
        assertThatThrownBy(() -> userLikeService.migrateLikesFromLocalStorage(
                "nonexistent@example.com", List.of(testArticle.getId())))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("좋아요 마이그레이션 - 존재하지 않는 게시글 ID는 건너뜀")
    void migrateLikesFromLocalStorage_SkipNonExistentArticle() {
        // when
        MigrationResultDto result = userLikeService.migrateLikesFromLocalStorage(
                testUser.getEmail(), List.of(testArticle.getId(), 999999L));

        // then
        assertThat(result.getTotalCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("IP 기반 좋아요 확인 - null IP")
    void hasLikedByIp_NullIp() {
        boolean result = userLikeService.hasLikedByIp(testArticle.getId(), null);
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("IP 기반 좋아요 확인 - 빈 IP")
    void hasLikedByIp_EmptyIp() {
        boolean result = userLikeService.hasLikedByIp(testArticle.getId(), "");
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("배치 좋아요 상태 - 사용자: null articleIds")
    void getLikeStatusBatchByUser_NullArticleIds() {
        Map<Long, Boolean> result = userLikeService.getLikeStatusBatchByUser(null, testUser.getEmail());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("배치 좋아요 상태 - 사용자: 빈 articleIds")
    void getLikeStatusBatchByUser_EmptyArticleIds() {
        Map<Long, Boolean> result = userLikeService.getLikeStatusBatchByUser(List.of(), testUser.getEmail());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("배치 좋아요 상태 - 사용자: null email")
    void getLikeStatusBatchByUser_NullEmail() {
        Map<Long, Boolean> result = userLikeService.getLikeStatusBatchByUser(
                List.of(testArticle.getId()), null);
        assertThat(result).containsEntry(testArticle.getId(), false);
    }

    @Test
    @DisplayName("배치 좋아요 상태 - IP: null articleIds")
    void getLikeStatusBatchByIp_NullArticleIds() {
        Map<Long, Boolean> result = userLikeService.getLikeStatusBatchByIp(null, "127.0.0.1");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("배치 좋아요 상태 - IP: null IP")
    void getLikeStatusBatchByIp_NullIp() {
        Map<Long, Boolean> result = userLikeService.getLikeStatusBatchByIp(
                List.of(testArticle.getId()), null);
        assertThat(result).containsEntry(testArticle.getId(), false);
    }
}
