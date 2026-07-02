package com.newcodes7.small_town.crawler.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.crawler.config.WebDriverConfig;
import com.newcodes7.small_town.crawler.crawler.DefaultBlogCrawler;
import com.newcodes7.small_town.crawler.crawler.MediumBlogCrawler;
import com.newcodes7.small_town.crawler.dto.CrawlResult;
import com.newcodes7.small_town.crawler.dto.VideoCrawlResult;
import com.newcodes7.small_town.crawler.entity.CrawlingJobType;
import com.newcodes7.small_town.crawler.entity.CrawlingRunStatus;
import com.newcodes7.small_town.crawler.entity.CrawlingSchedulerRun;
import com.newcodes7.small_town.crawler.integration.translation.TitleTranslationService;
import com.newcodes7.small_town.crawler.persistence.ArticlePersistenceService;
import com.newcodes7.small_town.crawler.service.CrawlingRunContext;
import com.newcodes7.small_town.crawler.service.CrawlingRunService;
import com.newcodes7.small_town.crawler.service.CrawlingService;
import com.newcodes7.small_town.crawler.service.IndexPrewarmService;
import com.newcodes7.small_town.crawler.service.YouTubeCrawlingService;
import com.newcodes7.small_town.global.entity.Corporation;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CrawlingScheduler 단위 테스트
 * 스케줄러 메서드의 정상/예외 처리 분기 검증
 */
@ExtendWith(MockitoExtension.class)
class CrawlingSchedulerTest {

    @Mock private CrawlingService crawlingService;
    @Mock private YouTubeCrawlingService youtubeCrawlingService;
    @Mock private TitleTranslationService titleTranslationService;
    @Mock private ArticlePersistenceService articlePersistenceService;
    @Mock private ArticleRepository articleRepository;
    @Mock private WebDriverConfig webDriverConfig;
    @Mock private MediumBlogCrawler mediumBlogCrawler;
    @Mock private DefaultBlogCrawler defaultBlogCrawler;
    @Mock private CrawlingRunService crawlingRunService;
    @Mock private CrawlingRunContext crawlingRunContext;
    @Mock private IndexPrewarmService indexPrewarmService;

    private CrawlingScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new CrawlingScheduler(
                crawlingService,
                youtubeCrawlingService,
                titleTranslationService,
                articlePersistenceService,
                articleRepository,
                webDriverConfig,
                mediumBlogCrawler,
                defaultBlogCrawler,
                crawlingRunService,
                crawlingRunContext,
                indexPrewarmService
        );
    }

    private CrawlingSchedulerRun mockRun(Long id) {
        CrawlingSchedulerRun run = CrawlingSchedulerRun.builder()
                .jobType(CrawlingJobType.BLOG)
                .status(CrawlingRunStatus.RUNNING)
                .successCount(0)
                .failureCount(0)
                .newItemsCount(0)
                .build();
        run.setId(id);
        return run;
    }

    @Nested
    @DisplayName("scheduledBlogCrawling")
    class BlogCrawlingTests {

        @Test
        @DisplayName("정상 완료 시 SUCCESS 상태로 finishRun 호출")
        void scheduledBlogCrawling_success_finishesWithSuccessStatus() {
            // given
            CrawlingSchedulerRun run = mockRun(1L);
            when(crawlingRunService.startRun(CrawlingJobType.BLOG)).thenReturn(run);

            Corporation corp = Corporation.builder().name("TestCorp").build();
            CrawlResult result = CrawlResult.success(corp, List.of(), 3);
            when(crawlingService.crawlAllBlogs()).thenReturn(List.of(result));

            // when
            scheduler.scheduledBlogCrawling();

            // then
            verify(crawlingRunService).finishRun(1L, CrawlingRunStatus.SUCCESS, 1, 0, 3, null);
            verify(indexPrewarmService).prewarmChunkEmbeddingIndex();
            verify(crawlingRunContext).clear();
        }

        @Test
        @DisplayName("일부 실패 시 PARTIAL_SUCCESS 상태로 finishRun 호출")
        void scheduledBlogCrawling_partialFailure_finishesWithPartialStatus() {
            // given
            CrawlingSchedulerRun run = mockRun(1L);
            when(crawlingRunService.startRun(CrawlingJobType.BLOG)).thenReturn(run);

            Corporation corp1 = Corporation.builder().name("SuccessCorp").build();
            Corporation corp2 = Corporation.builder().name("FailCorp").build();
            CrawlResult success = CrawlResult.success(corp1, List.of(), 2);
            CrawlResult failure = CrawlResult.failure(corp2, "Connection timeout");
            when(crawlingService.crawlAllBlogs()).thenReturn(List.of(success, failure));

            // when
            scheduler.scheduledBlogCrawling();

            // then
            verify(crawlingRunService).finishRun(1L, CrawlingRunStatus.PARTIAL_SUCCESS, 1, 1, 2, null);
        }

        @Test
        @DisplayName("예외 발생 시 FAILURE 상태로 finishRun 호출 (크롤링 계속)")
        void scheduledBlogCrawling_exceptionThrown_finishesWithFailureStatus() {
            // given
            CrawlingSchedulerRun run = mockRun(1L);
            when(crawlingRunService.startRun(CrawlingJobType.BLOG)).thenReturn(run);
            when(crawlingService.crawlAllBlogs()).thenThrow(new RuntimeException("DB connection failed"));

            // when - 예외가 외부로 전파되지 않아야 함
            assertThatCode(() -> scheduler.scheduledBlogCrawling()).doesNotThrowAnyException();

            // then
            verify(crawlingRunService).finishRun(eq(1L), eq(CrawlingRunStatus.FAILURE), eq(0), eq(0), eq(0), anyString());
            verify(crawlingRunContext).clear();
        }
    }

    @Nested
    @DisplayName("scheduledYouTubeCrawling")
    class YouTubeCrawlingTests {

        @Test
        @DisplayName("정상 완료 시 SUCCESS 상태로 finishRun 호출")
        void scheduledYouTubeCrawling_success_finishesWithSuccessStatus() {
            // given
            CrawlingSchedulerRun run = CrawlingSchedulerRun.builder()
                    .jobType(CrawlingJobType.YOUTUBE)
                    .status(CrawlingRunStatus.RUNNING)
                    .successCount(0).failureCount(0).newItemsCount(0)
                    .build();
            run.setId(2L);
            when(crawlingRunService.startRun(CrawlingJobType.YOUTUBE)).thenReturn(run);

            Corporation corp = Corporation.builder().name("YouTubeCorp").build();
            VideoCrawlResult result = VideoCrawlResult.success(corp, List.of(), 5);
            when(youtubeCrawlingService.crawlAllYouTube()).thenReturn(List.of(result));

            // when
            scheduler.scheduledYouTubeCrawling();

            // then
            verify(crawlingRunService).finishRun(2L, CrawlingRunStatus.SUCCESS, 1, 0, 5, null);
            verify(crawlingRunContext).clear();
        }

        @Test
        @DisplayName("예외 발생 시 FAILURE 상태로 finishRun 호출 (크롤링 계속)")
        void scheduledYouTubeCrawling_exceptionThrown_finishesWithFailureStatus() {
            // given
            CrawlingSchedulerRun run = CrawlingSchedulerRun.builder()
                    .jobType(CrawlingJobType.YOUTUBE)
                    .status(CrawlingRunStatus.RUNNING)
                    .successCount(0).failureCount(0).newItemsCount(0)
                    .build();
            run.setId(2L);
            when(crawlingRunService.startRun(CrawlingJobType.YOUTUBE)).thenReturn(run);
            when(youtubeCrawlingService.crawlAllYouTube()).thenThrow(new RuntimeException("API key expired"));

            // when
            assertThatCode(() -> scheduler.scheduledYouTubeCrawling()).doesNotThrowAnyException();

            // then
            verify(crawlingRunService).finishRun(eq(2L), eq(CrawlingRunStatus.FAILURE), eq(0), eq(0), eq(0), anyString());
        }
    }

}
