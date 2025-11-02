package com.newcodes7.small_town.crawler.service;

import com.newcodes7.small_town.crawler.dto.YouTubeVideo;
import com.newcodes7.small_town.crawler.exception.CrawlerException;
import com.newcodes7.small_town.crawler.exception.NetworkAccessException;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.WebDriver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * YouTube 채널의 영상을 크롤링하는 Crawler
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class YouTubeCrawler implements BlogCrawler {

    private final YouTubeService youtubeService;

    @Override
    public boolean canHandle(String blogUrl) {
        // Corporation의 youtubeChannelId가 있으면 처리 가능
        // blogUrl이 youtube.com을 포함하면 처리
        return blogUrl != null && blogUrl.contains("youtube.com");
    }

    @Override
    public List<Article> crawl(WebDriver driver, Corporation corporation) throws CrawlerException {
        String channelId = corporation.getYoutubeChannelId();

        if (channelId == null || channelId.trim().isEmpty()) {
            log.warn("YouTube Channel ID가 설정되지 않음: {}", corporation.getName());
            return Collections.emptyList();
        }

        try {
            log.info("YouTube 크롤링 시작 - 기업: {}, Channel ID: {}",
                     corporation.getName(), channelId);

            // YouTube API를 통해 최신 영상 조회
            List<YouTubeVideo> videos = youtubeService.getLatestVideos(channelId);

            if (videos.isEmpty()) {
                log.info("조회된 YouTube 영상이 없습니다 - 기업: {}", corporation.getName());
                return Collections.emptyList();
            }

            // YouTubeVideo를 Article로 변환
            List<Article> articles = videos.stream()
                    .map(video -> convertToArticle(video, corporation))
                    .collect(Collectors.toList());

            log.info("YouTube 크롤링 완료 - 기업: {}, 조회된 영상: {}개",
                     corporation.getName(), articles.size());

            return articles;

        } catch (Exception e) {
            log.error("YouTube 크롤링 실패 - 기업: {}, Channel ID: {}",
                      corporation.getName(), channelId, e);
            String youtubeUrl = corporation.getYoutubeUrl() != null ?
                corporation.getYoutubeUrl() : "https://www.youtube.com/channel/" + channelId;
            throw new NetworkAccessException(youtubeUrl, e);
        }
    }

    @Override
    public String getProviderName() {
        return "YouTube";
    }

    @Override
    public void processImageUpload(Article article, Corporation corporation) {
        // YouTube 썸네일은 이미 YouTube CDN URL이므로 별도 업로드 불필요
        log.debug("YouTube 썸네일은 업로드 건너뜀 - Article: {}", article.getTitle());
    }

    /**
     * YouTubeVideo를 Article로 변환
     *
     * @param video YouTube 영상 정보
     * @param corporation 기업 정보
     * @return Article 엔티티
     */
    private Article convertToArticle(YouTubeVideo video, Corporation corporation) {
        Article article = Article.builder()
                .title(video.getTitle())
                .summary(video.getDescription())
                .link("https://www.youtube.com/watch?v=" + video.getVideoId())
                .youtubeVideoId(video.getVideoId())
                .contentType("YOUTUBE")
                .thumbnailImage(video.getThumbnailUrl())
                .publishedAt(video.getPublishedAt())
                .corporation(corporation)
                .build();

        log.debug("YouTube 영상을 Article로 변환 - 제목: {}, Video ID: {}",
                  video.getTitle(), video.getVideoId());

        return article;
    }

    @Override
    public List<Article> crawlWithRobotsCheck(WebDriver driver, Corporation corporation,
                                               RobotsTxtService robotsTxtService) throws CrawlerException {
        // YouTube는 공식 API를 사용하므로 robots.txt 체크 불필요
        log.debug("YouTube는 공식 API를 사용하므로 robots.txt 체크 생략");
        return crawl(driver, corporation);
    }
}
