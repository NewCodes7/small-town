package com.newcodes7.small_town.crawler.controller;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.crawler.config.WebDriverConfig;
import com.newcodes7.small_town.crawler.crawler.DefaultBlogCrawler;
import com.newcodes7.small_town.crawler.crawler.MediumBlogCrawler;
import com.newcodes7.small_town.crawler.dto.CrawlResult;
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
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.BlogType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.WebDriver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "crawler", name = "enabled", havingValue = "true")
public class CrawlingScheduler {

    private final CrawlingService crawlingService;
    private final YouTubeCrawlingService youtubeCrawlingService;
    private final TitleTranslationService titleTranslationService;
    private final ArticlePersistenceService articlePersistenceService;
    private final ArticleRepository articleRepository;
    private final WebDriverConfig webDriverConfig;
    private final MediumBlogCrawler mediumBlogCrawler;
    private final DefaultBlogCrawler defaultBlogCrawler;
    private final CrawlingRunService crawlingRunService;
    private final CrawlingRunContext crawlingRunContext;
    private final IndexPrewarmService indexPrewarmService;

    private static final int MAX_CONTENT_LENGTH = 200;
    private static final int BATCH_SIZE = 400;
    private static final long RATE_LIMIT_DELAY_MS = 1000;
    private static final long MEDIUM_CRAWL_TIMEOUT_MS = 25 * 60 * 1000; // 25분
    // 한 WebDriver로 이 개수만큼 페이지를 처리하면 재시작 — renderer-process-limit=1로 인해
    // 단일 프로세스에 누적되는 메모리를 주기적으로 반환하기 위함
    private static final int DRIVER_RESTART_INTERVAL = 30;

    /**
     * 블로그 크롤링 스케줄러
     * 매 시간 정각에 실행 (Embedding 재추출로 인한 서버 리소스 아끼기 위해 잠시 새벽 4시로 설정)
     */
    @Scheduled(cron = "${crawler.schedule.blog.cron:0 0 4 * * ?}", zone = "Asia/Seoul")
    public void scheduledBlogCrawling() {
        log.info("스케줄된 블로그 크롤링 작업 시작");

        CrawlingSchedulerRun run = crawlingRunService.startRun(CrawlingJobType.BLOG);
        crawlingRunContext.setRunId(run.getId());

        try {
            List<CrawlResult> results = crawlingService.crawlAllBlogs();

            long successCount = results.stream()
                    .filter(CrawlResult::isSuccess)
                    .count();

            long failureCount = results.size() - successCount;

            long totalNewArticles = results.stream()
                    .filter(CrawlResult::isSuccess)
                    .mapToLong(CrawlResult::getNewArticles)
                    .sum();

            log.info("스케줄된 블로그 크롤링 작업 완료 - 성공: {}개, 실패: {}개, 신규 글: {}개",
                successCount, failureCount, totalNewArticles);

            // 실패한 경우 로그 출력
            results.stream()
                    .filter(result -> !result.isSuccess())
                    .forEach(result -> {
                        String corpName = result.getCorporation() != null ?
                            result.getCorporation().getName() : "Unknown";
                        log.warn("블로그 크롤링 실패 - 기업: {}, 오류: {}", corpName, result.getErrorMessage());
                    });

            CrawlingRunStatus status = failureCount == 0
                    ? CrawlingRunStatus.SUCCESS
                    : (successCount == 0 ? CrawlingRunStatus.FAILURE : CrawlingRunStatus.PARTIAL_SUCCESS);
            crawlingRunService.finishRun(run.getId(), status, (int) successCount, (int) failureCount, (int) totalNewArticles, null);

        } catch (Exception e) {
            log.error("스케줄된 블로그 크롤링 작업 중 오류 발생", e);
            crawlingRunService.finishRun(run.getId(), CrawlingRunStatus.FAILURE, 0, 0, 0, e.getMessage());
        } finally {
            indexPrewarmService.prewarmChunkEmbeddingIndex();
            crawlingRunContext.clear();
        }
    }

    /**
     * YouTube 크롤링 스케줄러
     * 새벽 4시 30분에 실행
     */
    @Scheduled(cron = "${crawler.schedule.youtube.cron:0 30 4 * * ?}", zone = "Asia/Seoul")
    public void scheduledYouTubeCrawling() {
        log.info("스케줄된 YouTube 크롤링 작업 시작");

        CrawlingSchedulerRun run = crawlingRunService.startRun(CrawlingJobType.YOUTUBE);
        crawlingRunContext.setRunId(run.getId());

        try {
            List<com.newcodes7.small_town.crawler.dto.VideoCrawlResult> results = youtubeCrawlingService.crawlAllYouTube();

            long successCount = results.stream()
                    .filter(com.newcodes7.small_town.crawler.dto.VideoCrawlResult::isSuccess)
                    .count();

            long failureCount = results.size() - successCount;

            long totalNewVideos = results.stream()
                    .filter(com.newcodes7.small_town.crawler.dto.VideoCrawlResult::isSuccess)
                    .mapToLong(com.newcodes7.small_town.crawler.dto.VideoCrawlResult::getNewVideos)
                    .sum();

            log.info("스케줄된 YouTube 크롤링 작업 완료 - 성공: {}개, 실패: {}개, 신규 영상: {}개",
                successCount, failureCount, totalNewVideos);

            // 실패한 경우 로그 출력
            results.stream()
                    .filter(result -> !result.isSuccess())
                    .forEach(result -> {
                        String corpName = result.getCorporation() != null ?
                            result.getCorporation().getName() : "Unknown";
                        log.warn("YouTube 크롤링 실패 - 기업: {}, 오류: {}", corpName, result.getErrorMessage());
                    });

            CrawlingRunStatus status = failureCount == 0
                    ? CrawlingRunStatus.SUCCESS
                    : (successCount == 0 ? CrawlingRunStatus.FAILURE : CrawlingRunStatus.PARTIAL_SUCCESS);
            crawlingRunService.finishRun(run.getId(), status, (int) successCount, (int) failureCount, (int) totalNewVideos, null);

        } catch (Exception e) {
            log.error("스케줄된 YouTube 크롤링 작업 중 오류 발생", e);
            crawlingRunService.finishRun(run.getId(), CrawlingRunStatus.FAILURE, 0, 0, 0, e.getMessage());
        } finally {
            crawlingRunContext.clear();
        }
    }

    /**
     * 전체 페이지 크롤링 스케줄러
     * 매 시간 30분에 실행 (ID 내림차순, lastFullCrawledAt이 null인 경우에만)
     */
    // @Scheduled(cron = "${crawler.schedule.fullpage.cron:0 30 * * * ?}", zone = "Asia/Seoul")
    public void scheduledFullPageCrawling() {
        log.info("스케줄된 전체 페이지 크롤링 작업 시작");

        CrawlingSchedulerRun run = crawlingRunService.startRun(CrawlingJobType.FULL_PAGE);
        crawlingRunContext.setRunId(run.getId());

        try {
            List<CrawlResult> results = crawlingService.scheduledFullPageCrawling();

            long successCount = results.stream()
                    .filter(CrawlResult::isSuccess)
                    .count();

            long failureCount = results.size() - successCount;

            long totalNewArticles = results.stream()
                    .filter(CrawlResult::isSuccess)
                    .mapToLong(CrawlResult::getNewArticles)
                    .sum();

            log.info("스케줄된 전체 페이지 크롤링 작업 완료 - 성공: {}개, 실패: {}개, 신규 글: {}개",
                successCount, failureCount, totalNewArticles);

            // 실패한 경우 로그 출력
            results.stream()
                    .filter(result -> !result.isSuccess())
                    .forEach(result -> {
                        String corpName = result.getCorporation() != null ?
                            result.getCorporation().getName() : "Unknown";
                        log.warn("전체 페이지 크롤링 실패 - 기업: {}, 오류: {}", corpName, result.getErrorMessage());
                    });

            CrawlingRunStatus status = failureCount == 0
                    ? CrawlingRunStatus.SUCCESS
                    : (successCount == 0 ? CrawlingRunStatus.FAILURE : CrawlingRunStatus.PARTIAL_SUCCESS);
            crawlingRunService.finishRun(run.getId(), status, (int) successCount, (int) failureCount, (int) totalNewArticles, null);

        } catch (Exception e) {
            log.error("스케줄된 전체 페이지 크롤링 작업 중 오류 발생", e);
            crawlingRunService.finishRun(run.getId(), CrawlingRunStatus.FAILURE, 0, 0, 0, e.getMessage());
        } finally {
            crawlingRunContext.clear();
        }
    }

    /**
     * 제목 번역 및 AI 카테고리 분류 스케줄러
     */
    // @Scheduled(cron = "${crawler.schedule.analysis.cron:0 0 5 * * ?}", zone = "Asia/Seoul")
    public void scheduledTranslationAndAnalysis() {
        log.info("스케줄된 번역 및 AI 분석 작업 시작");

        try {
            // 1. 해외 기업 글 제목 번역
            log.info("해외 기업 글 제목 번역 시작");
            titleTranslationService.translateAllOverseasArticleTitles();
            log.info("해외 기업 글 제목 번역 완료");

            // 2. 해외 기업 영상 제목 번역
            log.info("해외 기업 영상 제목 번역 시작");
            titleTranslationService.translateAllOverseasVideoTitles();
            log.info("해외 기업 영상 제목 번역 완료");

            // 3. 미분류 글 AI 카테고리 분류
            log.info("미분류 글 AI 카테고리 분류 시작");
            var result = articlePersistenceService.analyzeExistingArticles();
            log.info("미분류 글 AI 카테고리 분류 완료 - {}", result.get("message"));

            log.info("스케줄된 번역 및 AI 분석 작업 완료");

        } catch (Exception e) {
            log.error("스케줄된 번역 및 AI 분석 작업 중 오류 발생", e);
        }
    }

    /**
     * 본문 백필 크롤링 스케줄러
     * 매 정각 30분에 실행
     * 모든 블로그 타입의 본문이 200자 이하인 Article 대상
     */
    @Scheduled(cron = "${crawler.schedule.medium-content.cron:0 30 * * * ?}", zone = "Asia/Seoul")
    public void scheduledContentCrawling() {
        log.info("스케줄된 본문 백필 크롤링 작업 시작 (타임아웃: 25분)");

        long startTime = System.currentTimeMillis();

        try {
            // 본문이 짧은 전체 Article 조회
            List<Article> articles = articleRepository.findArticlesWithShortContent(
                    MAX_CONTENT_LENGTH,
                    PageRequest.of(0, BATCH_SIZE)
            );

            if (articles.isEmpty()) {
                log.info("본문 크롤링 대상 Article이 없습니다.");
                return;
            }

            log.info("본문 백필 크롤링 대상: {}개 Article", articles.size());

            // Medium은 bot 감지 우회를 위해 이미지 로딩이 필요하므로, 두 그룹을 별도 WebDriver로 처리
            List<Article> defaultArticles = articles.stream()
                    .filter(a -> a.getCorporation().getBlogType() != BlogType.MEDIUM)
                    .toList();
            List<Article> mediumArticles = articles.stream()
                    .filter(a -> a.getCorporation().getBlogType() == BlogType.MEDIUM)
                    .toList();

            ContentCrawlBatchResult defaultResult = crawlContentBatch(
                    defaultArticles, false, startTime, defaultBlogCrawler::extractArticleContent);

            ContentCrawlBatchResult mediumResult = Thread.currentThread().isInterrupted()
                    ? ContentCrawlBatchResult.skippedAll(mediumArticles.size())
                    : crawlContentBatch(mediumArticles, true, startTime, mediumBlogCrawler::extractArticleContent);

            int successCount = defaultResult.successCount() + mediumResult.successCount();
            int failureCount = defaultResult.failureCount() + mediumResult.failureCount();
            int skippedByTimeout = defaultResult.skippedByTimeout() + mediumResult.skippedByTimeout();

            long totalElapsed = System.currentTimeMillis() - startTime;
            log.info("스케줄된 본문 백필 크롤링 작업 완료 - 성공: {}개, 실패: {}개, 타임아웃 스킵: {}개, 소요시간: {}분",
                    successCount, failureCount, skippedByTimeout, totalElapsed / 60000);

        } catch (Exception e) {
            log.error("스케줄된 본문 백필 크롤링 작업 중 오류 발생", e);
        }
    }

    /**
     * Article 목록에 대해 본문을 추출·저장한다. WebDriver는 DRIVER_RESTART_INTERVAL개마다 재시작해
     * (renderer-process-limit=1로 인해) 단일 프로세스에 누적되는 메모리를 주기적으로 반환한다.
     */
    private ContentCrawlBatchResult crawlContentBatch(
            List<Article> articles,
            boolean allowImages,
            long startTime,
            java.util.function.BiFunction<String, WebDriver, String> contentExtractor) {

        int successCount = 0;
        int failureCount = 0;
        int skippedByTimeout = 0;
        WebDriver driver = null;
        int processedSinceRestart = 0;

        try {
            for (int i = 0; i < articles.size(); i++) {
                Article article = articles.get(i);

                // 25분 타임아웃 체크 (두 그룹이 시작 시각을 공유)
                long elapsedTime = System.currentTimeMillis() - startTime;
                if (elapsedTime >= MEDIUM_CRAWL_TIMEOUT_MS) {
                    skippedByTimeout = articles.size() - i;
                    log.warn("25분 타임아웃 도달 - 크롤링 종료 (경과: {}분, 남은 Article: {}개)",
                            elapsedTime / 60000, skippedByTimeout);
                    break;
                }

                if (driver == null || processedSinceRestart >= DRIVER_RESTART_INTERVAL) {
                    if (driver != null) {
                        log.debug("WebDriver 재시작 - 누적 메모리 반환 ({}개 처리)", processedSinceRestart);
                        webDriverConfig.forceCloseWebDriver(driver);
                    }
                    driver = webDriverConfig.createWebDriver(allowImages);
                    processedSinceRestart = 0;
                }

                try {
                    String content = contentExtractor.apply(article.getLink(), driver);

                    if (content != null && !content.isBlank() && content.length() > MAX_CONTENT_LENGTH) {
                        // 본문 업데이트 (독립 트랜잭션)
                        updateArticleContent(article.getId(), content);
                        successCount++;
                        log.debug("Article {} 본문 추출 완료 ({}자)", article.getId(), content.length());
                    } else {
                        failureCount++;
                        log.warn("Article {} 본문 추출 실패 또는 여전히 짧음 ({}자)",
                                article.getId(), content != null ? content.length() : 0);
                    }

                    // Rate limiting
                    Thread.sleep(RATE_LIMIT_DELAY_MS);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("본문 백필 크롤링 중단됨");
                    skippedByTimeout = articles.size() - i - 1;
                    break;
                } catch (Exception e) {
                    failureCount++;
                    log.error("Article {} 본문 추출 실패: {}", article.getId(), e.getMessage());
                }

                processedSinceRestart++;
            }
        } finally {
            if (driver != null) {
                try {
                    webDriverConfig.forceCloseWebDriver(driver);
                } catch (Exception e) {
                    log.warn("WebDriver 종료 중 오류 발생 (무시)", e);
                }
            }
        }

        return new ContentCrawlBatchResult(successCount, failureCount, skippedByTimeout);
    }

    private record ContentCrawlBatchResult(int successCount, int failureCount, int skippedByTimeout) {
        static ContentCrawlBatchResult skippedAll(int count) {
            return new ContentCrawlBatchResult(0, 0, count);
        }
    }

    /**
     * Article의 content를 업데이트합니다.
     * 독립 트랜잭션으로 실행되어 개별 실패가 전체에 영향을 주지 않습니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateArticleContent(Long articleId, String content) {
        Article article = articleRepository.findById(articleId)
                .orElseThrow(() -> new IllegalArgumentException("Article not found: " + articleId));
        article.setContent(content);
        articleRepository.save(article);
        log.debug("Article {} content 업데이트 완료", articleId);
    }

}
