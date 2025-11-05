package com.newcodes7.small_town.crawler.service;

import com.newcodes7.small_town.crawler.dto.YouTubeVideo;
import com.newcodes7.small_town.crawler.exception.CrawlerException;
import com.newcodes7.small_town.crawler.exception.NetworkAccessException;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.entity.Video;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.WebDriver;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * YouTube 채널의 영상을 크롤링하는 Crawler
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class YouTubeCrawler implements VideoCrawler {

    private final YouTubeService youtubeService;
    private final S3ImageService s3ImageService;

    @Override
    public boolean canHandle(String url) {
        // url이 youtube.com을 포함하면 처리
        return url != null && url.contains("youtube.com");
    }

    @Override
    public List<Video> crawl(WebDriver driver, Corporation corporation) throws CrawlerException {
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

            // YouTubeVideo를 Video로 변환
            List<Video> videoEntities = videos.stream()
                    .map(video -> convertToVideo(video, corporation))
                    .collect(Collectors.toList());

            log.info("YouTube 크롤링 완료 - 기업: {}, 조회된 영상: {}개",
                     corporation.getName(), videoEntities.size());

            return videoEntities;

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
    public void processImageUpload(Video video, Corporation corporation) {
        String originalImageUrl = video.getThumbnailUrl();

        if (originalImageUrl != null && !originalImageUrl.isEmpty() && originalImageUrl.startsWith("http")) {
            try {
                String s3ImageUrl = s3ImageService.uploadImageFromUrl(originalImageUrl, corporation.getName());
                video.setThumbnailUrl(s3ImageUrl);
                log.info("YouTube 썸네일 S3 업로드 성공: {} -> {}", originalImageUrl, s3ImageUrl);
            } catch (Exception e) {
                log.warn("YouTube 썸네일 이미지 업로드 실패: {} - {}", originalImageUrl, e.getMessage());
                // S3 업로드 실패 시 원본 URL 그대로 유지
            }
        }
    }

    /**
     * YouTubeVideo를 Video로 변환
     *
     * @param youtubeVideo YouTube 영상 정보
     * @param corporation 기업 정보
     * @return Video 엔티티
     */
    private Video convertToVideo(YouTubeVideo youtubeVideo, Corporation corporation) {
        Video video = Video.builder()
                .title(youtubeVideo.getTitle())
                .description(youtubeVideo.getDescription())
                .link("https://www.youtube.com/watch?v=" + youtubeVideo.getVideoId())
                .videoId(youtubeVideo.getVideoId())
                .thumbnailUrl(youtubeVideo.getThumbnailUrl())
                .publishedAt(youtubeVideo.getPublishedAt())
                .corporation(corporation)
                .build();

        log.debug("YouTube 영상을 Video로 변환 - 제목: {}, Video ID: {}",
                  youtubeVideo.getTitle(), youtubeVideo.getVideoId());

        return video;
    }
}
