package com.newcodes7.small_town.article.exception;

public class LikeAlreadyExistsException extends ArticleException {
    
    public LikeAlreadyExistsException(Long articleId, String userEmail) {
        super("LIKE_ALREADY_EXISTS", "이미 좋아요를 누른 게시글입니다. 게시글 ID: %d, 사용자: %s", articleId, userEmail);
    }
}