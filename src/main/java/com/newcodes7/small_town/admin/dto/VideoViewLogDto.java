package com.newcodes7.small_town.admin.dto;

import java.time.LocalDateTime;

public record VideoViewLogDto(
        Long viewLogId,
        Long videoId,
        String videoTitle,
        String videoLink,
        String corporationName,
        Integer videoViewCount,
        Integer videoLikeCount,
        String ipAddress,
        LocalDateTime viewedAt,
        boolean isLiked
) {}
