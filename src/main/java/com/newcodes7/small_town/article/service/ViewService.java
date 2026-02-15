package com.newcodes7.small_town.article.service;

import com.newcodes7.small_town.article.entity.ViewLog;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.ViewLogRepository;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.article.exception.ArticleNotFoundException;
import com.newcodes7.small_town.global.exception.UserNotFoundException;
import com.newcodes7.small_town.article.exception.ViewCooldownException;
import com.newcodes7.small_town.global.exception.InvalidParameterException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ViewService {
    
    private static final int VIEW_COOLDOWN_MINUTES = 30;
    
    private final ViewLogRepository viewLogRepository;
    private final ArticleRepository articleRepository;
    private final UserRepository userRepository;
    
    /**
     * 인증된 사용자의 조회수 증가
     */
    public boolean incrementViewCount(Long articleId, String userEmail, String ipAddress) {
        if (articleId == null || articleId <= 0) {
            throw new InvalidParameterException("articleId", articleId);
        }
        if (userEmail == null || userEmail.trim().isEmpty()) {
            throw new InvalidParameterException("userEmail", userEmail);
        }
        
        User user = userRepository.findByUsernameAndDeletedAtIsNull(userEmail)
            .orElseThrow(() -> new UserNotFoundException(userEmail));
        
        Article article = articleRepository.findById(articleId)
            .orElseThrow(() -> new ArticleNotFoundException(articleId));
        
        // 30분 이내 조회 기록 확인
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(VIEW_COOLDOWN_MINUTES);
        Optional<ViewLog> recentView = viewLogRepository.findRecentViewByUserAndArticle(
            user.getId(), articleId, cutoffTime
        );
        
        if (recentView.isPresent()) {
            log.debug("사용자 {}의 게시글 {} 조회가 쿨다운 중입니다. 마지막 조회: {}", 
                     userEmail, articleId, recentView.get().getCreatedAt());
            return false; // 쿨다운 중이므로 조회수 증가하지 않음
        }
        
        // 새로운 조회 기록 생성
        ViewLog viewLog = new ViewLog(article, user, ipAddress);
        viewLogRepository.save(viewLog);
        
        // Article 엔티티의 조회수 업데이트
        updateArticleViewCount(articleId);
        
        log.debug("사용자 {}의 게시글 {} 조회수가 증가했습니다.", userEmail, articleId);
        return true;
    }
    
    /**
     * 익명 사용자의 조회수 증가 (IP 기반)
     */
    public boolean incrementViewCountByIp(Long articleId, String ipAddress) {
        if (articleId == null || articleId <= 0) {
            throw new InvalidParameterException("articleId", articleId);
        }
        if (ipAddress == null || ipAddress.trim().isEmpty()) {
            throw new InvalidParameterException("ipAddress", ipAddress);
        }
        
        Article article = articleRepository.findById(articleId)
            .orElseThrow(() -> new ArticleNotFoundException(articleId));
        
        // 30분 이내 조회 기록 확인
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(VIEW_COOLDOWN_MINUTES);
        Optional<ViewLog> recentView = viewLogRepository.findRecentViewByIpAndArticle(
            ipAddress, articleId, cutoffTime
        );
        
        if (recentView.isPresent()) {
            log.debug("IP {}의 게시글 {} 조회가 쿨다운 중입니다. 마지막 조회: {}", 
                     ipAddress, articleId, recentView.get().getCreatedAt());
            return false; // 쿨다운 중이므로 조회수 증가하지 않음
        }
        
        // 새로운 조회 기록 생성
        ViewLog viewLog = new ViewLog(article, ipAddress);
        viewLogRepository.save(viewLog);
        
        // Article 엔티티의 조회수 업데이트
        updateArticleViewCount(articleId);
        
        log.debug("IP {}의 게시글 {} 조회수가 증가했습니다.", ipAddress, articleId);
        return true;
    }
    
    /**
     * Article 엔티티의 조회수 증가 (원자적 연산으로 동시성 안전)
     */
    private void updateArticleViewCount(Long articleId) {
        articleRepository.incrementViewCount(articleId);
    }
    
    /**
     * 게시글의 총 조회수 조회 (Article 엔티티의 캐시된 값 반환)
     */
    @Transactional(readOnly = true)
    public long getViewCount(Long articleId) {
        return articleRepository.findById(articleId)
            .map(article -> article.getViewCount() != null ? article.getViewCount().longValue() : 0L)
            .orElse(0L);
    }
    
    /**
     * 사용자의 최근 조회 시간 확인 (인증된 사용자)
     */
    @Transactional(readOnly = true)
    public Optional<LocalDateTime> getLastViewTime(Long articleId, String userEmail) {
        User user = userRepository.findByUsernameAndDeletedAtIsNull(userEmail)
            .orElse(null);
        
        if (user == null) {
            return Optional.empty();
        }
        
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(VIEW_COOLDOWN_MINUTES);
        return viewLogRepository.findRecentViewByUserAndArticle(user.getId(), articleId, cutoffTime)
            .map(ViewLog::getCreatedAt);
    }
    
    /**
     * IP의 최근 조회 시간 확인 (익명 사용자)
     */
    @Transactional(readOnly = true)
    public Optional<LocalDateTime> getLastViewTimeByIp(Long articleId, String ipAddress) {
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(VIEW_COOLDOWN_MINUTES);
        return viewLogRepository.findRecentViewByIpAndArticle(ipAddress, articleId, cutoffTime)
            .map(ViewLog::getCreatedAt);
    }
}