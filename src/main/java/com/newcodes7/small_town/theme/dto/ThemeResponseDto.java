package com.newcodes7.small_town.theme.dto;

import com.newcodes7.small_town.theme.entity.Theme;

import lombok.Getter;

import java.util.List;

@Getter
public class ThemeResponseDto {

    private final Long id;
    private final String name;
    private final String description;
    private final String emoji;
    private final String thumbnailUrl;
    private final Integer displayOrder;
    private final Boolean isActive;
    private final Integer totalContentCount;
    private final Integer articleCount;
    private final Integer videoCount;
    private final List<ThemeContentDto> contents;

    public ThemeResponseDto(Theme theme, List<ThemeContentDto> contents, int articleCount, int videoCount, String thumbnailUrl) {
        this.id = theme.getId();
        this.name = theme.getName();
        this.description = theme.getDescription();
        this.emoji = theme.getEmoji();
        this.thumbnailUrl = thumbnailUrl;  // 첫 번째 컨텐츠의 썸네일 사용
        this.displayOrder = theme.getDisplayOrder();
        this.isActive = theme.getIsActive();
        this.contents = contents;
        this.articleCount = articleCount;
        this.videoCount = videoCount;
        this.totalContentCount = articleCount + videoCount;
    }

    // 컨텐츠 목록 없이 생성 (목록용)
    public ThemeResponseDto(Theme theme, int articleCount, int videoCount, String thumbnailUrl) {
        this(theme, null, articleCount, videoCount, thumbnailUrl);
    }
}
