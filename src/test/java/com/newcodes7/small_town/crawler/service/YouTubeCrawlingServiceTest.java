package com.newcodes7.small_town.crawler.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import com.newcodes7.small_town.crawler.crawler.VideoCrawler;
import com.newcodes7.small_town.crawler.dto.VideoCrawlResult;
import com.newcodes7.small_town.crawler.exception.CorporationCrawlingException;
import com.newcodes7.small_town.crawler.exception.CrawlerNotFoundException;
import com.newcodes7.small_town.crawler.persistence.VideoPersistenceService;
import com.newcodes7.small_town.crawler.repository.CrawlerCorporationRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerVideoRepository;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.entity.Video;

/**
 * YouTubeCrawlingService 단위 테스트
 * YouTube 크롤링 배치/단건 처리 및 예외 처리 검증
 */
@ExtendWith(MockitoExtension.class)
class YouTubeCrawlingServiceTest {

    @Mock
    private CrawlerCorporationRepository crawlerCorporationRepository;

    @Mock
    private CrawlerVideoRepository crawlerVideoRepository;

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private VideoPersistenceService videoPersistenceService;

    private YouTubeCrawlingService youtubeCrawlingService;

    @BeforeEach
    void setUp() {
        youtubeCrawlingService = new YouTubeCrawlingService(
                crawlerCorporationRepository,
                crawlerVideoRepository,
                applicationContext,
                videoPersistenceService
        );
    }

    private Corporation createCorporationWithChannel(Long id, String name, String channelId) {
        Corporation corp = Corporation.builder()
                .name(name)
                .youtubeChannelId(channelId)
                .build();
        corp.setId(id);
        return corp;
    }

    // ===== crawlAllYouTube 테스트 =====

    @Nested
    @DisplayName("crawlAllYouTube")
    class CrawlAllYouTubeTests {

        @Test
        @DisplayName("YouTube 채널이 있는 기업이 없으면 빈 결과 반환")
        void crawlAllYouTube_noCorporations_returnsEmptyList() {
            // given
            when(crawlerCorporationRepository.findAllWithYoutubeChannel()).thenReturn(List.of());

            // when
            List<VideoCrawlResult> results = youtubeCrawlingService.crawlAllYouTube();

            // then
            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("크롤링 중 예외 발생 시 해당 기업은 실패 처리하고 나머지 계속")
        void crawlAllYouTube_exceptionInOneCorp_addsFailureAndContinues() {
            // given
            Corporation corp1 = createCorporationWithChannel(1L, "Corp1", "channel1");
            Corporation corp2 = createCorporationWithChannel(2L, "Corp2", "channel2");
            when(crawlerCorporationRepository.findAllWithYoutubeChannel()).thenReturn(List.of(corp1, corp2));

            // corp1 not found → CorporationCrawlingException
            when(crawlerCorporationRepository.findByIdAndNotDeleted(1L)).thenReturn(null);
            // corp2 also not found (simplify)
            when(crawlerCorporationRepository.findByIdAndNotDeleted(2L)).thenReturn(null);

            // when
            List<VideoCrawlResult> results = youtubeCrawlingService.crawlAllYouTube();

            // then - both fail but processing continues
            assertThat(results).hasSize(2);
            assertThat(results).allMatch(r -> !r.isSuccess());
        }
    }

    // ===== crawlSingleYouTube 테스트 =====

    @Nested
    @DisplayName("crawlSingleYouTube")
    class CrawlSingleYouTubeTests {

        @Test
        @DisplayName("Corporation 없음 → CorporationCrawlingException")
        void crawlSingleYouTube_corporationNotFound_throwsException() {
            // given
            when(crawlerCorporationRepository.findByIdAndNotDeleted(999L)).thenReturn(null);

            // when / then
            assertThatThrownBy(() -> youtubeCrawlingService.crawlSingleYouTube(999L, null))
                    .isInstanceOf(CorporationCrawlingException.class);
        }

        @Test
        @DisplayName("YouTube 채널 ID 없음 → CorporationCrawlingException")
        void crawlSingleYouTube_noYoutubeChannelId_throwsException() {
            // given
            Corporation corp = Corporation.builder()
                    .name("NoBlogCorp")
                    .youtubeChannelId(null)
                    .build();
            corp.setId(1L);
            when(crawlerCorporationRepository.findByIdAndNotDeleted(1L)).thenReturn(corp);

            // when / then
            assertThatThrownBy(() -> youtubeCrawlingService.crawlSingleYouTube(1L, null))
                    .isInstanceOf(CorporationCrawlingException.class);
        }

        @Test
        @DisplayName("YouTube 채널 ID가 빈 문자열 → CorporationCrawlingException")
        void crawlSingleYouTube_emptyYoutubeChannelId_throwsException() {
            // given
            Corporation corp = Corporation.builder()
                    .name("EmptyChannelCorp")
                    .youtubeChannelId("   ")
                    .build();
            corp.setId(1L);
            when(crawlerCorporationRepository.findByIdAndNotDeleted(1L)).thenReturn(corp);

            // when / then
            assertThatThrownBy(() -> youtubeCrawlingService.crawlSingleYouTube(1L, null))
                    .isInstanceOf(CorporationCrawlingException.class);
        }

        @Test
        @DisplayName("적합한 VideoCrawler 없음 → CrawlerNotFoundException")
        void crawlSingleYouTube_noCrawlerFound_throwsCrawlerNotFoundException() {
            // given
            Corporation corp = createCorporationWithChannel(1L, "TestCorp", "UC123");
            when(crawlerCorporationRepository.findByIdAndNotDeleted(1L)).thenReturn(corp);

            VideoCrawler crawler = mock(VideoCrawler.class);
            when(crawler.canHandle(anyString())).thenReturn(false);
            when(applicationContext.getBeansOfType(VideoCrawler.class)).thenReturn(Map.of("youtube", crawler));

            // when / then
            assertThatThrownBy(() -> youtubeCrawlingService.crawlSingleYouTube(1L, null))
                    .isInstanceOf(CrawlerNotFoundException.class);
        }

        @Test
        @DisplayName("신규 비디오 존재 시 저장 후 성공 결과 반환")
        void crawlSingleYouTube_newVideos_savesAndReturnsSuccess() throws Exception {
            // given
            Corporation corp = createCorporationWithChannel(1L, "TestCorp", "UC123");
            when(crawlerCorporationRepository.findByIdAndNotDeleted(1L)).thenReturn(corp);

            Video video = Video.builder()
                    .title("New Video")
                    .link("https://youtube.com/watch?v=abc123")
                    .build();

            VideoCrawler crawler = mock(VideoCrawler.class);
            when(crawler.canHandle(anyString())).thenReturn(true);
            when(crawler.getProviderName()).thenReturn("YouTube");
            when(crawler.crawl(any(), any(Corporation.class))).thenReturn(List.of(video));
            when(applicationContext.getBeansOfType(VideoCrawler.class)).thenReturn(Map.of("youtube", crawler));

            // 중복 없음
            when(crawlerVideoRepository.findFirstByLinkAndDeletedAtIsNull(video.getLink()))
                    .thenReturn(Optional.empty());

            // when
            VideoCrawlResult result = youtubeCrawlingService.crawlSingleYouTube(1L, null);

            // then
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getNewVideos()).isEqualTo(1);
            verify(videoPersistenceService).saveVideoWithTranslation(eq(video), eq(corp), eq(crawler));
        }

        @Test
        @DisplayName("중복 비디오 → 저장 스킵하고 신규 0개 반환")
        void crawlSingleYouTube_duplicateVideo_skipsAndReturnsZeroNew() throws Exception {
            // given
            Corporation corp = createCorporationWithChannel(1L, "TestCorp", "UC123");
            when(crawlerCorporationRepository.findByIdAndNotDeleted(1L)).thenReturn(corp);

            Video video = Video.builder()
                    .title("Existing Video")
                    .link("https://youtube.com/watch?v=existing")
                    .build();

            VideoCrawler crawler = mock(VideoCrawler.class);
            when(crawler.canHandle(anyString())).thenReturn(true);
            when(crawler.getProviderName()).thenReturn("YouTube");
            when(crawler.crawl(any(), any(Corporation.class))).thenReturn(List.of(video));
            when(applicationContext.getBeansOfType(VideoCrawler.class)).thenReturn(Map.of("youtube", crawler));

            // 이미 존재하는 비디오
            when(crawlerVideoRepository.findFirstByLinkAndDeletedAtIsNull(video.getLink()))
                    .thenReturn(Optional.of(video));

            // when
            VideoCrawlResult result = youtubeCrawlingService.crawlSingleYouTube(1L, null);

            // then
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getNewVideos()).isEqualTo(0);
            verify(videoPersistenceService, never()).saveVideoWithTranslation(any(), any(), any());
        }
    }
}
