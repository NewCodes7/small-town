package com.newcodes7.small_town.crawler.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.openqa.selenium.WebDriver;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.crawler.config.WebDriverConfig;
import com.newcodes7.small_town.crawler.dto.ArticleAnalysisResponse;
import com.newcodes7.small_town.crawler.dto.CrawlResult;
import com.newcodes7.small_town.crawler.dto.CrawlingStats;
import com.newcodes7.small_town.crawler.exception.CorporationCrawlingException;
import com.newcodes7.small_town.crawler.exception.CrawlerException;
import com.newcodes7.small_town.crawler.exception.CrawlerNotFoundException;
import com.newcodes7.small_town.crawler.repository.ArticleTagRepository;
import com.newcodes7.small_town.crawler.repository.CategoryRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerArticleRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerCorporationRepository;
import com.newcodes7.small_town.crawler.repository.TagRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.ArticleTag;
import com.newcodes7.small_town.global.entity.Category;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.entity.Tag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CrawlingService {
    
    private final CrawlerCorporationRepository crawlerCorporationRepository;
    private final CrawlerArticleRepository crawlerArticleRepository;
    private final CategoryRepository categoryRepository;
    private final TagRepository tagRepository;
    private final ArticleTagRepository articleTagRepository;
    private final ApplicationContext applicationContext;
    private final RobotsTxtService robotsTxtService;
    private final WebDriverConfig webDriverConfig;
    private final OpenaiService openaiService;

    /**
     * 모든 기업 블로그 크롤링 (동기 처리)
     */
    public List<CrawlResult> crawlAllBlogs() {
        List<Corporation> corporations = crawlerCorporationRepository.findAllWithBlogLink();
        log.info("크롤링 시작 - 대상 기업: {}개", corporations.size());

        List<CrawlResult> results = new ArrayList<>();

        // 기업별로 WebDriver를 새로 생성하여 메모리 누적 방지
        for (Corporation corporation : corporations) {
            WebDriver driver = null;
            try {
                driver = webDriverConfig.createWebDriver();
                CrawlResult result = crawlSingleBlog(corporation.getId(), driver);
                results.add(result);

                log.info("기업 크롤링 완료 - {}: {} 진행", corporation.getName(),
                    results.size() + "/" + corporations.size());

            } catch (Exception e) {
                log.error("기업 ID {} 크롤링 중 오류 발생: {}", corporation.getId(), e.getMessage(), e);
                results.add(CrawlResult.failure(corporation, "크롤링 실행 실패: " + e.getMessage()));
            } finally {
                if (driver != null) {
                    webDriverConfig.forceCloseWebDriver(driver);
                    // 메모리 정리를 위한 대기 시간
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("메모리 정리 대기 중 인터럽트 발생");
                    }
                }
            }
        }

        log.info("전체 크롤링 완료 - 처리된 기업: {}개", results.size());
        return results;
    }
    
    /**
     * 특정 기업 블로그 크롤링
     */
    @Transactional
    public CrawlResult crawlSingleBlog(Long corporationId, WebDriver driver) {
        Corporation corporation = crawlerCorporationRepository.findByIdAndNotDeleted(corporationId);
        if (corporation == null) {
            throw new CorporationCrawlingException(corporationId);
        }
        
        if (corporation.getBlogLink() == null || corporation.getBlogLink().trim().isEmpty()) {
            throw new CorporationCrawlingException(corporationId, "empty or null blog URL");
        }
        
        boolean isDriverProvided = (driver != null);
        if (!isDriverProvided) {
            driver = webDriverConfig.createWebDriver();
        }
        try {
            // 적절한 크롤러 선택
            BlogCrawler crawler = selectCrawler(corporation.getBlogLink());
            log.info("크롤링 시작 - 기업: {}, 크롤러: {}", corporation.getName(), crawler.getProviderName());
            
            // robots.txt 확인 및 크롤링 실행
            String baseUrl = crawler.extractBaseUrl(corporation.getBlogLink());
            log.info("robots.txt 확인 - 기업: {}, 블로그URL: {}, 베이스URL: {}", corporation.getName(), corporation.getBlogLink(), baseUrl);
            
            boolean isAllowed = robotsTxtService.isPathAllowed(baseUrl, "/");
            log.info("robots.txt 확인 결과 - 기업: {}, 허용 여부: {}", corporation.getName(), isAllowed);
            
            if (!isAllowed) {
                log.warn("robots.txt에 의해 크롤링이 금지됨 - 기업: {}, 블로그URL: {}, 베이스URL: {}", corporation.getName(), corporation.getBlogLink(), baseUrl);
                return CrawlResult.failure(corporation, "robots.txt에 의해 크롤링 금지됨");
            }
            
            log.info("robots.txt 확인 완료 - 크롤링 허용됨");
            List<Article> crawledArticles = crawler.crawlWithRobotsCheck(driver, corporation, robotsTxtService);
            
            // 중복 제거 및 저장
            List<Article> newArticles = new ArrayList<>();
            for (Article article : crawledArticles) {
                if (!crawlerArticleRepository.findFirstByLinkAndDeletedAtIsNull(article.getLink()).isPresent()) {
                    crawler.processImageUpload(article, corporation);

                    // article 저장
                    crawlerArticleRepository.save(article);

                    // 해외 기업의 영어 제목 자동 번역
                    translateTitleIfNeeded(article, corporation);

                    ArticleAnalysisResponse openAiResponse = openaiService.sendArticleAnalysis(article);

                    // TODO: openai 분석 결과 성공 시 저장, 실패 시 롤백 유도

                    // 카테고리 저장 (있다면 기존 id 활용)
                    Category category = categoryRepository.findByName(openAiResponse.getCategory())
                                        .orElseGet(() -> categoryRepository.save(openAiResponse.toCategoryEntity()));
                    article.setCategory(category);

                    // // Tag 저장 + ArticleTag 저장
                    // Set<Tag> tags = openAiResponse.toTagEntities().stream()
                    //         .map(tag -> tagRepository.findByKeyword(tag.getKeyword())
                    //                 .orElseGet(() -> tagRepository.save(tag))
                    //             )
                    //         .collect(Collectors.toSet());
                    // article.setArticleTags(
                    //     tags.stream()
                    //         .map(tag -> ArticleTag.builder()
                    //                 .article(article)
                    //                 .tag(tag)
                    //                 .build())
                    //         .collect(Collectors.toSet())
                    // );

                    // // summary 저장
                    // article.getSummaries().clear();
                    // article.getSummaries().addAll(openAiResponse.getSummaries().stream()
                    //         .map(summary -> {
                    //             summary.setArticle(article);
                    //             return summary;
                    //         })
                    //         .toList());

                    newArticles.add(article);
                } else {
                    log.debug("중복 게시글 스킵: {}", article.getLink());
                }
            }

            int newArticlesCount = newArticles.size();
            log.info("크롤링 완료 - 기업: {}, 전체: {}개, 신규: {}개", 
                corporation.getName(), crawledArticles.size(), newArticlesCount);
            
            return CrawlResult.success(corporation, newArticles, newArticlesCount);
            
            // TODO: catch 있어도 롤백 되나? 
        } catch (CrawlerException e) {
            log.error("크롤링 실패 - 기업: {}, 오류: {}", corporation.getName(), e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("예상치 못한 크롤링 오류 - 기업: {}, 오류: {}", corporation.getName(), e.getMessage(), e);
            throw new CrawlerException("CRAWLER_UNEXPECTED_ERROR", "Unexpected error during crawling for corporation: " + corporation.getName(), e) {};
        } finally {
            if (!isDriverProvided) {
                webDriverConfig.forceCloseWebDriver(driver);
            }
        }
    }
    
    /**
     * 블로그 URL에 따라 적절한 크롤러 선택
     */
    private BlogCrawler selectCrawler(String blogUrl) {
        List<BlogCrawler> crawlers = applicationContext.getBeansOfType(BlogCrawler.class)
                .values()
                .stream()
                .toList();
        
        // 특화된 크롤러 우선 선택
        for (BlogCrawler crawler : crawlers) {
            if (!crawler.getProviderName().equals("Default") && crawler.canHandle(blogUrl)) {
                return crawler;
            }
        }
        
        // 기본 크롤러 반환
        return crawlers.stream()
                .filter(crawler -> crawler.getProviderName().equals("Default"))
                .findFirst()
                .orElseThrow(() -> new CrawlerNotFoundException(blogUrl));
    }
    
    /**
     * 크롤링 통계 조회
     */
    public CrawlingStats getCrawlingStats() {
        LocalDateTime since = LocalDateTime.now().minusDays(1);

        List<Corporation> allCorporations = crawlerCorporationRepository.findAllWithBlogLink();
        long totalCorporations = allCorporations.size();

        long totalNewArticles = 0;
        for (Corporation corp : allCorporations) {
            totalNewArticles += crawlerArticleRepository.countNewArticlesByCorporation(corp.getId(), since);
        }

        return CrawlingStats.builder()
                .totalCorporations(totalCorporations)
                .totalNewArticles(totalNewArticles)
                .lastCrawledAt(LocalDateTime.now())
                .build();
    }

    /**
     * 기존 글들에 대한 AI 분석 실행
     */
    public Map<String, Object> analyzeExistingArticles() {
        Map<String, Object> result = new HashMap<>();

        // AI 분석이 완료되지 않은 글들 조회
        List<Article> unanalyzedArticles = crawlerArticleRepository.findUnanalyzedArticles();

        log.info("AI 분석 대상 글 수: {}개", unanalyzedArticles.size());
        result.put("totalUnanalyzedArticles", unanalyzedArticles.size());

        if (unanalyzedArticles.isEmpty()) {
            result.put("success", true);
            result.put("message", "분석이 필요한 글이 없습니다.");
            result.put("processedCount", 0);
            result.put("successCount", 0);
            result.put("failureCount", 0);
            return result;
        }

        int processedCount = 0;
        int successCount = 0;
        int failureCount = 0;
        List<String> errors = new ArrayList<>();

        // 순차적으로 AI 분석 진행
        for (Article article : unanalyzedArticles) {
            processedCount++;
            log.info("AI 분석 진행 중: {} / {} - {}", processedCount, unanalyzedArticles.size(), article.getTitle());

            try {
                analyzeSingleArticle(article);
                successCount++;
                log.info("AI 분석 완료: {}", article.getTitle());
            } catch (Exception e) {
                failureCount++;
                String errorMsg = String.format("글 ID %d (%s) 분석 실패: %s",
                    article.getId(), article.getTitle(), e.getMessage());
                errors.add(errorMsg);
                log.error(errorMsg, e);
            }
        }

        result.put("success", true);
        result.put("message", String.format("AI 분석 완료. 성공: %d개, 실패: %d개", successCount, failureCount));
        result.put("processedCount", processedCount);
        result.put("successCount", successCount);
        result.put("failureCount", failureCount);

        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }

        log.info("기존 글 AI 분석 완료 - 처리: {}개, 성공: {}개, 실패: {}개",
            processedCount, successCount, failureCount);

        return result;
    }

    /**
     * 개별 글에 대한 AI 분석 (개별 트랜잭션)
     */
    @Transactional
    public void analyzeSingleArticle(Article article) throws Exception {
        // OpenAI로 분석 요청
        ArticleAnalysisResponse openAiResponse = openaiService.sendArticleAnalysis(article);

        // 카테고리 저장 (기존 카테고리가 있다면 재사용)
        Category category = categoryRepository.findByName(openAiResponse.getCategory())
                            .orElseGet(() -> categoryRepository.save(openAiResponse.toCategoryEntity()));
        article.setCategory(category);

        // 태그 저장 및 ArticleTag 연결
        Set<Tag> tags = openAiResponse.toTagEntities().stream()
                .map(tag -> tagRepository.findByKeyword(tag.getKeyword())
                        .orElseGet(() -> tagRepository.save(tag))
                    )
                .collect(Collectors.toSet());

        // 기존 태그 관계 삭제 후 새로 추가
        Set<ArticleTag> articleTags = tags.stream()
                .map(tag -> ArticleTag.builder()
                        .article(article)
                        .tag(tag)
                        .build())
                .collect(Collectors.toSet());
        articleTagRepository.saveAll(articleTags);

        // 요약 정보 저장
        article.getSummaries().clear();
        article.getSummaries().addAll(openAiResponse.getSummaries().stream()
                .map(summary -> {
                    summary.setArticle(article);
                    return summary;
                })
                .toList());

        // 변경사항 저장
        crawlerArticleRepository.save(article);
    }

    /**
     * 글 카테고리 수정
     */
    @Transactional
    public void updateArticleCategory(Long articleId, String categoryName) {
        // 글 조회
        Article article = crawlerArticleRepository.findById(articleId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 글입니다. ID: " + articleId));

        if (article.isDeleted()) {
            throw new IllegalArgumentException("삭제된 글입니다. ID: " + articleId);
        }

        // 카테고리 조회 또는 생성
        Category category = categoryRepository.findByName(categoryName)
                .orElseGet(() -> {
                    Category newCategory = Category.builder()
                            .name(categoryName)
                            .build();
                    return categoryRepository.save(newCategory);
                });

        // 글에 카테고리 설정 및 저장
        article.setCategory(category);
        crawlerArticleRepository.save(article);

        log.info("글 카테고리 수정 완료 - 글 ID: {}, 제목: {}, 카테고리: {}",
            articleId, article.getTitle(), categoryName);
    }

    /**
     * 필요한 경우 제목을 번역합니다.
     * 해외 기업의 영어 제목만 한국어로 번역합니다.
     */
    private void translateTitleIfNeeded(Article article, Corporation corporation) {
        try {
            // 해외 기업인지 확인 (isDomestic = false)
            if (!corporation.getIsDomestic()) {
                String title = article.getTitle();

                // 제목에 한국어가 포함되어 있지 않으면 번역
                if (title != null && !openaiService.containsKorean(title)) {
                    log.debug("영어 제목 번역 시도 - 기업: {}, 제목: {}", corporation.getName(), title);

                    String translatedTitle = openaiService.translateTitle(title, corporation.getName());

                    if (translatedTitle != null && !translatedTitle.trim().isEmpty()) {
                        article.setTranslatedTitle(translatedTitle);
                        crawlerArticleRepository.save(article);

                        log.info("제목 번역 완료 - 기업: {}, 원본: '{}' → 번역: '{}'",
                            corporation.getName(), title, translatedTitle);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("제목 번역 중 오류 발생 - 기업: {}, 제목: {}, 오류: {}",
                corporation.getName(), article.getTitle(), e.getMessage());
            // 번역 실패는 크롤링을 중단시키지 않음
        }
    }
}