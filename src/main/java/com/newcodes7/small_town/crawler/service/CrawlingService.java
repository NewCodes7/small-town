package com.newcodes7.small_town.crawler.service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.openqa.selenium.WebDriver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.ArticleTermRepository;
import com.newcodes7.small_town.article.repository.TermRepository;
import com.newcodes7.small_town.crawler.config.WebDriverConfig;
import com.newcodes7.small_town.crawler.crawler.BlogCrawler;
import com.newcodes7.small_town.crawler.crawler.DefaultBlogCrawler;
import com.newcodes7.small_town.crawler.crawler.MediumBlogCrawler;
import com.newcodes7.small_town.crawler.crawler.VideoCrawler;
import com.newcodes7.small_town.crawler.dto.CrawlResult;
import com.newcodes7.small_town.crawler.dto.CrawlingStats;
import com.newcodes7.small_town.crawler.dto.VideoCrawlResult;
import com.newcodes7.small_town.crawler.exception.CorporationCrawlingException;
import com.newcodes7.small_town.crawler.exception.CrawlerException;
import com.newcodes7.small_town.crawler.exception.CrawlerNotFoundException;
import com.newcodes7.small_town.crawler.integration.robotstxt.RobotsTxtService;
import com.newcodes7.small_town.crawler.persistence.ArticlePersistenceService;
import com.newcodes7.small_town.crawler.persistence.VideoPersistenceService;
import com.newcodes7.small_town.crawler.repository.CrawlerArticleRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerCorporationRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerVideoRepository;
import com.newcodes7.small_town.global.cache.NginxCachePurgeService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.ArticleTerm;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.entity.Term;
import com.newcodes7.small_town.global.entity.Video;
import com.newcodes7.small_town.global.service.MorphemeAnalyzer;
import com.newcodes7.small_town.crawler.service.ArticleContentExtractionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CrawlingService {

    private final CrawlerCorporationRepository crawlerCorporationRepository;
    private final CrawlerArticleRepository crawlerArticleRepository;
    private final CrawlerVideoRepository crawlerVideoRepository;
    private final ApplicationContext applicationContext;
    private final RobotsTxtService robotsTxtService;
    private final WebDriverConfig webDriverConfig;
    private final ArticlePersistenceService articlePersistenceService;
    private final VideoPersistenceService videoPersistenceService;
    private final NginxCachePurgeService nginxCachePurgeService;
    private final MorphemeAnalyzer morphemeAnalyzer;
    private final TermRepository termRepository;
    private final ArticleTermRepository articleTermRepository;
    private final ArticleContentExtractionService articleContentExtractionService;
    private final ArticleRepository articleRepository;

    // Term 추출 설정
    @Value("${term.extraction.max-terms:7}")
    private int maxTermsPerArticle;

    @Value("${term.extraction.min-frequency:2}")
    private int minTermFrequency;

    /**
     * 모든 기업의 블로그만 크롤링 (동기 처리)
     */
    public List<CrawlResult> crawlAllBlogs() {
        List<Corporation> corporations = crawlerCorporationRepository.findAllWithBlogLink();
        log.info("블로그 크롤링 시작 - 대상 기업: {}개", corporations.size());

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

        log.info("블로그 크롤링 완료 - 처리된 기업: {}개", results.size());

        // 크롤링 완료 후 선택적 캐시 purge
        purgeCacheForCrawlResults(results);

        // BM25 검색 인덱스 갱신 (ArticleTerm이 업데이트되었으므로)
        refreshBM25SearchIndex(results);

        return results;
    }

    /**
     * 모든 기업의 YouTube만 크롤링 (동기 처리)
     * YouTube는 API를 사용하므로 WebDriver 불필요
     */
    public List<VideoCrawlResult> crawlAllYouTube() {
        List<Corporation> corporations = crawlerCorporationRepository.findAllWithYoutubeChannel();
        log.info("YouTube 크롤링 시작 - 대상 기업: {}개", corporations.size());

        List<VideoCrawlResult> results = new ArrayList<>();

        for (Corporation corporation : corporations) {
            try {
                VideoCrawlResult result = crawlSingleYouTube(corporation.getId(), null);
                results.add(result);

                log.info("YouTube 크롤링 완료 - {}: {}/{} 진행", corporation.getName(),
                    results.size(), corporations.size());

            } catch (Exception e) {
                log.error("기업 ID {} YouTube 크롤링 중 오류 발생: {}", corporation.getId(), e.getMessage(), e);
                results.add(VideoCrawlResult.failure(corporation, "YouTube 크롤링 실행 실패: " + e.getMessage()));
            }
        }

        log.info("YouTube 크롤링 완료 - 처리된 기업: {}개", results.size());

        // 크롤링 완료 후 선택적 캐시 purge
        purgeCacheForVideoCrawlResults(results);

        return results;
    }

    /**
     * 크롤링 결과에 따라 선택적으로 캐시 purge
     * - 신규 글이 추가된 corporation만 개별 purge
     * - 전체적으로 신규 글이 1개 이상이면 home 페이지 purge
     */
    private void purgeCacheForCrawlResults(List<CrawlResult> results) {
        try {
            // 신규 글이 추가된 corporation 찾기
            List<Long> corporationIdsWithNewArticles = results.stream()
                    .filter(CrawlResult::hasNewArticles)
                    .map(result -> result.getCorporation().getId())
                    .toList();

            if (corporationIdsWithNewArticles.isEmpty()) {
                log.info("신규 글이 없어 캐시 purge를 건너뜁니다.");
                return;
            }

            log.info("신규 글이 추가된 기업 {}개에 대해 캐시 purge 시작", corporationIdsWithNewArticles.size());

            // 신규 글이 있는 corporation 페이지만 purge
            nginxCachePurgeService.purgeCorporationPages(corporationIdsWithNewArticles);

            // 전체적으로 신규 글이 있으면 home 페이지도 purge
            nginxCachePurgeService.purgeHomePages();

            log.info("크롤링 후 캐시 purge 완료");
        } catch (Exception e) {
            log.error("크롤링 후 캐시 purge 중 오류 발생: {}", e.getMessage(), e);
            // 캐시 purge 실패는 크롤링 자체를 실패시키지 않음
        }
    }

    /**
     * 비디오 크롤링 결과에 따라 선택적으로 캐시 purge
     * - 신규 영상이 추가된 corporation만 개별 purge
     * - 전체적으로 신규 영상이 1개 이상이면 home 페이지 purge
     */
    private void purgeCacheForVideoCrawlResults(List<VideoCrawlResult> results) {
        try {
            // 신규 영상이 추가된 corporation 찾기
            List<Long> corporationIdsWithNewVideos = results.stream()
                    .filter(VideoCrawlResult::hasNewVideos)
                    .map(result -> result.getCorporation().getId())
                    .toList();

            if (corporationIdsWithNewVideos.isEmpty()) {
                log.info("신규 영상이 없어 캐시 purge를 건너뜁니다.");
                return;
            }

            log.info("신규 영상이 추가된 기업 {}개에 대해 캐시 purge 시작", corporationIdsWithNewVideos.size());

            // 신규 영상이 있는 corporation 페이지만 purge
            nginxCachePurgeService.purgeCorporationPages(corporationIdsWithNewVideos);

            // 전체적으로 신규 영상이 있으면 home 페이지도 purge
            nginxCachePurgeService.purgeHomePages();

            log.info("비디오 크롤링 후 캐시 purge 완료");
        } catch (Exception e) {
            log.error("비디오 크롤링 후 캐시 purge 중 오류 발생: {}", e.getMessage(), e);
            // 캐시 purge 실패는 크롤링 자체를 실패시키지 않음
        }
    }

    /**
     * BM25 검색 인덱스 갱신 (Materialized View REFRESH)
     * 신규 Article이 추가되었거나 ArticleTerm이 업데이트된 경우 호출
     */
    private void refreshBM25SearchIndex(List<CrawlResult> results) {
        try {
            // 신규 글이 추가된 경우에만 REFRESH
            boolean hasNewArticles = results.stream()
                    .anyMatch(CrawlResult::hasNewArticles);

            if (!hasNewArticles) {
                log.info("신규 글이 없어 BM25 인덱스 갱신을 건너뜁니다.");
                return;
            }

            log.info("BM25 검색 인덱스 갱신 시작 (Materialized View REFRESH)");
            long startTime = System.currentTimeMillis();

            articleRepository.refreshArticleSearchIndex();

            long elapsedTime = System.currentTimeMillis() - startTime;
            log.info("BM25 검색 인덱스 갱신 완료 ({}ms)", elapsedTime);

            // Term 자동완성 Materialized View 갱신
            log.info("Term 자동완성 인덱스 갱신 시작");
            long termStartTime = System.currentTimeMillis();

            articleRepository.refreshTermAutocompleteIndex();

            long termElapsedTime = System.currentTimeMillis() - termStartTime;
            log.info("Term 자동완성 인덱스 갱신 완료 ({}ms)", termElapsedTime);

        } catch (Exception e) {
            log.error("BM25 검색 인덱스 갱신 중 오류 발생: {}", e.getMessage(), e);
            // 인덱스 갱신 실패는 크롤링 자체를 실패시키지 않음
        }
    }

    /**
     * 특정 기업 블로그만 크롤링 (WebDriver 관리 + 예외 처리)
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
            return crawlAndSaveBlogArticles(corporation, driver);
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
     * 특정 기업 YouTube만 크롤링
     * YouTube는 API를 사용하므로 WebDriver 불필요
     */
    public VideoCrawlResult crawlSingleYouTube(Long corporationId, WebDriver driver) {
        Corporation corporation = crawlerCorporationRepository.findByIdAndNotDeleted(corporationId);
        if (corporation == null) {
            throw new CorporationCrawlingException(corporationId);
        }

        boolean hasYoutubeChannel = corporation.getYoutubeChannelId() != null && !corporation.getYoutubeChannelId().trim().isEmpty();
        if (!hasYoutubeChannel) {
            throw new CorporationCrawlingException(corporationId, "empty or null YouTube channel ID");
        }

        try {
            return crawlAndSaveYouTubeVideos(corporation, driver);
        } catch (CrawlerException e) {
            log.error("YouTube 크롤링 실패 - 기업: {}, 오류: {}", corporation.getName(), e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("예상치 못한 YouTube 크롤링 오류 - 기업: {}, 오류: {}", corporation.getName(), e.getMessage(), e);
            throw new CrawlerException("CRAWLER_UNEXPECTED_ERROR", "Unexpected error during YouTube crawling for corporation: " + corporation.getName(), e) {};
        }
    }

    /**
     * 블로그 크롤링 및 Article 저장
     */
    private CrawlResult crawlAndSaveBlogArticles(Corporation corporation, WebDriver driver) throws IOException {
        List<Article> crawledArticles = new ArrayList<>();
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
        crawledArticles.addAll(blogArticles);

        // 중복 제거 및 저장 (link 또는 title이 같으면 중복)
        for (Article article : blogArticles) {
            boolean isDuplicateByLink = crawlerArticleRepository.findFirstByLinkAndDeletedAtIsNull(article.getLink()).isPresent();
            boolean isDuplicateByTitle = crawlerArticleRepository.existsByTitleAndCorporationIdAndDeletedAtIsNull(article.getTitle(), corporation.getId());

            if (!isDuplicateByLink && !isDuplicateByTitle) {
                // Article 저장 및 AI 분석
                articlePersistenceService.saveArticleWithAnalysis(article, corporation, blogCrawler);

                // 본문에서 Term 추출 및 저장
                extractAndSaveTerms(article, blogCrawler, driver);

                newArticles.add(article);
            }
        }

        log.info("블로그 크롤링 완료 - 기업: {}, 조회: {}개, 신규: {}개",
                 corporation.getName(), blogArticles.size(), newArticles.size());

        return CrawlResult.success(corporation, newArticles, newArticles.size());
    }

    /**
     * YouTube 크롤링 및 Video 저장
     */
    private VideoCrawlResult crawlAndSaveYouTubeVideos(Corporation corporation, WebDriver driver) throws IOException {
        List<Video> newVideos = new ArrayList<>();

        VideoCrawler youtubeCrawler = selectVideoCrawler("https://www.youtube.com/channel/" + corporation.getYoutubeChannelId());
        log.info("YouTube 크롤링 시작 - 기업: {}, 크롤러: {}", corporation.getName(), youtubeCrawler.getProviderName());

        List<Video> youtubeVideos = youtubeCrawler.crawl(driver, corporation);

        // 중복 제거 및 저장
        for (Video video : youtubeVideos) {
            if (!crawlerVideoRepository.findFirstByLinkAndDeletedAtIsNull(video.getLink()).isPresent()) {
                // VideoPersistenceService를 통해 저장 (이미지 업로드, 번역, 캐시 evict 포함)
                videoPersistenceService.saveVideoWithTranslation(video, corporation, youtubeCrawler);
                newVideos.add(video);
            }
        }

        log.info("YouTube 크롤링 완료 - 기업: {}, 조회: {}개, 신규: {}개",
                 corporation.getName(), youtubeVideos.size(), newVideos.size());

        return VideoCrawlResult.success(corporation, newVideos, newVideos.size());
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
     * URL에 따라 적절한 비디오 크롤러 선택
     */
    private VideoCrawler selectVideoCrawler(String url) {
        List<VideoCrawler> crawlers = applicationContext.getBeansOfType(VideoCrawler.class)
                .values()
                .stream()
                .toList();

        // 적절한 크롤러 선택
        for (VideoCrawler crawler : crawlers) {
            if (crawler.canHandle(url)) {
                return crawler;
            }
        }

        throw new CrawlerNotFoundException(url);
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
     * 본문에서 Term을 추출하여 저장
     */
    private void extractAndSaveTerms(Article article, BlogCrawler crawler, WebDriver driver) {
        try {
            // 본문 추출 (기존 driver 재사용)
            String content = articleContentExtractionService.extractContent(article, driver);

            // 본문 추출 결과 확인
            if (content == null || content.trim().isEmpty()) {
                log.warn("Term 추출 건너뜀 - 본문이 비어있음: {}", article.getTitle());
                return;
            }

            log.info("본문 추출 성공 - Article: {}, 본문 길이: {}자", article.getTitle(), content.length());

            // 추출된 본문을 DB에 저장 (독립 트랜잭션)
            try {
                articleContentExtractionService.updateArticleContent(article.getId(), content);
                log.info("Article 본문 DB 저장 완료 - Article: {}", article.getTitle());
            } catch (Exception e) {
                log.error("Article 본문 DB 저장 실패 - Article: {}, 오류: {}",
                        article.getTitle(), e.getMessage(), e);
                // 본문 저장 실패해도 Term 추출은 계속 진행
            }

            // 형태소 분석을 통해 Term 추출
            Map<String, MorphemeAnalyzer.TermInfo> termInfoMap = morphemeAnalyzer.extractTerms(content);
            if (termInfoMap.isEmpty()) {
                log.warn("Term 추출 결과 없음 - Article: {}", article.getTitle());
                return;
            }

            log.info("형태소 분석 완료 - Article: {}, 추출된 Term 수: {}개", article.getTitle(), termInfoMap.size());

            // 빈도수 기반 점수 계산 (최댓값 기준 정규화)
            int maxFrequency = termInfoMap.values().stream()
                    .mapToInt(MorphemeAnalyzer.TermInfo::getFrequency)
                    .max()
                    .orElse(1);

            // 최소 빈도수 이상 & 점수 내림차순 정렬 후 상위 N개 선택
            List<MorphemeAnalyzer.TermInfo> topTerms = termInfoMap.values().stream()
                    .filter(termInfo -> termInfo.getFrequency() >= minTermFrequency)  // 최소 빈도수 필터
                    .sorted(Comparator.comparingInt(MorphemeAnalyzer.TermInfo::getFrequency).reversed())
                    .limit(maxTermsPerArticle)  // 설정 가능한 최대 개수
                    .collect(Collectors.toList());

            log.info("상위 {}개 Term 선택 완료 (최소 빈도: {}) - Article: {}",
                    topTerms.size(), minTermFrequency, article.getTitle());

            // ArticleTerm 생성 및 저장
            List<ArticleTerm> articleTerms = new ArrayList<>();
            for (MorphemeAnalyzer.TermInfo termInfo : topTerms) {
                try {
                    // Term 조회 또는 생성
                    Term term = termRepository.findByTermAndTermType(termInfo.getTerm(), termInfo.getTermType())
                            .orElseGet(() -> {
                                Term newTerm = Term.builder()
                                        .term(termInfo.getTerm())
                                        .termType(termInfo.getTermType())
                                        .build();
                                return termRepository.save(newTerm);
                            });

                    // 정규화된 점수 계산 (0~1 사이)
                    double score = (double) termInfo.getFrequency() / maxFrequency;

                    // ArticleTerm 생성
                    ArticleTerm articleTerm = ArticleTerm.builder()
                            .article(article)
                            .term(term)
                            .frequency(termInfo.getFrequency())
                            .score(score)
                            .build();

                    articleTerms.add(articleTerm);

                    log.debug("ArticleTerm 생성 - Term: {}, Frequency: {}, Score: {:.2f}",
                            termInfo.getTerm(), termInfo.getFrequency(), score);

                } catch (Exception e) {
                    log.error("ArticleTerm 생성 실패 - Term: {}, 오류: {}",
                            termInfo.getTerm(), e.getMessage(), e);
                }
            }

            // 배치 저장
            if (!articleTerms.isEmpty()) {
                articleTermRepository.saveAll(articleTerms);
                log.info("ArticleTerm 저장 완료 - Article: {}, 저장된 Term 수: {}개",
                        article.getTitle(), articleTerms.size());
            }

        } catch (Exception e) {
            log.error("Term 추출 및 저장 중 오류 발생 - Article: {}, 오류: {}",
                    article.getTitle(), e.getMessage(), e);
            // Term 추출 실패는 크롤링을 중단시키지 않음
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

            // 중복 체크 및 저장 (link 또는 title이 같으면 중복, 캐시 작업 없이)
            for (Article article : articles) {
                boolean isDuplicateByLink = crawlerArticleRepository.findFirstByLinkAndDeletedAtIsNull(article.getLink()).isPresent();
                boolean isDuplicateByTitle = crawlerArticleRepository.existsByTitleAndCorporationIdAndDeletedAtIsNull(article.getTitle(), corporation.getId());

                if (!isDuplicateByLink && !isDuplicateByTitle) {
                    // Article 저장 및 AI 분석 (캐시 작업 없음)
                    articlePersistenceService.saveArticleWithAnalysisNoCache(article, corporation, crawler);

                    // 전체 페이지 크롤링 시에는 Term 분석 생략 (성능 최적화)

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

        // 크롤링 완료 후 선택적 캐시 purge
        purgeCacheForCrawlResults(results);

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

                // 중복 체크 및 저장 (link 또는 title이 같으면 중복)
                for (Article article : articles) {
                    boolean isDuplicateByLink = crawlerArticleRepository.findFirstByLinkAndDeletedAtIsNull(article.getLink()).isPresent();
                    boolean isDuplicateByTitle = crawlerArticleRepository.existsByTitleAndCorporationIdAndDeletedAtIsNull(article.getTitle(), corporation.getId());

                    if (!isDuplicateByLink && !isDuplicateByTitle) {
                        articlePersistenceService.saveArticleWithAnalysisNoCache(article, corporation, crawler);
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

        // 크롤링 완료 후 선택적 캐시 purge
        if (!results.isEmpty()) {
            purgeCacheForCrawlResults(results);
            refreshBM25SearchIndex(results);
        }

        return results;
    }
}