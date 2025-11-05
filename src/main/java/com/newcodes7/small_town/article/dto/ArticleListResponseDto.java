package com.newcodes7.small_town.article.dto;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Tag;

import lombok.Getter;

@Getter
public class ArticleListResponseDto implements ArticleResponseDto {

    private final Long id;
    private final String title;
    private final String translatedTitle;
    private final String summary;
    private final String link;
    private final Integer viewCount;
    private final Integer likeCount;
    private final String thumbnailImage;
    private final Integer readingTime;
    private final String publishedAt;
    private final CorporationDto corporation;
    private final List<Tag> tags;

    public ArticleListResponseDto(Article article) {
        this.id = article.getId();
        this.title = article.getTitle();
        this.translatedTitle = article.getTranslatedTitle();
        this.summary = article.getSummary();
        this.link = article.getLink();
        this.viewCount = article.getViewCount();
        this.likeCount = article.getLikeCount();
        this.thumbnailImage = article.getThumbnailImage();
        this.readingTime = article.getReadingTime();
        this.publishedAt = article.getPublishedAt() != null ?
            article.getPublishedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : null;
        this.corporation = new CorporationDto(article.getCorporation());
        this.tags = article.getArticleTags().stream()
            .map(articleTag -> articleTag.getTag())
            .collect(Collectors.toList());
    }
}