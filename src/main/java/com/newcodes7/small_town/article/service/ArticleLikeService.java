package com.newcodes7.small_town.article.service;

import com.newcodes7.small_town.article.entity.Like;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.LikeRepository;
import com.newcodes7.small_town.global.entity.Article;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ArticleLikeService {
    
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

    /**
     * 여러 article의 좋아요 상태를 한 번에 조회 (Batch)
     * N+1 문제 해결을 위한 메서드
     *
     * @param articleIds 조회할 article ID 목록
     * @param ipAddress 사용자 IP 주소
     * @return articleId -> isLiked 매핑 (true: 좋아요함, false: 좋아요 안함)
     */
    @Transactional(readOnly = true)
    public java.util.Map<Long, Boolean> getLikeStatusBatch(java.util.List<Long> articleIds, String ipAddress) {
        if (articleIds == null || articleIds.isEmpty()) {
            return java.util.Collections.emptyMap();
        }

        if (ipAddress == null || ipAddress.isEmpty()) {
            // IP가 없으면 모두 false
            return articleIds.stream()
                .collect(java.util.stream.Collectors.toMap(id -> id, id -> false));
        }

        // 한 번의 쿼리로 좋아요한 article ID 목록 조회
        java.util.List<Long> likedArticleIds = likeRepository.findLikedArticleIdsByIpAddressAndArticleIds(
            ipAddress, articleIds
        );

        // Set으로 변환하여 빠른 조회
        java.util.Set<Long> likedSet = new java.util.HashSet<>(likedArticleIds);

        // Map 생성: articleId -> isLiked
        return articleIds.stream()
            .collect(java.util.stream.Collectors.toMap(id -> id, likedSet::contains));
    }
}