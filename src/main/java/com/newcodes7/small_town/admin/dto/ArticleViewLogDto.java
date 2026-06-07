package com.newcodes7.small_town.admin.dto;

import java.time.LocalDateTime;

public record ArticleViewLogDto(
        Long viewLogId,
        Long articleId,
        String articleTitle,
        String articleLink,
        String corporationName,
        Integer articleViewCount,
        Integer articleLikeCount,
        String ipAddress,
        LocalDateTime viewedAt,
        boolean isLiked
) {}
