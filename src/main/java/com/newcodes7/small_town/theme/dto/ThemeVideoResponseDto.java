package com.newcodes7.small_town.theme.dto;

import com.newcodes7.small_town.theme.entity.ThemeVideo;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 테마-비디오 연결 응답 DTO
 */
@Getter
public class ThemeVideoResponseDto {

    private final Long id;
    private final Long themeId;
    private final String themeName;
    private final Long videoId;
    private final String videoTitle;
    private final Integer displayOrder;
    private final LocalDateTime createdAt;

    public ThemeVideoResponseDto(ThemeVideo themeVideo) {
        this.id = themeVideo.getId();
        this.themeId = themeVideo.getTheme().getId();
        this.themeName = themeVideo.getTheme().getName();
        this.videoId = themeVideo.getVideo().getId();
        this.videoTitle = themeVideo.getVideo().getTitle();
        this.displayOrder = themeVideo.getDisplayOrder();
        this.createdAt = themeVideo.getCreatedAt();
    }
}
