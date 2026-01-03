package com.newcodes7.small_town.crawler.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.openqa.selenium.WebDriver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.service.ArticleTermService;
import com.newcodes7.small_town.article.service.ContentExtractor;
import com.newcodes7.small_town.crawler.config.WebDriverConfig;
import com.newcodes7.small_town.global.entity.Article;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Content 및 Term 추출 스케줄러
 * 매시간 30분에 실행되어 content가 없는 Article에 대해:
 * 1. content 추출
 * 2. term 추출
 *
 * 정각 5분 전(25분 이내)에 작업을 완료합니다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "content.extraction", name = "scheduled.enabled", havingValue = "true", matchIfMissing = true)
public class ContentAndTermExtractionScheduler {

    private final ArticleRepository articleRepository;
    private final ContentExtractor contentExtractor;
    private final ArticleTermService articleTermService;
    private final WebDriverConfig webDriverConfig;

    // 작업 제한 시간: 25분
    private static final long MAX_EXECUTION_TIME_MINUTES = 25;

    // Rate limiting 딜레이: 500ms
    private static final long RATE_LIMIT_DELAY_MS = 500;

    // 한 번에 조회할 최대 Article 수
    private static final int MAX_FETCH_SIZE = 1000;

    /**
     * Content 및 Term 추출 스케줄러
     * 매시간 30분에 실행 (예: 00:30, 01:30, 02:30, ...)
     */
    // @Scheduled(cron = "${content.extraction.schedule.cron:0 30 * * * ?}", zone = "Asia/Seoul")
    public void scheduledContentAndTermExtraction() {
        log.info("=== 스케줄된 Content 및 Term 추출 작업 시작 ===");

        Instant startTime = Instant.now();
        int processedCount = 0;
        int successCount = 0;
        int failureCount = 0;
        WebDriver driver = null;

        try {
            // content가 없는 Article 조회 (최신순, 최대 1000개)
            Pageable pageable = PageRequest.of(0, MAX_FETCH_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"));
            List<Article> articlesWithoutContent = articleRepository.findArticlesWithoutContent(pageable);

            if (articlesWithoutContent.isEmpty()) {
                log.info("처리할 Article이 없습니다. 작업을 종료합니다.");
                return;
            }

            log.info("총 {}개의 Article 발견 (content 없음). 최대 25분 동안 처리를 시작합니다.",
                    articlesWithoutContent.size());

            // WebDriver 생성 (재사용)
            driver = webDriverConfig.createWebDriver();

            // 각 Article 처리
            for (Article article : articlesWithoutContent) {
                // 시간 체크: 25분 경과 시 중단
                Duration elapsed = Duration.between(startTime, Instant.now());
                if (elapsed.toMinutes() >= MAX_EXECUTION_TIME_MINUTES) {
                    log.warn("작업 시간 제한 도달 ({}분). 처리를 중단합니다. 처리: {}/{}",
                            MAX_EXECUTION_TIME_MINUTES, processedCount, articlesWithoutContent.size());
                    break;
                }

                try {
                    // 1. Content 추출
                    boolean contentExtracted = extractContentForArticle(article, driver);

                    if (!contentExtracted) {
                        failureCount++;
                        log.debug("Article {} content 추출 실패", article.getId());
                        continue;
                    }

                    // 2. Term 추출 (content 추출 성공 시에만)
                    extractTermsForArticle(article.getId());

                    successCount++;
                    processedCount++;

                    // Rate limiting
                    Thread.sleep(RATE_LIMIT_DELAY_MS);

                    // 진행 상황 로깅 (50개마다)
                    if (processedCount % 50 == 0) {
                        Duration currentElapsed = Duration.between(startTime, Instant.now());
                        log.info("진행 중: {}/{} 처리 (성공: {}, 실패: {}), 경과 시간: {}분",
                                processedCount, articlesWithoutContent.size(),
                                successCount, failureCount,
                                currentElapsed.toMinutes());
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("작업이 중단되었습니다", e);
                    break;

                } catch (Exception e) {
                    failureCount++;
                    processedCount++;
                    log.error("Article {} 처리 중 오류 발생", article.getId(), e);
                    // 계속 진행
                }
            }

            Duration totalElapsed = Duration.between(startTime, Instant.now());
            log.info("=== Content 및 Term 추출 작업 완료 ===");
            log.info("총 처리: {}개, 성공: {}개, 실패: {}개, 소요 시간: {}분 {}초",
                    processedCount, successCount, failureCount,
                    totalElapsed.toMinutes(), totalElapsed.getSeconds() % 60);

        } catch (Exception e) {
            log.error("스케줄된 Content 및 Term 추출 작업 중 오류 발생", e);

        } finally {
            // WebDriver 종료
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
     * Article의 content를 추출하여 저장합니다.
     *
     * @param article 대상 Article
     * @param driver 재사용할 WebDriver
     * @return content 추출 성공 여부
     */
    private boolean extractContentForArticle(Article article, WebDriver driver) {
        try {
            if (article.getLink() == null || article.getLink().isBlank()) {
                log.debug("Article {} link가 비어있음", article.getId());
                return false;
            }

            // Content 추출
            String extractedContent = contentExtractor.extractCleanContent(article.getLink(), driver);

            if (extractedContent == null || extractedContent.isBlank()) {
                log.debug("Article {} content 추출 실패 (빈 내용)", article.getId());
                return false;
            }

            // Content 저장 (독립 트랜잭션)
            updateArticleContent(article.getId(), extractedContent);

            log.debug("Article {} content 추출 완료 ({}자)", article.getId(), extractedContent.length());
            return true;

        } catch (Exception e) {
            log.error("Article {} content 추출 중 오류", article.getId(), e);
            return false;
        }
    }

    /**
     * Article의 content를 업데이트합니다.
     * 독립 트랜잭션으로 실행되어 개별 실패가 전체에 영향을 주지 않습니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateArticleContent(Long articleId, String content) {
        try {
            Article article = articleRepository.findById(articleId)
                .orElseThrow(() -> new IllegalArgumentException("Article not found: " + articleId));

            article.setContent(content);
            articleRepository.save(article);

        } catch (Exception e) {
            log.error("Article {} content 업데이트 실패", articleId, e);
            throw e;
        }
    }

    /**
     * Article의 term을 추출하여 저장합니다.
     *
     * @param articleId 대상 Article ID
     */
    private void extractTermsForArticle(Long articleId) {
        try {
            Article article = articleRepository.findById(articleId)
                .orElseThrow(() -> new IllegalArgumentException("Article not found: " + articleId));

            int termCount = articleTermService.extractAndSaveTermsForArticle(article);

            log.debug("Article {} term 추출 완료 ({} terms)", articleId, termCount);

        } catch (Exception e) {
            log.error("Article {} term 추출 중 오류", articleId, e);
            throw e;
        }
    }
}
