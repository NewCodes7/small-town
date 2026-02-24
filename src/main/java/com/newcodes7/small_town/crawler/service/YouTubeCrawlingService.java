package com.newcodes7.small_town.crawler.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.openqa.selenium.WebDriver;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import com.newcodes7.small_town.crawler.crawler.VideoCrawler;
import com.newcodes7.small_town.crawler.dto.VideoCrawlResult;
import com.newcodes7.small_town.crawler.exception.CorporationCrawlingException;
import com.newcodes7.small_town.crawler.exception.CrawlerException;
import com.newcodes7.small_town.crawler.exception.CrawlerNotFoundException;
import com.newcodes7.small_town.crawler.persistence.VideoPersistenceService;
import com.newcodes7.small_town.crawler.repository.CrawlerCorporationRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerVideoRepository;
import com.newcodes7.small_town.global.cache.NginxCachePurgeService;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.entity.Video;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class YouTubeCrawlingService {

    private final CrawlerCorporationRepository crawlerCorporationRepository;
    private final CrawlerVideoRepository crawlerVideoRepository;
    private final ApplicationContext applicationContext;
    private final VideoPersistenceService videoPersistenceService;
    private final NginxCachePurgeService nginxCachePurgeService;

    /**
     * 모든 기업 YouTube 크롤링 (배치)
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

        purgeCacheForVideoCrawlResults(results);

        return results;
    }

    /**
     * 특정 기업 YouTube 크롤링
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
                videoPersistenceService.saveVideoWithTranslation(video, corporation, youtubeCrawler);
                newVideos.add(video);
            }
        }

        log.info("YouTube 크롤링 완료 - 기업: {}, 조회: {}개, 신규: {}개",
                 corporation.getName(), youtubeVideos.size(), newVideos.size());

        return VideoCrawlResult.success(corporation, newVideos, newVideos.size());
    }

    /**
     * URL에 따라 적절한 비디오 크롤러 선택
     */
    private VideoCrawler selectVideoCrawler(String url) {
        List<VideoCrawler> crawlers = applicationContext.getBeansOfType(VideoCrawler.class)
                .values()
                .stream()
                .toList();

        for (VideoCrawler crawler : crawlers) {
            if (crawler.canHandle(url)) {
                return crawler;
            }
        }

        throw new CrawlerNotFoundException(url);
    }

    /**
     * 비디오 크롤링 결과에 따라 선택적으로 캐시 purge
     */
    private void purgeCacheForVideoCrawlResults(List<VideoCrawlResult> results) {
        try {
            List<Long> corporationIdsWithNewVideos = results.stream()
                    .filter(VideoCrawlResult::hasNewVideos)
                    .map(result -> result.getCorporation().getId())
                    .toList();

            if (corporationIdsWithNewVideos.isEmpty()) {
                log.info("신규 영상이 없어 캐시 purge를 건너뜁니다.");
                return;
            }

            log.info("신규 영상이 추가된 기업 {}개에 대해 캐시 purge 시작", corporationIdsWithNewVideos.size());

            nginxCachePurgeService.purgeCorporationPages(corporationIdsWithNewVideos);
            nginxCachePurgeService.purgeHomePages();

            log.info("비디오 크롤링 후 캐시 purge 완료");
        } catch (Exception e) {
            log.error("비디오 크롤링 후 캐시 purge 중 오류 발생: {}", e.getMessage(), e);
        }
    }
}
