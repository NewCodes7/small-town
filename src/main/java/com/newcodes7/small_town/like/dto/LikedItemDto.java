package com.newcodes7.small_town.like.dto;

import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Video;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class LikedItemDto {
    private final String type; // "article" or "video"
    private final Long id;
    private final String title;
    private final String translatedTitle;
    private final String link;
    private final String thumbnailUrl;
    private final LocalDateTime publishedAt;
    private final LocalDateTime likedAt; // 좋아요 누른 시간
    private final int viewCount;
    private final int likeCount;
    private final String corporationName;
    private final String corporationLogoUrl;
    private final String categoryName;

    // Article용 생성자
    public LikedItemDto(Article article, LocalDateTime likedAt) {
        this.type = "article";
        this.id = article.getId();
        this.title = article.getTitle();
        this.translatedTitle = article.getTranslatedTitle();
        this.link = article.getLink();
        this.thumbnailUrl = article.getThumbnailImage(); // Article은 thumbnailImage 사용
        this.publishedAt = article.getPublishedAt();
        this.likedAt = likedAt;
        this.viewCount = article.getViewCount() != null ? article.getViewCount() : 0;
        this.likeCount = article.getLikeCount() != null ? article.getLikeCount() : 0;
        this.corporationName = article.getCorporation().getName();
        this.corporationLogoUrl = article.getCorporation().getEffectiveLogoUrl();
        this.categoryName = article.getCategory() != null ? article.getCategory().getName() : null;
    }

    // Video용 생성자
    public LikedItemDto(Video video, LocalDateTime likedAt) {
        this.type = "video";
        this.id = video.getId();
        this.title = video.getTitle();
        this.translatedTitle = video.getTranslatedTitle();
        this.link = video.getLink();
        this.thumbnailUrl = video.getThumbnailUrl();
        this.publishedAt = video.getPublishedAt();
        this.likedAt = likedAt;
        this.viewCount = video.getViewCount() != null ? video.getViewCount() : 0;
        this.likeCount = video.getLikeCount() != null ? video.getLikeCount() : 0;
        this.corporationName = video.getCorporation().getName();
        this.corporationLogoUrl = video.getCorporation().getEffectiveLogoUrl();
        this.categoryName = video.getCategory() != null ? video.getCategory().getName() : null;
    }
}
