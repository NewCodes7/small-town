package com.newcodes7.small_town.video.dto;

import java.time.format.DateTimeFormatter;

import com.newcodes7.small_town.article.dto.CorporationDto;
import com.newcodes7.small_town.global.entity.Video;

import lombok.Getter;

@Getter
public class VideoListResponseDto implements VideoResponseDto {

    private final Long id;
    private final String title;
    private final String translatedTitle;
    private final String description;
    private final String videoId;
    private final String link;
    private final Integer viewCount;
    private final Integer likeCount;
    private final String thumbnailUrl;
    private final String publishedAt;
    private final CorporationDto corporation;

    public VideoListResponseDto(Video video) {
        this.id = video.getId();
        this.title = video.getTitle();
        this.translatedTitle = video.getTranslatedTitle();
        this.description = video.getDescription();
        this.videoId = video.getVideoId();
        this.link = video.getLink();
        this.viewCount = video.getViewCount();
        this.likeCount = video.getLikeCount();
        this.thumbnailUrl = video.getThumbnailUrl();
        this.publishedAt = video.getPublishedAt() != null ?
            video.getPublishedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : null;
        this.corporation = new CorporationDto(video.getCorporation());
    }
}
