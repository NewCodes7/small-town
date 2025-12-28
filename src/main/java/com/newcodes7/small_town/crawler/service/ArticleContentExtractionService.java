package com.newcodes7.small_town.crawler.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.openqa.selenium.WebDriver;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.service.ContentExtractor;
import com.newcodes7.small_town.crawler.config.WebDriverConfig;
import com.newcodes7.small_town.global.entity.Article;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Article 본문 추출 서비스
 * Admin 인터페이스를 통해 Article의 본문(content)을 추출하고 저장합니다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArticleContentExtractionService {

    private final ArticleRepository articleRepository;
    private final ContentExtractor contentExtractor;
    private final WebDriverConfig webDriverConfig;

    // Rate limiting 딜레이 (500ms)
    private static final long RATE_LIMIT_DELAY_MS = 500;

    /**
     * 단일 Article의 본문을 추출하여 저장합니다.
     *
     * @param articleId 본문을 추출할 Article의 ID
     * @return 추출 결과
     */
    public Map<String, Object> extractContentForSingleArticle(Long articleId) {
        Map<String, Object> result = new HashMap<>();

        log.info("Article {} 본문 추출 시작", articleId);

        // Article 조회
        Optional<Article> articleOpt = articleRepository.findById(articleId);
        if (articleOpt.isEmpty()) {
            result.put("success", false);
            result.put("message", "Article을 찾을 수 없습니다: " + articleId);
            return result;
        }

        Article article = articleOpt.get();

        if (article.getLink() == null || article.getLink().isBlank()) {
            result.put("success", false);
            result.put("message", "Article의 link가 비어있습니다: " + articleId);
            return result;
        }

        // 본문 추출
        String extractedContent = extractContent(article);

        if (extractedContent.isBlank()) {
            result.put("success", false);
            result.put("message", "본문 추출 실패: 추출된 내용이 비어있습니다");
            return result;
        }

        result.put("success", true);
        result.put("articleId", articleId);
        result.put("contentLength", extractedContent.length());
        result.put("message", "본문 추출 성공 (" + extractedContent.length() + "자)");

        log.info("Article {} 본문 추출 완료 ({}자)", articleId, extractedContent.length());

        return result;
    }

    /**
     * Article의 본문을 추출합니다 (WebDriver 자동 생성 및 종료).
     *
     * @param article 본문을 추출할 Article
     * @return 추출된 본문 (실패 시 빈 문자열)
     */
    public String extractContent(Article article) {
        WebDriver driver = null;

        try {
            log.info("Article {} 본문 추출 시작", article.getId());

            if (article.getLink() == null || article.getLink().isBlank()) {
                log.warn("Article의 link가 비어있습니다: {}", article.getId());
                return "";
            }

            // WebDriver 생성
            driver = webDriverConfig.createWebDriver();

            // 본문 추출
            String extractedContent = contentExtractor.extractCleanContent(article.getLink(), driver);

            if (extractedContent.isBlank()) {
                log.warn("본문 추출 실패: 추출된 내용이 비어있습니다 - Article: {}", article.getId());
                return "";
            }

            log.info("Article {} 본문 추출 완료 ({}자)", article.getId(), extractedContent.length());

            return extractedContent;

        } catch (Exception e) {
            log.error("Article {} 본문 추출 중 오류 발생", article.getId(), e);
            return "";

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
     * Article의 본문을 추출합니다 (기존 WebDriver 재사용).
     * WebDriver는 호출자가 관리하므로 이 메서드에서 종료하지 않습니다.
     *
     * @param article 본문을 추출할 Article
     * @param driver 재사용할 WebDriver 인스턴스
     * @return 추출된 본문 (실패 시 빈 문자열)
     */
    public String extractContent(Article article, WebDriver driver) {
        try {
            log.info("Article {} 본문 추출 시작 (driver 재사용)", article.getId());

            if (article.getLink() == null || article.getLink().isBlank()) {
                log.warn("Article의 link가 비어있습니다: {}", article.getId());
                return "";
            }

            if (driver == null) {
                log.warn("WebDriver가 null입니다. 새 driver를 생성합니다.");
                return extractContent(article);
            }

            // 본문 추출
            String extractedContent = contentExtractor.extractCleanContent(article.getLink(), driver);

            if (extractedContent.isBlank()) {
                log.warn("본문 추출 실패: 추출된 내용이 비어있습니다 - Article: {}", article.getId());
                return "";
            }

            log.info("Article {} 본문 추출 완료 ({}자)", article.getId(), extractedContent.length());

            return extractedContent;

        } catch (Exception e) {
            log.error("Article {} 본문 추출 중 오류 발생", article.getId(), e);
            return "";
        }
    }

    /**
     * 여러 Article의 본문을 배치로 추출하여 저장합니다.
     *
     * @param corporationId 특정 Corporation으로 필터링 (null이면 전체)
     * @param withoutContent content가 없는 Article만 추출할지 여부 (기본: true)
     * @param limit 최대 처리 개수 (기본: 50)
     * @return 배치 처리 결과 통계
     */
    public Map<String, Object> extractContentBatch(Long corporationId, Boolean withoutContent, Integer limit) {
        int successCount = 0;
        int failureCount = 0;
        List<String> errors = new ArrayList<>();
        WebDriver driver = null;

        try {
            log.info("배치 본문 추출 시작 - corporationId: {}, withoutContent: {}, limit: {}",
                    corporationId, withoutContent, limit);

            // 기본값 설정
            if (withoutContent == null) {
                withoutContent = true;
            }
            if (limit == null) {
                limit = 50;
            }

            // Article 조회
            List<Article> articles = getArticlesForExtraction(corporationId, withoutContent, limit);

            if (articles.isEmpty()) {
                log.info("배치 추출 대상 Article이 없습니다");
                return Map.of(
                    "totalArticles", 0,
                    "successArticles", 0,
                    "failureArticles", 0,
                    "errors", errors
                );
            }

            log.info("배치 추출 대상: {}개 Article", articles.size());

            // WebDriver 생성 (배치 전체에서 재사용)
            driver = webDriverConfig.createWebDriver();

            // 각 Article 처리
            for (Article article : articles) {
                try {
                    if (article.getLink() == null || article.getLink().isBlank()) {
                        failureCount++;
                        String errorMsg = "Article " + article.getId() + ": link가 비어있음";
                        errors.add(errorMsg);
                        log.warn(errorMsg);
                        continue;
                    }

                    // 본문 추출
                    String extractedContent = contentExtractor.extractCleanContent(article.getLink(), driver);

                    if (extractedContent.isBlank()) {
                        failureCount++;
                        String errorMsg = "Article " + article.getId() + ": 본문 추출 실패 (빈 내용)";
                        errors.add(errorMsg);
                        log.warn(errorMsg);
                    } else {
                        // Article content 업데이트 (독립 트랜잭션)
                        updateArticleContent(article.getId(), extractedContent);
                        successCount++;
                        log.debug("Article {} 본문 추출 완료 ({}자)", article.getId(), extractedContent.length());
                    }

                    // Rate limiting 딜레이
                    Thread.sleep(RATE_LIMIT_DELAY_MS);

                    // 진행 상황 로깅 (10개마다)
                    int processed = successCount + failureCount;
                    if (processed % 10 == 0) {
                        log.info("본문 추출 진행: {}/{} Articles (성공: {}, 실패: {})",
                                processed, articles.size(), successCount, failureCount);
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failureCount++;
                    String errorMsg = "Article " + article.getId() + ": 중단됨";
                    errors.add(errorMsg);
                    log.error(errorMsg, e);
                    break; // 중단 시 루프 종료

                } catch (Exception e) {
                    failureCount++;
                    String errorMsg = "Article " + article.getId() + ": " + e.getMessage();
                    errors.add(errorMsg);
                    log.error("Article {} 본문 추출 실패", article.getId(), e);
                    // 계속 진행
                }
            }

            log.info("배치 본문 추출 완료 - 성공: {}/{}", successCount, articles.size());

            return Map.of(
                "totalArticles", articles.size(),
                "successArticles", successCount,
                "failureArticles", failureCount,
                "errors", errors
            );

        } catch (Exception e) {
            log.error("배치 본문 추출 중 오류 발생", e);
            return Map.of(
                "totalArticles", 0,
                "successArticles", successCount,
                "failureArticles", failureCount,
                "errors", errors,
                "fatalError", e.getMessage()
            );

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
     * 추출 대상 Article 목록을 조회합니다.
     * 항상 최신순(createdAt DESC)으로 정렬됩니다.
     */
    private List<Article> getArticlesForExtraction(Long corporationId, boolean withoutContent, int limit) {
        // 최신순 정렬
        Pageable pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"));

        if (withoutContent) {
            // content가 없는 Article만 최신순으로 조회
            return articleRepository.findArticlesWithoutContent(pageable);
        } else {
            // 모든 Article을 최신순으로 조회
            return articleRepository.findByDeletedAtIsNull(pageable).getContent();
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

            log.debug("Article {} content 업데이트 완료", articleId);

        } catch (Exception e) {
            log.error("Article {} content 업데이트 실패", articleId, e);
            throw e;
        }
    }
}
