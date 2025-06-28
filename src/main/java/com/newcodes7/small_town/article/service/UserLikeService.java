package com.newcodes7.small_town.article.service;

import com.newcodes7.small_town.article.entity.Article;
import com.newcodes7.small_town.article.entity.LikeLog;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.LikeLogRepository;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.article.exception.ArticleNotFoundException;
import com.newcodes7.small_town.article.exception.UserNotFoundException;
import com.newcodes7.small_town.article.exception.InvalidParameterException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class UserLikeService {
    
    private final LikeLogRepository likeLogRepository;
    private final ArticleRepository articleRepository;
    private final UserRepository userRepository;
    
    public boolean toggleLike(Long articleId, String userEmail) {
        if (articleId == null || articleId <= 0) {
            throw new InvalidParameterException("articleId", articleId);
        }
        if (userEmail == null || userEmail.trim().isEmpty()) {
            throw new InvalidParameterException("userEmail", userEmail);
        }
        
        User user = userRepository.findByEmailAndDeletedAtIsNull(userEmail)
            .orElseThrow(() -> new UserNotFoundException(userEmail));
        
        Article article = articleRepository.findById(articleId)
            .orElseThrow(() -> new ArticleNotFoundException(articleId));
        
        Optional<LikeLog> existingLike = likeLogRepository.findByUserIdAndArticleIdAndDeletedAtIsNull(
            user.getId(), articleId
        );
        
        boolean isLiked;
        if (existingLike.isPresent()) {
            // 이미 좋아요가 있으면 삭제 (소프트 삭제)
            existingLike.get().delete();
            isLiked = false;
        } else {
            // 좋아요가 없으면 생성
            LikeLog likeLog = new LikeLog(user, article);
            likeLogRepository.save(likeLog);
            isLiked = true;
        }
        
        // 좋아요 수 업데이트
        long likeCount = likeLogRepository.countByArticleIdAndDeletedAtIsNull(articleId);
        articleRepository.updateLikeCount(articleId, (int) likeCount);
        
        return isLiked;
    }
    
    @Transactional(readOnly = true)
    public boolean hasLiked(Long articleId, String userEmail) {
        User user = userRepository.findByEmailAndDeletedAtIsNull(userEmail)
            .orElse(null);
        
        if (user == null) {
            return false;
        }
        
        return likeLogRepository.existsByUserIdAndArticleIdAndDeletedAtIsNull(
            user.getId(), articleId
        );
    }
    
    @Transactional(readOnly = true)
    public long getLikeCount(Long articleId) {
        return likeLogRepository.countByArticleIdAndDeletedAtIsNull(articleId);
    }
}