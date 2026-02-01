package com.newcodes7.small_town.crawler.controller;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.crawler.config.WebDriverConfig;
import com.newcodes7.small_town.crawler.crawler.DefaultBlogCrawler;
import com.newcodes7.small_town.crawler.crawler.MediumBlogCrawler;
import com.newcodes7.small_town.global.entity.BlogType;
import com.newcodes7.small_town.crawler.dto.CrawlResult;
import com.newcodes7.small_town.crawler.integration.analytics.GoogleAnalyticsService;
import com.newcodes7.small_town.crawler.persistence.ArticlePersistenceService;
import com.newcodes7.small_town.crawler.service.CrawlingService;
import com.newcodes7.small_town.crawler.integration.translation.TitleTranslationService;
import com.newcodes7.small_town.embedding.service.ClovaEmbeddingBatchService;
import com.newcodes7.small_town.global.entity.Article;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.WebDriver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "crawler", name = "enabled", havingValue = "true")
public class CrawlingScheduler {

    private final CrawlingService crawlingService;
    private final TitleTranslationService titleTranslationService;
    private final ArticlePersistenceService articlePersistenceService;
    private final GoogleAnalyticsService googleAnalyticsService;
    private final ArticleRepository articleRepository;
    private final WebDriverConfig webDriverConfig;
    private final MediumBlogCrawler mediumBlogCrawler;
    private final DefaultBlogCrawler defaultBlogCrawler;
    private final ClovaEmbeddingBatchService clovaEmbeddingBatchService;

    private static final int MAX_CONTENT_LENGTH = 200;
    private static final int BATCH_SIZE = 400;
    private static final long RATE_LIMIT_DELAY_MS = 1000;
    private static final long MEDIUM_CRAWL_TIMEOUT_MS = 25 * 60 * 1000; // 25분

    /**
     * 블로그 크롤링 스케줄러
     * 매 시간 정각에 실행
     */
    @Scheduled(cron = "${crawler.schedule.blog.cron:0 0 * * * ?}", zone = "Asia/Seoul")
    public void scheduledBlogCrawling() {
        log.info("스케줄된 블로그 크롤링 작업 시작");

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

            // 신규 글에 대해 Clova 임베딩 생성
            generateClovaEmbeddingsForNewArticles(results);

        } catch (Exception e) {
            log.error("스케줄된 블로그 크롤링 작업 중 오류 발생", e);
        }
    }

    /**
     * YouTube 크롤링 스케줄러
     * 매 시간 30분에 실행
     */
    @Scheduled(cron = "${crawler.schedule.youtube.cron:0 30 4 * * ?}", zone = "Asia/Seoul")
    public void scheduledYouTubeCrawling() {
        log.info("스케줄된 YouTube 크롤링 작업 시작");

        try {
            List<com.newcodes7.small_town.crawler.dto.VideoCrawlResult> results = crawlingService.crawlAllYouTube();

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

        } catch (Exception e) {
            log.error("스케줄된 YouTube 크롤링 작업 중 오류 발생", e);
        }
    }

    /**
     * 전체 페이지 크롤링 스케줄러
     * 매 시간 30분에 실행 (ID 내림차순, lastFullCrawledAt이 null인 경우에만)
     */
    // @Scheduled(cron = "${crawler.schedule.fullpage.cron:0 30 * * * ?}", zone = "Asia/Seoul")
    public void scheduledFullPageCrawling() {
        log.info("스케줄된 전체 페이지 크롤링 작업 시작");

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

        } catch (Exception e) {
            log.error("스케줄된 전체 페이지 크롤링 작업 중 오류 발생", e);
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
     * Google Analytics 조회수 동기화 스케줄러
     * 매일 새벽 3시에 실행 (어제 하루 조회수를 기존 조회수에 추가)
     */
    @Scheduled(cron = "${crawler.schedule.ga-sync.cron:0 0 3 * * ?}", zone = "Asia/Seoul")
    public void scheduledGoogleAnalyticsSync() {
        log.info("스케줄된 GA 조회수 동기화 작업 시작");

        try {
            if (!googleAnalyticsService.isEnabled()) {
                log.info("Google Analytics가 비활성화되어 있어 동기화를 건너뜁니다.");
                return;
            }

            int syncCount = googleAnalyticsService.syncDailyViewCounts();
            log.info("스케줄된 GA 조회수 동기화 작업 완료 - 동기화된 기업: {}개", syncCount);

        } catch (Exception e) {
            log.error("스케줄된 GA 조회수 동기화 작업 중 오류 발생", e);
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

        int successCount = 0;
        int failureCount = 0;
        int skippedByTimeout = 0;
        WebDriver driver = null;
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

            // WebDriver 생성 (배치 전체에서 재사용)
            driver = webDriverConfig.createWebDriver();

            for (Article article : articles) {
                // 25분 타임아웃 체크
                long elapsedTime = System.currentTimeMillis() - startTime;
                if (elapsedTime >= MEDIUM_CRAWL_TIMEOUT_MS) {
                    skippedByTimeout = articles.size() - successCount - failureCount;
                    log.warn("25분 타임아웃 도달 - 크롤링 종료 (경과: {}분, 남은 Article: {}개)",
                            elapsedTime / 60000, skippedByTimeout);
                    break;
                }

                try {
                    // BlogType에 따라 적절한 크롤러 선택
                    BlogType blogType = article.getCorporation().getBlogType();
                    String content;

                    if (blogType == BlogType.MEDIUM) {
                        content = mediumBlogCrawler.extractArticleContent(article.getLink(), driver);
                    } else {
                        content = defaultBlogCrawler.extractArticleContent(article.getLink(), driver);
                    }

                    if (content != null && !content.isBlank() && content.length() > MAX_CONTENT_LENGTH) {
                        // 본문 업데이트 (독립 트랜잭션)
                        updateArticleContent(article.getId(), content);
                        successCount++;
                        log.debug("Article {} 본문 추출 완료 ({}자, 타입: {})",
                                article.getId(), content.length(), blogType);
                    } else {
                        failureCount++;
                        log.warn("Article {} 본문 추출 실패 또는 여전히 짧음 ({}자, 타입: {})",
                                article.getId(), content != null ? content.length() : 0, blogType);
                    }

                    // Rate limiting
                    Thread.sleep(RATE_LIMIT_DELAY_MS);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("본문 백필 크롤링 중단됨");
                    break;
                } catch (Exception e) {
                    failureCount++;
                    log.error("Article {} 본문 추출 실패: {}", article.getId(), e.getMessage());
                }
            }

            long totalElapsed = System.currentTimeMillis() - startTime;
            log.info("스케줄된 본문 백필 크롤링 작업 완료 - 성공: {}개, 실패: {}개, 타임아웃 스킵: {}개, 소요시간: {}분",
                    successCount, failureCount, skippedByTimeout, totalElapsed / 60000);

        } catch (Exception e) {
            log.error("스케줄된 본문 백필 크롤링 작업 중 오류 발생", e);
        } finally {
            if (driver != null) {
                try {
                    webDriverConfig.forceCloseWebDriver(driver);
                } catch (Exception e) {
                    log.warn("WebDriver 종료 중 오류 발생 (무시)", e);
                }
            }
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

    /**
     * 신규 크롤링된 Article에 대해 Clova 임베딩 생성
     * content가 있는 Article만 처리
     */
    private void generateClovaEmbeddingsForNewArticles(List<CrawlResult> results) {
        try {
            // 성공한 결과에서 content가 있는 신규 Article 수집
            List<Article> newArticles = results.stream()
                    .filter(CrawlResult::hasNewArticles)
                    .flatMap(result -> result.getArticles().stream())
                    .filter(article -> article.getContent() != null && !article.getContent().isBlank())
                    .toList();

            if (newArticles.isEmpty()) {
                log.info("Clova 임베딩 생성 대상 신규 Article이 없습니다.");
                return;
            }

            log.info("신규 Article {}개에 대해 Clova 임베딩 생성 시작", newArticles.size());

            int successCount = 0;
            int failureCount = 0;
            int totalChunks = 0;

            for (Article article : newArticles) {
                try {
                    int chunksGenerated = clovaEmbeddingBatchService.generateClovaChunkEmbeddingsForArticle(article);
                    if (chunksGenerated > 0) {
                        successCount++;
                        totalChunks += chunksGenerated;
                        log.debug("Article {} Clova 임베딩 생성 완료: {}개 청크", article.getId(), chunksGenerated);
                    } else {
                        failureCount++;
                        log.warn("Article {} Clova 임베딩 생성 실패 (청크 0개)", article.getId());
                    }
                } catch (Exception e) {
                    failureCount++;
                    log.error("Article {} Clova 임베딩 생성 실패: {}", article.getId(), e.getMessage());
                }
            }

            log.info("Clova 임베딩 생성 완료 - 성공: {}개, 실패: {}개, 총 청크: {}개",
                    successCount, failureCount, totalChunks);

        } catch (Exception e) {
            log.error("Clova 임베딩 생성 중 오류 발생", e);
        }
    }
}