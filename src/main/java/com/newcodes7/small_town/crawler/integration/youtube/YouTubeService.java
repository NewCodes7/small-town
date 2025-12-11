package com.newcodes7.small_town.crawler.integration.youtube;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Channel;
import com.google.api.services.youtube.model.ChannelListResponse;
import com.google.api.services.youtube.model.SearchListResponse;
import com.google.api.services.youtube.model.SearchResult;
import com.newcodes7.small_town.crawler.dto.YouTubeVideo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringEscapeUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube Data API를 사용하여 채널 정보 및 영상 목록을 조회하는 서비스
 */
@Service
@Slf4j
public class YouTubeService {

    @Value("${youtube.api.key}")
    private String apiKey;

    @Value("${youtube.api.max-results:50}")
    private int maxResults;

    @Value("${youtube.api.application-name:small-town}")
    private String applicationName;

    private YouTube youtube;

    /**
     * YouTube API 클라이언트 초기화
     */
    private YouTube getYouTubeService() throws GeneralSecurityException, IOException {
        if (youtube == null) {
            youtube = new YouTube.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    JacksonFactory.getDefaultInstance(),
                    null
            ).setApplicationName(applicationName).build();
        }
        return youtube;
    }

    /**
     * YouTube 채널 URL에서 Channel ID 추출
     *
     * @param youtubeUrl YouTube 채널 URL
     * @return Channel ID
     */
    public String getChannelIdFromUrl(String youtubeUrl) {
        if (youtubeUrl == null || youtubeUrl.trim().isEmpty()) {
            return null;
        }

        try {
            // 1. URL에서 직접 Channel ID 추출 (channel/UC... 형태)
            Pattern channelIdPattern = Pattern.compile("youtube\\.com/channel/([a-zA-Z0-9_-]+)");
            Matcher channelIdMatcher = channelIdPattern.matcher(youtubeUrl);
            if (channelIdMatcher.find()) {
                String channelId = channelIdMatcher.group(1);
                log.info("Channel ID extracted from URL: {}", channelId);
                return channelId;
            }

            // 2. @username 또는 /c/username 형태에서 username 추출 후 API 조회
            Pattern usernamePattern = Pattern.compile("youtube\\.com/@([a-zA-Z0-9_-]+)");
            Matcher usernameMatcher = usernamePattern.matcher(youtubeUrl);
            if (usernameMatcher.find()) {
                String username = usernameMatcher.group(1);
                log.info("Username extracted from URL: @{}", username);
                return getChannelIdByUsername(username);
            }

            Pattern customUrlPattern = Pattern.compile("youtube\\.com/c/([a-zA-Z0-9_-]+)");
            Matcher customUrlMatcher = customUrlPattern.matcher(youtubeUrl);
            if (customUrlMatcher.find()) {
                String customName = customUrlMatcher.group(1);
                log.info("Custom URL extracted: /c/{}", customName);
                return getChannelIdByUsername(customName);
            }

            // 3. /user/username 형태
            Pattern userPattern = Pattern.compile("youtube\\.com/user/([a-zA-Z0-9_-]+)");
            Matcher userMatcher = userPattern.matcher(youtubeUrl);
            if (userMatcher.find()) {
                String username = userMatcher.group(1);
                log.info("User extracted: /user/{}", username);
                return getChannelIdByForUsername(username);
            }

            log.warn("YouTube URL 형식을 인식할 수 없습니다: {}", youtubeUrl);
            return null;

        } catch (Exception e) {
            log.error("YouTube Channel ID 추출 실패: {}", youtubeUrl, e);
            return null;
        }
    }

    /**
     * Username으로 Channel ID 조회 (forHandle 파라미터 사용)
     */
    private String getChannelIdByUsername(String username) throws GeneralSecurityException, IOException {
        YouTube youtubeService = getYouTubeService();

        // forHandle 파라미터로 @username 검색
        YouTube.Channels.List request = youtubeService.channels()
                .list(java.util.Arrays.asList("id"))
                .setForHandle(username)
                .setKey(apiKey);

        ChannelListResponse response = request.execute();

        if (response.getItems() != null && !response.getItems().isEmpty()) {
            Channel channel = response.getItems().get(0);
            log.info("Channel ID found by handle @{}: {}", username, channel.getId());
            return channel.getId();
        }

        log.warn("Channel not found for handle: @{}", username);
        return null;
    }

    /**
     * Legacy username으로 Channel ID 조회 (forUsername 파라미터 사용)
     */
    private String getChannelIdByForUsername(String username) throws GeneralSecurityException, IOException {
        YouTube youtubeService = getYouTubeService();

        YouTube.Channels.List request = youtubeService.channels()
                .list(java.util.Arrays.asList("id"))
                .setForUsername(username)
                .setKey(apiKey);

        ChannelListResponse response = request.execute();

        if (response.getItems() != null && !response.getItems().isEmpty()) {
            Channel channel = response.getItems().get(0);
            log.info("Channel ID found by username {}: {}", username, channel.getId());
            return channel.getId();
        }

        log.warn("Channel not found for username: {}", username);
        return null;
    }

    /**
     * 채널의 최신 비디오 목록 조회
     *
     * @param channelId YouTube Channel ID
     * @param maxResults 최대 조회 개수
     * @return YouTube 비디오 목록
     */
    public List<YouTubeVideo> getLatestVideos(String channelId, int maxResults) {
        if (channelId == null || channelId.trim().isEmpty()) {
            log.warn("Channel ID가 null이거나 비어있습니다.");
            return new ArrayList<>();
        }

        try {
            YouTube youtubeService = getYouTubeService();

            YouTube.Search.List search = youtubeService.search()
                    .list(java.util.Arrays.asList("snippet"))
                    .setChannelId(channelId)
                    .setOrder("date")
                    .setMaxResults((long) maxResults)
                    .setType(java.util.Arrays.asList("video"))
                    .setKey(apiKey);

            SearchListResponse response = search.execute();

            List<YouTubeVideo> videos = new ArrayList<>();

            if (response.getItems() != null) {
                for (SearchResult item : response.getItems()) {
                    YouTubeVideo video = YouTubeVideo.builder()
                            .videoId(item.getId().getVideoId())
                            .title(decodeHtmlEntities(item.getSnippet().getTitle()))
                            .description(decodeHtmlEntities(item.getSnippet().getDescription()))
                            .publishedAt(convertToLocalDateTime(item.getSnippet().getPublishedAt()))
                            .thumbnailUrl(getThumbnailUrl(item))
                            .channelTitle(decodeHtmlEntities(item.getSnippet().getChannelTitle()))
                            .channelId(item.getSnippet().getChannelId())
                            .build();
                    videos.add(video);
                }
                log.info("채널 {}에서 {}개의 영상을 조회했습니다.", channelId, videos.size());
            }

            return videos;

        } catch (Exception e) {
            log.error("YouTube 영상 조회 실패 - Channel ID: {}", channelId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 채널의 최신 비디오 목록 조회 (기본값 50개)
     */
    public List<YouTubeVideo> getLatestVideos(String channelId) {
        return getLatestVideos(channelId, maxResults);
    }

    /**
     * 특정 날짜 이후의 비디오 필터링
     *
     * @param channelId YouTube Channel ID
     * @param since 조회 시작 시각
     * @return YouTube 비디오 목록
     */
    public List<YouTubeVideo> getVideosSince(String channelId, LocalDateTime since) {
        List<YouTubeVideo> allVideos = getLatestVideos(channelId);

        List<YouTubeVideo> filteredVideos = new ArrayList<>();
        for (YouTubeVideo video : allVideos) {
            if (video.getPublishedAt() != null && video.getPublishedAt().isAfter(since)) {
                filteredVideos.add(video);
            }
        }

        log.info("채널 {}에서 {} 이후 {}개의 영상을 필터링했습니다.",
                 channelId, since, filteredVideos.size());
        return filteredVideos;
    }

    /**
     * YouTube DateTime을 LocalDateTime으로 변환
     */
    private LocalDateTime convertToLocalDateTime(com.google.api.client.util.DateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        Date date = new Date(dateTime.getValue());
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }

    /**
     * 썸네일 URL 추출 (high quality 우선)
     */
    private String getThumbnailUrl(SearchResult item) {
        if (item.getSnippet().getThumbnails() == null) {
            return null;
        }

        // high quality 우선
        if (item.getSnippet().getThumbnails().getHigh() != null) {
            return item.getSnippet().getThumbnails().getHigh().getUrl();
        }

        // medium quality 대체
        if (item.getSnippet().getThumbnails().getMedium() != null) {
            return item.getSnippet().getThumbnails().getMedium().getUrl();
        }

        // default quality 최후
        if (item.getSnippet().getThumbnails().getDefault() != null) {
            return item.getSnippet().getThumbnails().getDefault().getUrl();
        }

        return null;
    }

    /**
     * HTML 엔티티를 실제 문자로 디코딩
     *
     * @param text HTML 엔티티가 포함된 텍스트
     * @return 디코딩된 텍스트
     */
    private String decodeHtmlEntities(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return StringEscapeUtils.unescapeHtml4(text);
    }
}
