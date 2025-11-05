package com.newcodes7.small_town.crawler.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.entity.Video;

import lombok.Data;

@Data
public class VideoCrawlResult {
    private Corporation corporation;
    private List<Video> videos;
    private boolean success;
    private String errorMessage;
    private LocalDateTime crawledAt;
    private int totalVideos;
    private int newVideos;

    public static VideoCrawlResult success(Corporation corporation, List<Video> videos, int newVideos) {
        VideoCrawlResult result = new VideoCrawlResult();
        result.setCorporation(corporation);
        result.setVideos(videos);
        result.setSuccess(true);
        result.setCrawledAt(LocalDateTime.now());
        result.setTotalVideos(videos.size());
        result.setNewVideos(newVideos);
        return result;
    }

    public static VideoCrawlResult failure(Corporation corporation, String errorMessage) {
        VideoCrawlResult result = new VideoCrawlResult();
        result.setCorporation(corporation);
        result.setSuccess(false);
        result.setErrorMessage(errorMessage);
        result.setCrawledAt(LocalDateTime.now());
        return result;
    }

    /**
     * 크롤링 결과에 신규 영상이 포함되어 있는지 확인
     * @return 신규 영상이 1개 이상이면 true
     */
    public boolean hasNewVideos() {
        return success && newVideos > 0;
    }
}
