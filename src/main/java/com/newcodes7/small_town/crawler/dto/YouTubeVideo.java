package com.newcodes7.small_town.crawler.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * YouTube Data API에서 가져온 영상 정보를 담는 DTO
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YouTubeVideo {

    private String videoId;

    private String title;

    private String description;

    private LocalDateTime publishedAt;

    private String thumbnailUrl;

    private String channelTitle;

    private String channelId;
}
