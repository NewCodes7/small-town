package com.newcodes7.small_town.hackernews.dto;

import java.time.format.DateTimeFormatter;

import com.newcodes7.small_town.hackernews.entity.HackerNewsComment;

import lombok.Getter;

@Getter
public class HackerNewsCommentResponseDto {

    private final Long id;
    private final Long hnId;
    private final String author;
    private final String textContent;
    private final String translatedText;
    private final String hnCreatedAt;
    private final Integer depth;

    public HackerNewsCommentResponseDto(HackerNewsComment comment) {
        this.id = comment.getId();
        this.hnId = comment.getHnId();
        this.author = comment.getAuthor();
        this.textContent = comment.getTextContent();
        this.translatedText = comment.getTranslatedText();
        this.hnCreatedAt = comment.getHnCreatedAt() != null ?
            comment.getHnCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : null;
        this.depth = comment.getDepth();
    }
}
