package com.newcodes7.small_town.theme.dto;

import com.newcodes7.small_town.theme.entity.Theme;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 테마 생성/수정 시 반환용 DTO (컨텐츠 목록 제외)
 */
@Getter
public class ThemeSimpleResponseDto {

    private final Long id;
    private final String name;
    private final String description;
    private final String emoji;
    private final String thumbnailUrl;
    private final Integer displayOrder;
    private final Boolean isActive;
    private final Integer viewCount;
    private final Integer totalContentCount;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public ThemeSimpleResponseDto(Theme theme) {
        this.id = theme.getId();
        this.name = theme.getName();
        this.description = theme.getDescription();
        this.emoji = theme.getEmoji();
        this.thumbnailUrl = theme.getThumbnailUrl();
        this.displayOrder = theme.getDisplayOrder();
        this.isActive = theme.getIsActive();
        this.viewCount = theme.getViewCount();
        this.totalContentCount = theme.getThemeArticles().size() + theme.getThemeVideos().size();
        this.createdAt = theme.getCreatedAt();
        this.updatedAt = theme.getUpdatedAt();
    }
}
