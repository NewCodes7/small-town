package com.newcodes7.small_town.article.service;

import com.newcodes7.small_town.article.entity.Article;
import com.newcodes7.small_town.article.entity.Like;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.LikeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class LikeService {
    
    private final LikeRepository likeRepository;
    private final ArticleRepository articleRepository;
    
    public boolean toggleLike(Long articleId, String ipAddress) {
        Article article = articleRepository.findById(articleId)
            .orElseThrow(() -> new IllegalArgumentException("Article not found"));
        
        boolean exists = likeRepository.existsByArticleIdAndIpAddress(articleId, ipAddress);
        
        if (exists) {
            likeRepository.deleteByArticleIdAndIpAddress(articleId, ipAddress);
        } else {
            Like like = new Like(article, ipAddress);
            likeRepository.save(like);
        }
        
        long likeCount = likeRepository.countByArticleId(articleId);
        articleRepository.updateLikeCount(articleId, (int) likeCount);
        
        return !exists;
    }
    
    @Transactional(readOnly = true)
    public boolean hasLiked(Long articleId, String ipAddress) {
        return likeRepository.existsByArticleIdAndIpAddress(articleId, ipAddress);
    }
    
    @Transactional(readOnly = true)
    public long getLikeCount(Long articleId) {
        return likeRepository.countByArticleId(articleId);
    }
}