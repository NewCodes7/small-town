package com.newcodes7.small_town.hackernews.dto;

import java.time.format.DateTimeFormatter;

import com.newcodes7.small_town.hackernews.entity.HackerNewsItem;

import lombok.Getter;

@Getter
public class HackerNewsItemResponseDto {

    private final Long id;
    private final Long hnId;
    private final String title;
    private final String translatedTitle;
    private final String url;
    private final String hnUrl;
    private final String author;
    private final Integer score;
    private final Integer commentCount;
    private final String hnCreatedAt;
    private final String detailUrl;

    public HackerNewsItemResponseDto(HackerNewsItem item) {
        this.id = item.getId();
        this.hnId = item.getHnId();
        this.title = item.getTitle();
        this.translatedTitle = item.getTranslatedTitle();
        this.url = sanitizeUrl(item.getUrl());
        this.hnUrl = item.getHnDiscussionUrl();
        this.author = item.getAuthor();
        this.score = item.getScore();
        this.commentCount = item.getCommentCount();
        this.hnCreatedAt = item.getHnCreatedAt() != null ?
            item.getHnCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : null;
        this.detailUrl = "/hackernews/" + item.getId();
    }

    private static String sanitizeUrl(String url) {
        if (url == null) return null;
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return null;
    }
}
