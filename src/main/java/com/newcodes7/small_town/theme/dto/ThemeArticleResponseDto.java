package com.newcodes7.small_town.theme.dto;

import com.newcodes7.small_town.theme.entity.ThemeArticle;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 테마-아티클 연결 응답 DTO
 */
@Getter
public class ThemeArticleResponseDto {

    private final Long id;
    private final Long themeId;
    private final String themeName;
    private final Long articleId;
    private final String articleTitle;
    private final Integer displayOrder;
    private final LocalDateTime createdAt;

    public ThemeArticleResponseDto(ThemeArticle themeArticle) {
        this.id = themeArticle.getId();
        this.themeId = themeArticle.getTheme().getId();
        this.themeName = themeArticle.getTheme().getName();
        this.articleId = themeArticle.getArticle().getId();
        this.articleTitle = themeArticle.getArticle().getTitle();
        this.displayOrder = themeArticle.getDisplayOrder();
        this.createdAt = themeArticle.getCreatedAt();
    }
}
