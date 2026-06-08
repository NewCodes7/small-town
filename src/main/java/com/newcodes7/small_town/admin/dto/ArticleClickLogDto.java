package com.newcodes7.small_town.admin.dto;

import com.newcodes7.small_town.article.entity.ArticleClickLog;

import java.time.LocalDateTime;

public record ArticleClickLogDto(
        Long id,
        Long articleId,
        String articleTitle,
        String source,
        Long sourceContextId,
        String userNickname,
        String ipAddress,
        LocalDateTime createdAt
) {
    public static ArticleClickLogDto from(ArticleClickLog log, String articleTitle) {
        return new ArticleClickLogDto(
                log.getId(),
                log.getArticleId(),
                articleTitle,
                log.getSource() != null ? log.getSource().name() : null,
                log.getSourceContextId(),
                log.getUser() != null ? log.getUser().getNickname() : null,
                log.getIpAddress(),
                log.getCreatedAt()
        );
    }
}
