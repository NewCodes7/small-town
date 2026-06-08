package com.newcodes7.small_town.video.service;

import com.newcodes7.small_town.article.exception.InvalidParameterException;
import com.newcodes7.small_town.article.exception.UserNotFoundException;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.auth.entity.Provider;
import com.newcodes7.small_town.auth.entity.Role;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.ProviderRepository;
import com.newcodes7.small_town.auth.repository.RoleRepository;
import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.auth.util.UserTestHelper;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.entity.Video;
import com.newcodes7.small_town.video.exception.VideoNotFoundException;
import com.newcodes7.small_town.video.repository.VideoLikeLogRepository;
import com.newcodes7.small_town.video.repository.VideoRepository;
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
public class VideoLikeServiceTest {

    @Autowired
    private VideoLikeService videoLikeService;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private VideoLikeLogRepository videoLikeLogRepository;

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

    private User testUser;
    private Video testVideo;
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

        // 테스트 Corporation 생성
        Corporation testCorporation = Corporation.builder()
                .name("테스트회사")
                .isDomestic(1)
                .youtubeUrl("https://youtube.com/@test")
                .build();
        testCorporation = corporationRepository.save(testCorporation);

        // 테스트 비디오 생성
        testVideo = Video.builder()
                .title("테스트 비디오")
                .videoId("test123")
                .link("https://youtube.com/watch?v=test")
                .corporation(testCorporation)
                .publishedAt(LocalDateTime.now())
                .viewCount(0)
                .likeCount(0)
                .build();
        testVideo = videoRepository.save(testVideo);
    }

    @Test
    @DisplayName("좋아요 토글 - 좋아요 추가")
    void toggleLike_AddLike() {
        // when
        boolean isLiked = videoLikeService.toggleLike(testVideo.getId(), testUser.getEmail());

        // then
        assertThat(isLiked).isTrue();
        assertThat(videoLikeService.hasLiked(testVideo.getId(), testUser.getEmail())).isTrue();

        // 좋아요 로그가 생성되었는지 확인
        long likeCount = videoLikeLogRepository.countByVideoIdAndDeletedAtIsNull(testVideo.getId());
        assertThat(likeCount).isEqualTo(1);
    }

    @Test
    @DisplayName("좋아요 토글 - 좋아요 취소")
    void toggleLike_RemoveLike() {
        // given - 먼저 좋아요 추가
        videoLikeService.toggleLike(testVideo.getId(), testUser.getEmail());

        // when - 다시 토글하여 좋아요 취소
        boolean isLiked = videoLikeService.toggleLike(testVideo.getId(), testUser.getEmail());

        // then
        assertThat(isLiked).isFalse();
        assertThat(videoLikeService.hasLiked(testVideo.getId(), testUser.getEmail())).isFalse();

        // 좋아요 로그가 삭제되었는지 확인
        long likeCount = videoLikeLogRepository.countByVideoIdAndDeletedAtIsNull(testVideo.getId());
        assertThat(likeCount).isEqualTo(0);
    }

    @Test
    @DisplayName("좋아요 토글 - 여러 번 토글")
    void toggleLike_MultipleTimes() {
        // when & then
        boolean result1 = videoLikeService.toggleLike(testVideo.getId(), testUser.getEmail());
        assertThat(result1).isTrue();

        boolean result2 = videoLikeService.toggleLike(testVideo.getId(), testUser.getEmail());
        assertThat(result2).isFalse();

        boolean result3 = videoLikeService.toggleLike(testVideo.getId(), testUser.getEmail());
        assertThat(result3).isTrue();

        // 최종 상태 확인
        assertThat(videoLikeService.hasLiked(testVideo.getId(), testUser.getEmail())).isTrue();
    }

    @Test
    @DisplayName("좋아요 토글 - 잘못된 videoId")
    void toggleLike_InvalidVideoId() {
        // when & then
        assertThatThrownBy(() -> videoLikeService.toggleLike(null, testUser.getEmail()))
                .isInstanceOf(InvalidParameterException.class);

        assertThatThrownBy(() -> videoLikeService.toggleLike(0L, testUser.getEmail()))
                .isInstanceOf(InvalidParameterException.class);

        assertThatThrownBy(() -> videoLikeService.toggleLike(-1L, testUser.getEmail()))
                .isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @DisplayName("좋아요 토글 - 잘못된 userEmail")
    void toggleLike_InvalidUserEmail() {
        // when & then
        assertThatThrownBy(() -> videoLikeService.toggleLike(testVideo.getId(), null))
                .isInstanceOf(InvalidParameterException.class);

        assertThatThrownBy(() -> videoLikeService.toggleLike(testVideo.getId(), ""))
                .isInstanceOf(InvalidParameterException.class);

        assertThatThrownBy(() -> videoLikeService.toggleLike(testVideo.getId(), "   "))
                .isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @DisplayName("좋아요 토글 - 존재하지 않는 사용자")
    void toggleLike_UserNotFound() {
        // when & then
        assertThatThrownBy(() -> videoLikeService.toggleLike(testVideo.getId(), "nonexistent@example.com"))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("좋아요 토글 - 존재하지 않는 비디오")
    void toggleLike_VideoNotFound() {
        // when & then
        assertThatThrownBy(() -> videoLikeService.toggleLike(999999L, testUser.getEmail()))
                .isInstanceOf(VideoNotFoundException.class);
    }

    @Test
    @DisplayName("좋아요 상태 확인 - 좋아요하지 않은 상태")
    void hasLiked_NotLiked() {
        // when
        boolean hasLiked = videoLikeService.hasLiked(testVideo.getId(), testUser.getEmail());

        // then
        assertThat(hasLiked).isFalse();
    }

    @Test
    @DisplayName("좋아요 상태 확인 - 좋아요한 상태")
    void hasLiked_Liked() {
        // given
        videoLikeService.toggleLike(testVideo.getId(), testUser.getEmail());

        // when
        boolean hasLiked = videoLikeService.hasLiked(testVideo.getId(), testUser.getEmail());

        // then
        assertThat(hasLiked).isTrue();
    }

    @Test
    @DisplayName("좋아요 상태 확인 - 존재하지 않는 사용자")
    void hasLiked_UserNotFound() {
        // when
        boolean hasLiked = videoLikeService.hasLiked(testVideo.getId(), "nonexistent@example.com");

        // then
        assertThat(hasLiked).isFalse(); // 존재하지 않는 사용자는 false 반환
    }

    @Test
    @DisplayName("여러 사용자의 좋아요")
    void multipleLikesFromDifferentUsers() {
        // given
        User anotherUser = UserTestHelper.createTestUser(
                "another@example.com",
                passwordEncoder.encode("password123"),
                "다른유저",
                userRole,
                localProvider
        );
        anotherUser = userRepository.save(anotherUser);

        // when
        videoLikeService.toggleLike(testVideo.getId(), testUser.getEmail());
        videoLikeService.toggleLike(testVideo.getId(), anotherUser.getEmail());

        // then
        assertThat(videoLikeService.hasLiked(testVideo.getId(), testUser.getEmail())).isTrue();
        assertThat(videoLikeService.hasLiked(testVideo.getId(), anotherUser.getEmail())).isTrue();

        // 좋아요 로그 수 확인
        long likeCount = videoLikeLogRepository.countByVideoIdAndDeletedAtIsNull(testVideo.getId());
        assertThat(likeCount).isEqualTo(2);
    }
}
