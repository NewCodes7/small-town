package com.newcodes7.small_town.like.service;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.like.repository.LikeRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@TestPropertySource("classpath:application-test.properties")
@Transactional
public class LikeServiceTest {

    @Autowired
    private LikeService likeService;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private CorporationRepository corporationRepository;

    private Article testArticle;

    @BeforeEach
    void setUp() {
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
    @DisplayName("좋아요 토글 - 좋아요 추가")
    void toggleLike_AddLike() {
        // given
        String ipAddress = "127.0.0.1";

        // when
        boolean isLiked = likeService.toggleLike(testArticle.getId(), ipAddress);

        // then
        assertThat(isLiked).isTrue();
        assertThat(likeService.hasLiked(testArticle.getId(), ipAddress)).isTrue();
        assertThat(likeService.getLikeCount(testArticle.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("좋아요 토글 - 좋아요 취소")
    void toggleLike_RemoveLike() {
        // given
        String ipAddress = "127.0.0.1";
        likeService.toggleLike(testArticle.getId(), ipAddress);

        // when
        boolean isLiked = likeService.toggleLike(testArticle.getId(), ipAddress);

        // then
        assertThat(isLiked).isFalse();
        assertThat(likeService.hasLiked(testArticle.getId(), ipAddress)).isFalse();
        assertThat(likeService.getLikeCount(testArticle.getId())).isEqualTo(0);
    }

    @Test
    @DisplayName("좋아요 토글 - 여러 번 토글")
    void toggleLike_MultipleTimes() {
        // given
        String ipAddress = "127.0.0.1";

        // when & then
        assertThat(likeService.toggleLike(testArticle.getId(), ipAddress)).isTrue();
        assertThat(likeService.toggleLike(testArticle.getId(), ipAddress)).isFalse();
        assertThat(likeService.toggleLike(testArticle.getId(), ipAddress)).isTrue();

        // 최종 상태 확인
        assertThat(likeService.hasLiked(testArticle.getId(), ipAddress)).isTrue();
    }

    @Test
    @DisplayName("좋아요 토글 - 존재하지 않는 아티클")
    void toggleLike_ArticleNotFound() {
        // when & then
        assertThatThrownBy(() -> likeService.toggleLike(999999L, "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Article not found");
    }

    @Test
    @DisplayName("좋아요 상태 확인 - 좋아요하지 않은 상태")
    void hasLiked_NotLiked() {
        // when
        boolean hasLiked = likeService.hasLiked(testArticle.getId(), "127.0.0.1");

        // then
        assertThat(hasLiked).isFalse();
    }

    @Test
    @DisplayName("좋아요 상태 확인 - 좋아요한 상태")
    void hasLiked_Liked() {
        // given
        String ipAddress = "127.0.0.1";
        likeService.toggleLike(testArticle.getId(), ipAddress);

        // when
        boolean hasLiked = likeService.hasLiked(testArticle.getId(), ipAddress);

        // then
        assertThat(hasLiked).isTrue();
    }

    @Test
    @DisplayName("좋아요 개수 조회")
    void getLikeCount() {
        // given
        likeService.toggleLike(testArticle.getId(), "192.168.0.1");
        likeService.toggleLike(testArticle.getId(), "192.168.0.2");
        likeService.toggleLike(testArticle.getId(), "192.168.0.3");

        // when
        long likeCount = likeService.getLikeCount(testArticle.getId());

        // then
        assertThat(likeCount).isEqualTo(3);
    }

    @Test
    @DisplayName("여러 IP에서 좋아요")
    void multipleLikesFromDifferentIps() {
        // given
        String ip1 = "192.168.0.1";
        String ip2 = "192.168.0.2";
        String ip3 = "192.168.0.3";

        // when
        likeService.toggleLike(testArticle.getId(), ip1);
        likeService.toggleLike(testArticle.getId(), ip2);
        likeService.toggleLike(testArticle.getId(), ip3);

        // then
        assertThat(likeService.hasLiked(testArticle.getId(), ip1)).isTrue();
        assertThat(likeService.hasLiked(testArticle.getId(), ip2)).isTrue();
        assertThat(likeService.hasLiked(testArticle.getId(), ip3)).isTrue();
        assertThat(likeService.getLikeCount(testArticle.getId())).isEqualTo(3);
    }

    @Test
    @DisplayName("배치 좋아요 상태 조회 - 정상")
    void getLikeStatusBatch_Success() {
        // given
        Article article2 = Article.builder()
                .title("아티클2")
                .link("https://test.com/article2")
                .corporation(testArticle.getCorporation())
                .publishedAt(LocalDateTime.now())
                .build();
        article2 = articleRepository.save(article2);

        String ipAddress = "127.0.0.1";
        likeService.toggleLike(testArticle.getId(), ipAddress);
        // article2는 좋아요 안함

        // when
        Map<Long, Boolean> statusMap = likeService.getLikeStatusBatch(
                List.of(testArticle.getId(), article2.getId()),
                ipAddress
        );

        // then
        assertThat(statusMap).hasSize(2);
        assertThat(statusMap.get(testArticle.getId())).isTrue();
        assertThat(statusMap.get(article2.getId())).isFalse();
    }

    @Test
    @DisplayName("배치 좋아요 상태 조회 - 빈 목록")
    void getLikeStatusBatch_EmptyList() {
        // when
        Map<Long, Boolean> statusMap = likeService.getLikeStatusBatch(
                List.of(),
                "127.0.0.1"
        );

        // then
        assertThat(statusMap).isEmpty();
    }

    @Test
    @DisplayName("배치 좋아요 상태 조회 - null IP")
    void getLikeStatusBatch_NullIp() {
        // when
        Map<Long, Boolean> statusMap = likeService.getLikeStatusBatch(
                List.of(testArticle.getId()),
                null
        );

        // then
        assertThat(statusMap).hasSize(1);
        assertThat(statusMap.get(testArticle.getId())).isFalse();
    }
}
