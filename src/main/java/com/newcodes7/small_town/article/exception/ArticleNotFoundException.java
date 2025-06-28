package com.newcodes7.small_town.article.exception;

public class ArticleNotFoundException extends ArticleException {
    
    public ArticleNotFoundException(Long articleId) {
        super("ARTICLE_NOT_FOUND", "게시글을 찾을 수 없습니다. ID: %d", articleId);
    }
    
    public ArticleNotFoundException(String title) {
        super("ARTICLE_NOT_FOUND", "게시글을 찾을 수 없습니다. 제목: %s", title);
    }
}