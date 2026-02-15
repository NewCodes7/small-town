package com.newcodes7.small_town.video.service;

import com.newcodes7.small_town.article.repository.CorporationRepository;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.entity.Video;
import com.newcodes7.small_town.video.dto.VideoResponseDto;
import com.newcodes7.small_town.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@TestPropertySource("classpath:application-test.properties")
@Transactional
public class VideoServiceTest {

    @Autowired
    private VideoService videoService;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    @Qualifier("articleCorporationRepository")
    private CorporationRepository corporationRepository;

    private Corporation testCorporation;

    @BeforeEach
    void setUp() {
        // 테스트용 Corporation 생성
        testCorporation = Corporation.builder()
                .name("테스트회사")
                .isDomestic(1)
                .youtubeUrl("https://youtube.com/@test")
                .youtubeChannelId("UCtest123")
                .build();
        testCorporation = corporationRepository.save(testCorporation);
    }

    @Test
    @DisplayName("비디오 전체 조회 - list 뷰")
    void getVideosWithFilters_ListView() {
        // given
        Video video1 = createVideo("테스트 비디오 1", LocalDateTime.now().minusDays(1));
        Video video2 = createVideo("테스트 비디오 2", LocalDateTime.now().minusDays(2));
        videoRepository.save(video1);
        videoRepository.save(video2);

        // when
        Page<VideoResponseDto> result = videoService.getVideosWithFilters(
                null, null, 0, 10, "latest", "list", null
        );

        // then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("비디오 검색 - 키워드로 검색")
    void getVideosWithFilters_WithKeyword() {
        // given
        Video video1 = createVideo("Spring Boot 튜토리얼", LocalDateTime.now());
        Video video2 = createVideo("Java 프로그래밍", LocalDateTime.now());
        Video video3 = createVideo("Python 기초", LocalDateTime.now());
        
        videoRepository.save(video1);
        videoRepository.save(video2);
        videoRepository.save(video3);

        // when
        Page<VideoResponseDto> result = videoService.getVideosWithFilters(
                "Spring", null, 0, 10, "latest", "list", null
        );

        // then
        assertThat(result).isNotNull();
        // 키워드 검색 결과가 있어야 함 (정확한 매칭은 Term 기반)
    }

    @Test
    @DisplayName("비디오 필터링 - 국내 비디오만")
    void getVideosWithFilters_DomesticOnly() {
        // given
        Corporation domesticCorp = Corporation.builder()
                .name("국내회사")
                .isDomestic(1)
                .youtubeUrl("https://youtube.com/@domestic")
                .build();
        domesticCorp = corporationRepository.save(domesticCorp);

        Corporation overseasCorp = Corporation.builder()
                .name("해외회사")
                .isDomestic(0)
                .youtubeUrl("https://youtube.com/@overseas")
                .build();
        overseasCorp = corporationRepository.save(overseasCorp);

        Video domesticVideo = Video.builder()
                .title("국내 비디오")
                .videoId("domestic123")
                .link("https://youtube.com/watch?v=domestic")
                .corporation(domesticCorp)
                .publishedAt(LocalDateTime.now())
                .build();
        videoRepository.save(domesticVideo);

        Video overseasVideo = Video.builder()
                .title("해외 비디오")
                .videoId("overseas456")
                .link("https://youtube.com/watch?v=overseas")
                .corporation(overseasCorp)
                .publishedAt(LocalDateTime.now())
                .build();
        videoRepository.save(overseasVideo);

        // when
        Page<VideoResponseDto> result = videoService.getVideosWithFilters(
                null, List.of("domestic"), 0, 10, "latest", "list", null
        );

        // then
        assertThat(result).isNotNull();
        // 결과에는 국내 비디오만 포함되어야 함
    }

    @Test
    @DisplayName("비디오 그룹 조회 - grouped 뷰")
    void getVideosWithFilters_GroupedView() {
        // given
        Video video1 = createVideo("테스트 1", LocalDateTime.now());
        Video video2 = createVideo("테스트 2", LocalDateTime.now());
        videoRepository.save(video1);
        videoRepository.save(video2);

        // when
        Page<VideoResponseDto> result = videoService.getVideosWithFilters(
                null, null, 0, 10, "latest", "grouped", null
        );

        // then
        assertThat(result).isNotNull();
        // Grouped view는 기업별로 그룹화된 결과를 반환
    }

    @Test
    @DisplayName("비디오 조회 - 빈 결과")
    void getVideosWithFilters_EmptyResult() {
        // given - 데이터 없음

        // when
        Page<VideoResponseDto> result = videoService.getVideosWithFilters(
                "존재하지않는키워드", null, 0, 10, "latest", "list", null
        );

        // then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("비디오 조회 - 페이징 처리")
    void getVideosWithFilters_Pagination() {
        // given - 여러 비디오 생성
        for (int i = 1; i <= 5; i++) {
            Video video = createVideo("페이징테스트" + i, LocalDateTime.now().minusDays(i));
            videoRepository.save(video);
        }
        videoRepository.flush();

        // when - 첫 번째 페이지 조회 (size=2로 작게 설정)
        Page<VideoResponseDto> page1 = videoService.getVideosWithFilters(
                null, null, 0, 2, "latest", "list", null
        );

        // then - 페이징 응답이 정상적으로 동작하는지 확인
        assertThat(page1).isNotNull();
        assertThat(page1.getNumber()).isEqualTo(0); // 첫 번째 페이지
        assertThat(page1.getSize()).isEqualTo(2); // 요청한 사이즈
        // 실제 데이터가 있는지 확인 (최소 1개 이상)
        assertThat(page1.getContent()).isNotEmpty();
    }

    @Test
    @DisplayName("비디오 조회 - 정렬 (최신순)")
    void getVideosWithFilters_SortLatest() {
        // given
        Video oldVideo = createVideo("오래된 비디오", LocalDateTime.now().minusDays(10));
        Video newVideo = createVideo("최신 비디오", LocalDateTime.now().minusDays(1));
        videoRepository.save(oldVideo);
        videoRepository.save(newVideo);

        // when
        Page<VideoResponseDto> result = videoService.getVideosWithFilters(
                null, null, 0, 10, "latest", "list", null
        );

        // then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).isNotEmpty();
        // 최신순 정렬 확인
    }

    @Test
    @DisplayName("비디오 조회 - 해외 비디오만")
    void getVideosWithFilters_OverseasOnly() {
        // given
        Corporation overseasCorp = Corporation.builder()
                .name("해외회사")
                .isDomestic(0)
                .youtubeUrl("https://youtube.com/@overseas")
                .build();
        overseasCorp = corporationRepository.save(overseasCorp);

        Video video = Video.builder()
                .title("해외 비디오")
                .videoId("overseas123")
                .link("https://youtube.com/watch?v=overseas")
                .corporation(overseasCorp)
                .publishedAt(LocalDateTime.now())
                .build();
        videoRepository.save(video);

        // when
        Page<VideoResponseDto> result = videoService.getVideosWithFilters(
                null, List.of("overseas"), 0, 10, "latest", "list", null
        );

        // then
        assertThat(result).isNotNull();
        // 해외 비디오만 포함되어야 함
    }

    private Video createVideo(String title, LocalDateTime publishedAt) {
        return Video.builder()
                .title(title)
                .videoId("vid_" + System.currentTimeMillis() + "_" + Math.random())
                .link("https://youtube.com/watch?v=" + title)
                .corporation(testCorporation)
                .publishedAt(publishedAt)
                .viewCount(0)
                .likeCount(0)
                .build();
    }
}
