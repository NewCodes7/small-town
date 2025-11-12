package com.newcodes7.small_town.crawler.service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.openqa.selenium.WebDriver;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import com.newcodes7.small_town.crawler.config.WebDriverConfig;
import com.newcodes7.small_town.crawler.dto.CrawlResult;
import com.newcodes7.small_town.crawler.dto.CrawlingStats;
import com.newcodes7.small_town.crawler.dto.VideoCrawlResult;
import com.newcodes7.small_town.crawler.exception.CorporationCrawlingException;
import com.newcodes7.small_town.crawler.exception.CrawlerException;
import com.newcodes7.small_town.crawler.exception.CrawlerNotFoundException;
import com.newcodes7.small_town.crawler.repository.CrawlerArticleRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerCorporationRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerVideoRepository;
import com.newcodes7.small_town.global.cache.NginxCachePurgeService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.entity.Video;

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
    private final NginxCachePurgeService nginxCachePurgeService;
    private final OpenaiService openaiService;

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
     * 모든 기업의 블로그 및 YouTube 모두 크롤링 (동기 처리)
     */
    public List<CrawlResult> crawlAll() {
        List<Corporation> corporations = crawlerCorporationRepository.findAllWithBlogLinkOrYoutubeChannel();
        log.info("전체 크롤링 시작 - 대상 기업: {}개 (블로그 및 YouTube 포함)", corporations.size());

        List<CrawlResult> results = new ArrayList<>();

        // 기업별로 WebDriver를 새로 생성하여 메모리 누적 방지
        for (Corporation corporation : corporations) {
            WebDriver driver = null;
            try {
                driver = webDriverConfig.createWebDriver();
                CrawlResult result = crawlSingleBlog(corporation.getId(), driver);
                results.add(result);

                log.info("기업 크롤링 완료 - {}: {}/{} 진행", corporation.getName(),
                    results.size(), corporations.size());

            } catch (Exception e) {
                log.error("기업 ID {} 크롤링 중 오류 발생: {}", corporation.getId(), e.getMessage(), e);
                results.add(CrawlResult.failure(corporation, "크롤링 실행 실패: " + e.getMessage()));
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
        }

        log.info("전체 크롤링 완료 - 처리된 기업: {}개", results.size());

        // 크롤링 완료 후 선택적 캐시 purge
        purgeCacheForCrawlResults(results);

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

        BlogCrawler blogCrawler = selectCrawler(corporation.getBlogLink());
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

        // 중복 제거 및 저장
        for (Article article : blogArticles) {
            if (!crawlerArticleRepository.findFirstByLinkAndDeletedAtIsNull(article.getLink()).isPresent()) {
                articlePersistenceService.saveArticleWithAnalysis(article, corporation, blogCrawler);
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
                // 이미지 업로드 처리
                youtubeCrawler.processImageUpload(video, corporation);

                // 해외 기업의 영어 제목 자동 번역
                translateVideoTitleIfNeeded(video, corporation);

                // Video 저장
                crawlerVideoRepository.save(video);
                newVideos.add(video);
            }
        }

        log.info("YouTube 크롤링 완료 - 기업: {}, 조회: {}개, 신규: {}개",
                 corporation.getName(), youtubeVideos.size(), newVideos.size());

        return VideoCrawlResult.success(corporation, newVideos, newVideos.size());
    }

    /**
     * 필요한 경우 비디오 제목을 번역합니다.
     * 해외 기업의 영어 제목만 한국어로 번역합니다.
     */
    private void translateVideoTitleIfNeeded(Video video, Corporation corporation) {
        try {
            // 해외 기업인지 확인 (isDomestic = false)
            if (!corporation.getIsDomestic()) {
                String title = video.getTitle();

                // 제목에 한국어가 포함되어 있지 않으면 번역
                if (title != null && !openaiService.containsKorean(title)) {
                    log.debug("영어 제목 번역 시도 - 기업: {}, 제목: {}", corporation.getName(), title);

                    String translatedTitle = openaiService.translateTitle(title, corporation.getName());

                    if (translatedTitle != null && !translatedTitle.trim().isEmpty()) {
                        video.setTranslatedTitle(translatedTitle);

                        log.info("비디오 제목 번역 완료 - 기업: {}, 원본: '{}' → 번역: '{}'",
                            corporation.getName(), title, translatedTitle);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("비디오 제목 번역 중 오류 발생 - 기업: {}, 제목: {}, 오류: {}",
                corporation.getName(), video.getTitle(), e.getMessage());
            // 번역 실패는 크롤링을 중단시키지 않음
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
}