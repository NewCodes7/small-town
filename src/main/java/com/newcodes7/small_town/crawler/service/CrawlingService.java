package com.newcodes7.small_town.crawler.service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openqa.selenium.WebDriver;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import com.newcodes7.small_town.article.service.ArticleTermService;
import com.newcodes7.small_town.crawler.config.WebDriverConfig;
import com.newcodes7.small_town.crawler.crawler.BlogCrawler;
import com.newcodes7.small_town.crawler.dto.CrawlResult;
import com.newcodes7.small_town.crawler.dto.CrawlingStats;
import com.newcodes7.small_town.crawler.entity.CrawlingStepStatus;
import com.newcodes7.small_town.crawler.entity.CrawlingStepType;
import com.newcodes7.small_town.crawler.exception.CorporationCrawlingException;
import com.newcodes7.small_town.crawler.exception.CrawlerException;
import com.newcodes7.small_town.crawler.exception.CrawlerNotFoundException;
import com.newcodes7.small_town.crawler.integration.robotstxt.RobotsTxtService;
import com.newcodes7.small_town.crawler.persistence.ArticlePersistenceService;
import com.newcodes7.small_town.crawler.repository.CrawlerArticleRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerCorporationRepository;
import com.newcodes7.small_town.embedding.service.ChunkEmbeddingBatchService;
import com.newcodes7.small_town.embedding.service.RepresentativeChunkService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CrawlingService {

    private final CrawlerCorporationRepository crawlerCorporationRepository;
    private final CrawlerArticleRepository crawlerArticleRepository;
    private final ApplicationContext applicationContext;
    private final RobotsTxtService robotsTxtService;
    private final WebDriverConfig webDriverConfig;
    private final ArticlePersistenceService articlePersistenceService;
    private final ArticleContentExtractionService articleContentExtractionService;
    private final CrawlingRunService crawlingRunService;
    private final ArticleTermService articleTermService;
    private final ChunkEmbeddingBatchService chunkEmbeddingBatchService;
    private final RepresentativeChunkService representativeChunkService;

    /**
     * 모든 기업 블로그 크롤링 (배치)
     *
     * 기업별: crawlSingleBlog (새 글 수집 → 본문/Term 분석 → Embedding 생성)
     * 전체 완료 후: 대표 Chunk 선택
     * article_analyzed_content는 Term 추출 시 자동 갱신되므로 별도 인덱스 갱신 불필요
     */
    public List<CrawlResult> crawlAllBlogs() {
        List<Corporation> corporations = crawlerCorporationRepository.findAllWithBlogLink();
        log.info("블로그 크롤링 시작 - 대상 기업: {}개", corporations.size());

        List<CrawlResult> results = new ArrayList<>();
        List<Article> allNewArticles = new ArrayList<>();

        for (Corporation corporation : corporations) {
            WebDriver driver = null;
            CrawlResult result = null;
            try {
                driver = webDriverConfig.createWebDriver();
                result = crawlSingleBlog(corporation.getId(), driver);
                allNewArticles.addAll(result.getArticles());
                log.info("기업 크롤링 완료 - {}: {}/{} 진행", corporation.getName(),
                    results.size() + 1, corporations.size());
            } catch (Exception e) {
                log.error("기업 ID {} 크롤링 중 오류 발생: {}", corporation.getId(), e.getMessage(), e);
                result = CrawlResult.failure(corporation, "크롤링 실행 실패: " + e.getMessage());
            } finally {
                if (driver != null) {
                    webDriverConfig.forceCloseWebDriver(driver);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("메모리 정리 대기 중 인터럽트 발생");
                    }
                }
            }
            results.add(result);
        }

        log.info("블로그 크롤링 완료 - 처리된 기업: {}개", results.size());

        if (!allNewArticles.isEmpty()) {
            // article_analyzed_content는 Term 추출 시 자동 갱신되므로 별도 refresh 불필요
            selectRepresentativeChunksForArticles(allNewArticles, true);
        }

        return results;
    }

    /**
     * 특정 기업 블로그 크롤링 (기업 단위 처리)
     *
     * 새 글 수집 → 본문/Term 분석 → Embedding 생성
     * BM25 인덱스 갱신과 대표 Chunk 선택은 포함하지 않음.
     * 단일 기업 즉시 실행 시 crawlAllBlogs를 통해 호출하거나, 호출 후 별도 처리 필요.
     */
    public CrawlResult crawlSingleBlog(Long corporationId, WebDriver driver) {
        Corporation corporation = crawlerCorporationRepository.findByIdAndNotDeleted(corporationId);
        if (corporation == null) {
            throw new CorporationCrawlingException(corporationId);
        }

        boolean hasBlogLink = corporation.getBlogLink() != null && !corporation.getBlogLink().trim().isEmpty();
        if (!hasBlogLink) {
            throw new CorporationCrawlingException(corporationId, "empty or null blog URL");
        }

        boolean isDriverProvided = (driver != null);
        if (!isDriverProvided) {
            driver = webDriverConfig.createWebDriver();
        }

        try {
            CrawlResult result = fetchAndPersistNewArticles(corporation, driver);
            if (!result.hasNewArticles()) {
                return result;
            }

            List<Article> newArticles = result.getArticles();
            extractContentAndTermsForArticles(newArticles, driver);
            generateEmbeddingsForArticles(newArticles);

            return result;

        } catch (CrawlerException e) {
            log.error("블로그 크롤링 실패 - 기업: {}, 오류: {}", corporation.getName(), e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("예상치 못한 블로그 크롤링 오류 - 기업: {}, 오류: {}", corporation.getName(), e.getMessage(), e);
            throw new CrawlerException("CRAWLER_UNEXPECTED_ERROR", "Unexpected error during blog crawling for corporation: " + corporation.getName(), e) {};
        } finally {
            if (!isDriverProvided) {
                webDriverConfig.forceCloseWebDriver(driver);
            }
        }
    }

    /**
     * 블로그 크롤링 및 신규 Article 저장
     * 본문/Term 추출은 이후 단계에서 별도 처리
     */
    private CrawlResult fetchAndPersistNewArticles(Corporation corporation, WebDriver driver) throws IOException {
        List<Article> newArticles = new ArrayList<>();

        BlogCrawler blogCrawler = selectCrawler(corporation);
        log.info("블로그 크롤링 시작 - 기업: {}, 크롤러: {}", corporation.getName(), blogCrawler.getProviderName());

        // robots.txt 확인 및 크롤링 실행
        String baseUrl = blogCrawler.extractBaseUrl(corporation.getBlogLink());
        boolean isAllowed = robotsTxtService.isPathAllowed(baseUrl, "/");

        if (!isAllowed) {
            log.warn("robots.txt에 의해 블로그 크롤링이 금지됨 - 기업: {}", corporation.getName());
            return CrawlResult.success(corporation, newArticles, 0);
        }

        List<Article> blogArticles = blogCrawler.crawlWithRobotsCheck(driver, corporation, robotsTxtService);

        // 중복 제거 및 저장 (link 또는 title이 같으면 중복) - 배치 조회로 N+1 방지
        Set<String> existingLinks = loadExistingLinks(blogArticles);
        Set<String> existingTitles = loadExistingTitles(blogArticles, corporation.getId());

        for (Article article : blogArticles) {
            if (!existingLinks.contains(article.getLink()) && !existingTitles.contains(article.getTitle())) {
                articlePersistenceService.saveArticleWithAnalysis(article, corporation, blogCrawler);
                newArticles.add(article);
            }
        }

        log.info("새 글 수집 완료 - 기업: {}, 조회: {}개, 신규: {}개",
                 corporation.getName(), blogArticles.size(), newArticles.size());

        return CrawlResult.success(corporation, newArticles, newArticles.size());
    }

    /**
     * 본문 추출 + Term 분석 및 저장
     * 본문 추출 실패 시 title 기반 term 분석으로 fallback
     */
    private void extractContentAndTermsForArticles(List<Article> articles, WebDriver driver) {
        for (Article article : articles) {
            try {
                String content = articleContentExtractionService.extractContent(article, driver);

                if (content == null || content.trim().isEmpty()) {
                    log.warn("본문 추출 실패 - 본문이 비어있음: {}", article.getTitle());
                    crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.CONTENT_EXTRACTION, CrawlingStepStatus.FAILURE, "본문이 비어있음");
                    try {
                        int termCount = articleTermService.extractAndSaveTermsForArticle(article);
                        log.info("Title 기반 Term 추출 완료 - Article: {}, Term 수: {}개", article.getTitle(), termCount);
                        crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.TERM_ANALYSIS, CrawlingStepStatus.SUCCESS, null);
                    } catch (Exception e) {
                        log.error("Title 기반 Term 추출 실패 - Article: {}, 오류: {}", article.getTitle(), e.getMessage(), e);
                        crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.TERM_ANALYSIS, CrawlingStepStatus.FAILURE, e.getMessage());
                    }
                    continue;
                }

                log.info("본문 추출 성공 - Article: {}, 본문 길이: {}자", article.getTitle(), content.length());
                article.setContent(content);
                articleContentExtractionService.updateArticleContent(article.getId(), content);
                crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.CONTENT_EXTRACTION, CrawlingStepStatus.SUCCESS, null);

                try {
                    int termCount = articleTermService.extractAndSaveTermsForArticle(article);
                    log.info("Term 추출 완료 - Article: {}, Term 수: {}개", article.getTitle(), termCount);
                    crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.TERM_ANALYSIS, CrawlingStepStatus.SUCCESS, null);
                } catch (Exception e) {
                    log.error("Term 추출 실패 - Article: {}, 오류: {}", article.getTitle(), e.getMessage(), e);
                    crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.TERM_ANALYSIS, CrawlingStepStatus.FAILURE, e.getMessage());
                }

            } catch (Exception e) {
                log.error("본문/Term 추출 중 오류 발생 - Article: {}, 오류: {}", article.getTitle(), e.getMessage(), e);
                crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.CONTENT_EXTRACTION, CrawlingStepStatus.FAILURE, e.getMessage());
                crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.TERM_ANALYSIS, CrawlingStepStatus.SKIPPED, "본문 추출 실패");
            }
        }
    }

    /**
     * Embedding 생성. content가 있는 Article만 처리
     */
    private void generateEmbeddingsForArticles(List<Article> articles) {
        List<Article> targets = articles.stream()
                .filter(a -> a.getContent() != null && !a.getContent().isBlank())
                .toList();

        if (targets.isEmpty()) {
            log.info("Embedding 생성 대상 Article이 없습니다 (본문 없음).");
            return;
        }

        log.info("Embedding 생성 시작 - {}개 Article", targets.size());
        int successCount = 0;
        int failureCount = 0;

        for (Article article : targets) {
            try {
                int chunksGenerated = chunkEmbeddingBatchService.generateChunkEmbeddingsForArticle(article);
                if (chunksGenerated > 0) {
                    successCount++;
                    log.debug("Article {} Embedding 생성 완료: {}개 청크", article.getId(), chunksGenerated);
                    crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.EMBEDDING, CrawlingStepStatus.SUCCESS, null);
                } else {
                    failureCount++;
                    log.warn("Article {} Embedding 생성 실패 (청크 0개)", article.getId());
                    crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.EMBEDDING, CrawlingStepStatus.FAILURE, "청크 0개");
                }
            } catch (Exception e) {
                failureCount++;
                log.error("Article {} Embedding 생성 실패: {}", article.getId(), e.getMessage(), e);
                crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.EMBEDDING, CrawlingStepStatus.FAILURE, e.getMessage());
            }
        }

        log.info("Embedding 생성 완료 - 성공: {}개, 실패: {}개", successCount, failureCount);
    }

    /**
     * 대표 Chunk 선택. BM25 인덱스 갱신 후 호출해야 정확한 선정 가능
     * Embedding이 없는 Article은 selectRepresentativeChunk 내부에서 null 반환 처리
     *
     * @param bm25RefreshSucceeded BM25 인덱스 갱신 성공 여부. false이면 term frequency fallback으로 선정되었음을 WARNING으로 기록
     */
    private void selectRepresentativeChunksForArticles(List<Article> articles, boolean bm25RefreshSucceeded) {
        for (Article article : articles) {
            try {
                Long chunkId = representativeChunkService.selectRepresentativeChunk(article.getId());
                if (chunkId != null) {
                    if (!bm25RefreshSucceeded) {
                        crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.REPRESENTATIVE_CHUNK, CrawlingStepStatus.WARNING, "BM25 인덱스 갱신 실패로 인해 term frequency 기반으로 선정됨");
                    } else {
                        crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.REPRESENTATIVE_CHUNK, CrawlingStepStatus.SUCCESS, null);
                    }
                } else {
                    log.warn("Article {} 대표 Chunk 선정 실패 (결과 없음)", article.getId());
                    crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.REPRESENTATIVE_CHUNK, CrawlingStepStatus.FAILURE, "대표 chunk 없음");
                }
            } catch (Exception e) {
                log.error("Article {} 대표 Chunk 선정 실패: {}", article.getId(), e.getMessage(), e);
                crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.REPRESENTATIVE_CHUNK, CrawlingStepStatus.FAILURE, e.getMessage());
            }
        }
    }

    /**
     * Corporation의 blogType에 따라 적절한 크롤러 선택
     */
    private BlogCrawler selectCrawler(Corporation corporation) {
        List<BlogCrawler> crawlers = applicationContext.getBeansOfType(BlogCrawler.class)
                .values()
                .stream()
                .toList();

        // 특화된 크롤러 우선 선택 (blogType 기반)
        for (BlogCrawler crawler : crawlers) {
            if (!crawler.getProviderName().equals("Default") && crawler.canHandle(corporation)) {
                return crawler;
            }
        }

        // 기본 크롤러 반환
        return crawlers.stream()
                .filter(crawler -> crawler.getProviderName().equals("Default"))
                .findFirst()
                .orElseThrow(() -> new CrawlerNotFoundException(corporation.getBlogLink()));
    }

    /**
     * 주어진 아티클 목록의 링크 중 DB에 이미 존재하는 것을 Set으로 반환 (배치 조회)
     */
    private Set<String> loadExistingLinks(List<Article> articles) {
        if (articles.isEmpty()) {
            return Set.of();
        }
        List<String> links = articles.stream().map(Article::getLink).toList();
        return new HashSet<>(crawlerArticleRepository.findExistingLinksByLinksIn(links));
    }

    /**
     * 주어진 아티클 목록의 제목 중 해당 기업에 이미 존재하는 것을 Set으로 반환 (배치 조회)
     */
    private Set<String> loadExistingTitles(List<Article> articles, Long corporationId) {
        if (articles.isEmpty()) {
            return Set.of();
        }
        List<String> titles = articles.stream().map(Article::getTitle).toList();
        return new HashSet<>(crawlerArticleRepository.findExistingTitlesByTitlesInAndCorporationId(titles, corporationId));
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
     * 본문만 추출하여 저장 (Admin 전체 페이지 크롤링용, Term/Embedding 생략)
     */
    private void extractContentOnly(Article article, WebDriver driver) {
        try {
            String content = articleContentExtractionService.extractContent(article, driver);

            if (content == null || content.trim().isEmpty()) {
                log.warn("Content 추출 건너뜀 - 본문이 비어있음: {}", article.getTitle());
                crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.CONTENT_EXTRACTION, CrawlingStepStatus.FAILURE, "본문이 비어있음");
                crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.TERM_ANALYSIS, CrawlingStepStatus.SKIPPED, "본문 없음");
                return;
            }

            log.info("본문 추출 성공 - Article: {}, 본문 길이: {}자", article.getTitle(), content.length());

            articleContentExtractionService.updateArticleContent(article.getId(), content);
            log.info("Article 본문 DB 저장 완료 - Article: {}", article.getTitle());
            crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.CONTENT_EXTRACTION, CrawlingStepStatus.SUCCESS, null);
            crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.TERM_ANALYSIS, CrawlingStepStatus.SKIPPED, "Content-only 크롤링");

        } catch (Exception e) {
            log.error("Content 추출 및 저장 중 오류 발생 - Article: {}, 오류: {}",
                    article.getTitle(), e.getMessage(), e);
            crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.CONTENT_EXTRACTION, CrawlingStepStatus.FAILURE, e.getMessage());
            crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.TERM_ANALYSIS, CrawlingStepStatus.SKIPPED, "Content 추출 실패");
        }
    }

    /**
     * 특정 기업의 모든 페이지 크롤링 (Admin 전용)
     * @param corporationId 기업 ID
     * @return 크롤링 결과
     */
    public CrawlResult crawlAllPagesForCorporation(Long corporationId) {
        log.info("Admin 전체 페이지 크롤링 시작 - 기업 ID: {}", corporationId);

        Corporation corporation = crawlerCorporationRepository.findByIdAndNotDeleted(corporationId);
        if (corporation == null) {
            throw new CorporationCrawlingException(corporationId);
        }

        WebDriver driver = null;
        try {
            driver = webDriverConfig.createWebDriver();
            BlogCrawler crawler = selectCrawler(corporation);

            log.info("Admin 전체 페이지 크롤링 - 기업: {}, 크롤러: {}",
                    corporation.getName(), crawler.getProviderName());

            // crawlAllPages 호출
            List<Article> articles = crawler.crawlAllPages(driver, corporation);
            List<Article> newArticles = new ArrayList<>();

            // 중복 체크 및 저장 (link 또는 title이 같으면 중복, 캐시 작업 없이) - 배치 조회로 N+1 방지
            Set<String> existingLinks = loadExistingLinks(articles);
            Set<String> existingTitles = loadExistingTitles(articles, corporation.getId());

            for (Article article : articles) {
                if (!existingLinks.contains(article.getLink()) && !existingTitles.contains(article.getTitle())) {
                    articlePersistenceService.saveArticleWithAnalysisNoCache(article, corporation, crawler);
                    extractContentOnly(article, driver);
                    newArticles.add(article);
                }
            }

            log.info("Admin 전체 페이지 크롤링 완료 - 기업: {}, 수집: {}개, 신규: {}개",
                    corporation.getName(), articles.size(), newArticles.size());

            return CrawlResult.success(corporation, newArticles, newArticles.size());

        } catch (Exception e) {
            log.error("Admin 전체 페이지 크롤링 실패 - 기업: {}, 오류: {}",
                    corporation.getName(), e.getMessage(), e);
            return CrawlResult.failure(corporation, "크롤링 실행 실패: " + e.getMessage());
        } finally {
            if (driver != null) {
                webDriverConfig.forceCloseWebDriver(driver);
            }
        }
    }

    /**
     * 모든 기업의 모든 페이지 크롤링 (Admin 전용)
     * @return 크롤링 결과 목록
     */
    public List<CrawlResult> crawlAllPagesForAllCorporations() {
        log.info("Admin 전체 기업 전체 페이지 크롤링 시작");

        List<Corporation> corporations = crawlerCorporationRepository.findAllWithBlogLink();
        List<CrawlResult> results = new ArrayList<>();

        for (Corporation corporation : corporations) {
            try {
                CrawlResult result = crawlAllPagesForCorporation(corporation.getId());
                results.add(result);

                log.info("기업 전체 페이지 크롤링 완료 - {}: {}/{} 진행",
                        corporation.getName(), results.size(), corporations.size());

                // 기업 간 딜레이
                Thread.sleep(3000);
            } catch (Exception e) {
                log.error("전체 페이지 크롤링 실패 - 기업: {}", corporation.getName(), e);
                results.add(CrawlResult.failure(corporation, "크롤링 실행 실패: " + e.getMessage()));
            }
        }

        log.info("Admin 전체 기업 전체 페이지 크롤링 완료 - 총 기업: {}개", corporations.size());

        return results;
    }

    /**
     * 스케줄된 전체 페이지 크롤링 (ID 내림차순, lastFullCrawledAt이 null인 경우에만)
     * 최대 실행 시간: 25분 (30분 시작 -> 55분 종료)
     * @return 크롤링 결과 목록
     */
    public List<CrawlResult> scheduledFullPageCrawling() {
        log.info("스케줄된 전체 페이지 크롤링 시작");

        long startTime = System.currentTimeMillis();
        long maxExecutionTimeMs = 25 * 60 * 1000; // 25분

        // ID 내림차순으로 기업 조회
        List<Corporation> corporations = crawlerCorporationRepository.findAllWithBlogLinkOrderByIdDesc();
        List<CrawlResult> results = new ArrayList<>();

        // lastFullCrawledAt이 null인 기업만 필터링
        List<Corporation> targetCorporations = corporations.stream()
                .filter(corp -> corp.getLastFullCrawledAt() == null)
                .toList();

        log.info("크롤링 대상 기업: {}개 (전체: {}개)", targetCorporations.size(), corporations.size());

        for (Corporation corporation : targetCorporations) {
            // 시간 체크: 25분 경과 시 중단
            long elapsedTime = System.currentTimeMillis() - startTime;
            if (elapsedTime >= maxExecutionTimeMs) {
                log.warn("작업 시간 제한 도달 (25분). 크롤링을 중단합니다. 처리: {}/{}",
                        results.size(), targetCorporations.size());
                break;
            }

            WebDriver driver = null;
            try {
                log.info("전체 페이지 크롤링 시작 - 기업: {} (ID: {})", corporation.getName(), corporation.getId());

                driver = webDriverConfig.createWebDriver();
                BlogCrawler crawler = selectCrawler(corporation);

                // crawlAllPages 호출
                List<Article> articles = crawler.crawlAllPages(driver, corporation);
                List<Article> newArticles = new ArrayList<>();

                // 중복 체크 및 저장 (link 또는 title이 같으면 중복) - 배치 조회로 N+1 방지
                Set<String> existingLinks = loadExistingLinks(articles);
                Set<String> existingTitles = loadExistingTitles(articles, corporation.getId());

                for (Article article : articles) {
                    if (!existingLinks.contains(article.getLink()) && !existingTitles.contains(article.getTitle())) {
                        articlePersistenceService.saveArticleWithAnalysisNoCache(article, corporation, crawler);
                        extractContentOnly(article, driver);
                        newArticles.add(article);
                    }
                }

                // 성공 시 상태 업데이트
                corporation.setLastFullCrawledAt(LocalDateTime.now());
                corporation.setLastFullCrawlStatus("SUCCESS");
                crawlerCorporationRepository.save(corporation);

                CrawlResult result = CrawlResult.success(corporation, newArticles, newArticles.size());
                results.add(result);

                log.info("전체 페이지 크롤링 완료 - 기업: {}, 수집: {}개, 신규: {}개",
                        corporation.getName(), articles.size(), newArticles.size());

                // 기업 간 딜레이
                Thread.sleep(3000);

            } catch (Exception e) {
                log.error("전체 페이지 크롤링 실패 - 기업: {}, 오류: {}",
                        corporation.getName(), e.getMessage(), e);

                // 실패 시 상태 업데이트
                corporation.setLastFullCrawledAt(LocalDateTime.now());
                corporation.setLastFullCrawlStatus("FAILURE");
                crawlerCorporationRepository.save(corporation);

                results.add(CrawlResult.failure(corporation, "크롤링 실행 실패: " + e.getMessage()));
            } finally {
                if (driver != null) {
                    webDriverConfig.forceCloseWebDriver(driver);
                }
            }
        }

        long totalElapsedMinutes = (System.currentTimeMillis() - startTime) / 60000;
        log.info("스케줄된 전체 페이지 크롤링 완료 - 처리된 기업: {}개, 소요 시간: {}분",
                results.size(), totalElapsedMinutes);

        return results;
    }
}
