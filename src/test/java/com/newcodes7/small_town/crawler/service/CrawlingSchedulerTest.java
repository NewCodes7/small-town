package com.newcodes7.small_town.crawler.service;

import com.newcodes7.small_town.crawler.config.CrawlerProperties;
import com.newcodes7.small_town.crawler.dto.CrawlResult;
import com.newcodes7.small_town.crawler.entity.Corporation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CrawlingScheduler 단위 테스트")
class CrawlingSchedulerTest {

    @Mock
    private CrawlingService crawlingService;

    @Mock
    private CrawlerProperties crawlerProperties;

    @InjectMocks
    private CrawlingScheduler crawlingScheduler;

    private Corporation testCorporation;

    @BeforeEach
    void setUp() {
        testCorporation = Corporation.builder()
                .id(1L)
                .name("테스트 기업")
                .blogLink("https://test-blog.com")
                .build();
    }

    @Test
    @DisplayName("스케줄된 크롤링 정상 실행")
    void scheduledCrawling_정상_실행() {
        // given
        List<CrawlResult> successResults = Arrays.asList(
                CrawlResult.success(testCorporation, List.of(), 5),
                CrawlResult.success(testCorporation, List.of(), 3)
        );
        when(crawlingService.crawlAllBlogs()).thenReturn(successResults);

        // when
        crawlingScheduler.scheduledCrawling();

        // then
        verify(crawlingService).crawlAllBlogs();
    }

    @Test
    @DisplayName("스케줄된 크롤링 - 일부 실패 포함")
    void scheduledCrawling_일부_실패() {
        // given
        Corporation failedCorp = Corporation.builder()
                .id(2L)
                .name("실패 기업")
                .blogLink("https://failed-blog.com")
                .build();

        List<CrawlResult> mixedResults = Arrays.asList(
                CrawlResult.success(testCorporation, List.of(), 5),
                CrawlResult.failure(failedCorp, "크롤링 실패")
        );
        when(crawlingService.crawlAllBlogs()).thenReturn(mixedResults);

        // when
        crawlingScheduler.scheduledCrawling();

        // then
        verify(crawlingService).crawlAllBlogs();
    }

    @Test
    @DisplayName("크롤링 서비스에서 예외 발생 시 안전 처리")
    void scheduledCrawling_예외_안전_처리() {
        // given
        when(crawlingService.crawlAllBlogs()).thenThrow(new RuntimeException("크롤링 서비스 오류"));

        // when
        crawlingScheduler.scheduledCrawling();

        // then
        verify(crawlingService).crawlAllBlogs();
        // 예외가 발생해도 스케줄러가 중단되지 않아야 함
    }

    @Test
    @DisplayName("빈 결과 리스트 처리")
    void scheduledCrawling_빈_결과() {
        // given
        when(crawlingService.crawlAllBlogs()).thenReturn(List.of());

        // when
        crawlingScheduler.scheduledCrawling();

        // then
        verify(crawlingService).crawlAllBlogs();
    }

    @Test
    @DisplayName("모든 크롤링 실패 시 처리")
    void scheduledCrawling_모든_실패() {
        // given
        Corporation corp1 = Corporation.builder()
                .id(1L)
                .name("기업 1")
                .blogLink("https://blog1.com")
                .build();
        
        Corporation corp2 = Corporation.builder()
                .id(2L)
                .name("기업 2")
                .blogLink("https://blog2.com")
                .build();

        List<CrawlResult> failedResults = Arrays.asList(
                CrawlResult.failure(corp1, "네트워크 오류"),
                CrawlResult.failure(corp2, "파싱 오류")
        );
        when(crawlingService.crawlAllBlogs()).thenReturn(failedResults);

        // when
        crawlingScheduler.scheduledCrawling();

        // then
        verify(crawlingService).crawlAllBlogs();
    }

    @Test
    @DisplayName("대량 크롤링 결과 처리")
    void scheduledCrawling_대량_결과() {
        // given
        List<CrawlResult> largeResults = generateLargeResults(100);
        when(crawlingService.crawlAllBlogs()).thenReturn(largeResults);

        // when
        crawlingScheduler.scheduledCrawling();

        // then
        verify(crawlingService).crawlAllBlogs();
    }

    @Test
    @DisplayName("null Corporation이 포함된 결과 처리")
    void scheduledCrawling_null_Corporation_처리() {
        // given
        List<CrawlResult> resultsWithNull = Arrays.asList(
                CrawlResult.success(testCorporation, List.of(), 3),
                CrawlResult.failure(null, "Unknown error")
        );
        when(crawlingService.crawlAllBlogs()).thenReturn(resultsWithNull);

        // when
        crawlingScheduler.scheduledCrawling();

        // then
        verify(crawlingService).crawlAllBlogs();
        // null Corporation이 있어도 예외 없이 처리되어야 함
    }

    // 테스트 헬퍼 메서드
    private List<CrawlResult> generateLargeResults(int count) {
        return java.util.stream.IntStream.range(1, count + 1)
                .mapToObj(i -> {
                    Corporation corp = Corporation.builder()
                            .id((long) i)
                            .name("기업 " + i)
                            .blogLink("https://blog" + i + ".com")
                            .build();
                    
                    // 80% 성공, 20% 실패로 설정
                    if (i % 5 == 0) {
                        return CrawlResult.failure(corp, "크롤링 실패 " + i);
                    } else {
                        return CrawlResult.success(corp, List.of(), i % 10);
                    }
                })
                .toList();
    }
}