package com.newcodes7.small_town.article.exception;

import java.time.LocalDateTime;

public class ViewCooldownException extends ArticleException {
    
    public ViewCooldownException(Long articleId, String identifier, LocalDateTime lastViewTime, int cooldownMinutes) {
        super("VIEW_COOLDOWN_ACTIVE", 
              "조회수 증가 쿨다운이 활성화되어 있습니다. 게시글 ID: %d, 식별자: %s, 마지막 조회: %s, 쿨다운: %d분", 
              articleId, identifier, lastViewTime, cooldownMinutes);
    }
}