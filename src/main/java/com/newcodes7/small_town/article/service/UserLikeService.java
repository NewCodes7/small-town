package com.newcodes7.small_town.article.service;

import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.entity.LikeLog;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.LikeLogRepository;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.article.exception.ArticleNotFoundException;
import com.newcodes7.small_town.global.exception.UserNotFoundException;
import com.newcodes7.small_town.global.exception.InvalidParameterException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

        // email, oauthUsername, oauthProviderId 중 하나로 조회
        User user = userRepository.findByUsernameAndDeletedAtIsNull(userEmail)
            .orElseThrow(() -> new UserNotFoundException(userEmail));
        
        Article article = articleRepository.findById(articleId)
            .orElseThrow(() -> new ArticleNotFoundException(articleId));
        
        Optional<LikeLog> existingLike = likeLogRepository.findByUserIdAndArticleIdAndDeletedAtIsNull(
            user.getId(), articleId
        );
        
        boolean isLiked;
        if (existingLike.isPresent()) {
            // 이미 좋아요가 있으면 삭제 (물리적 삭제)
            likeLogRepository.delete(existingLike.get());
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
        // email, oauthUsername, oauthProviderId 중 하나로 조회
        User user = userRepository.findByUsernameAndDeletedAtIsNull(userEmail)
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

    /**
     * 익명 사용자의 좋아요 토글 (IP 기반)
     */
    public boolean toggleLikeByIp(Long articleId, String ipAddress) {
        if (articleId == null || articleId <= 0) {
            throw new InvalidParameterException("articleId", articleId);
        }
        if (ipAddress == null || ipAddress.trim().isEmpty()) {
            throw new InvalidParameterException("ipAddress", ipAddress);
        }

        Article article = articleRepository.findById(articleId)
            .orElseThrow(() -> new ArticleNotFoundException(articleId));

        Optional<LikeLog> existingLike = likeLogRepository.findByIpAddressAndArticleIdAndDeletedAtIsNull(
            ipAddress, articleId
        );

        boolean isLiked;
        if (existingLike.isPresent()) {
            // 이미 좋아요가 있으면 삭제 (물리적 삭제)
            likeLogRepository.delete(existingLike.get());
            isLiked = false;
        } else {
            // 좋아요가 없으면 생성
            LikeLog likeLog = new LikeLog(article, ipAddress);
            likeLogRepository.save(likeLog);
            isLiked = true;
        }

        // 좋아요 수 업데이트
        long likeCount = likeLogRepository.countByArticleIdAndDeletedAtIsNull(articleId);
        articleRepository.updateLikeCount(articleId, (int) likeCount);

        return isLiked;
    }

    /**
     * 익명 사용자의 좋아요 확인 (IP 기반)
     */
    @Transactional(readOnly = true)
    public boolean hasLikedByIp(Long articleId, String ipAddress) {
        if (ipAddress == null || ipAddress.trim().isEmpty()) {
            return false;
        }

        return likeLogRepository.existsByIpAddressAndArticleIdAndDeletedAtIsNull(
            ipAddress, articleId
        );
    }

    /**
     * 여러 article의 좋아요 상태를 한 번에 조회 (Batch) - 로그인 사용자용
     * N+1 문제 해결을 위한 메서드
     *
     * @param articleIds 조회할 article ID 목록
     * @param userEmail 사용자 이메일
     * @return articleId -> isLiked 매핑 (true: 좋아요함, false: 좋아요 안함)
     */
    @Transactional(readOnly = true)
    public java.util.Map<Long, Boolean> getLikeStatusBatchByUser(java.util.List<Long> articleIds, String userEmail) {
        if (articleIds == null || articleIds.isEmpty()) {
            return java.util.Collections.emptyMap();
        }

        if (userEmail == null || userEmail.trim().isEmpty()) {
            // 이메일이 없으면 모두 false
            return articleIds.stream()
                .collect(java.util.stream.Collectors.toMap(id -> id, id -> false));
        }

        // 사용자 조회
        User user = userRepository.findByUsernameAndDeletedAtIsNull(userEmail).orElse(null);
        if (user == null) {
            // 사용자가 없으면 모두 false
            return articleIds.stream()
                .collect(java.util.stream.Collectors.toMap(id -> id, id -> false));
        }

        // 한 번의 쿼리로 좋아요한 article ID 목록 조회
        java.util.List<Long> likedArticleIds = likeLogRepository.findLikedArticleIdsByUserIdAndArticleIds(
            user.getId(), articleIds
        );

        // Set으로 변환하여 빠른 조회
        java.util.Set<Long> likedSet = new java.util.HashSet<>(likedArticleIds);

        // Map 생성: articleId -> isLiked
        return articleIds.stream()
            .collect(java.util.stream.Collectors.toMap(id -> id, likedSet::contains));
    }

    /**
     * 여러 article의 좋아요 상태를 한 번에 조회 (Batch) - IP 주소용
     * N+1 문제 해결을 위한 메서드
     *
     * @param articleIds 조회할 article ID 목록
     * @param ipAddress IP 주소
     * @return articleId -> isLiked 매핑 (true: 좋아요함, false: 좋아요 안함)
     */
    @Transactional(readOnly = true)
    public java.util.Map<Long, Boolean> getLikeStatusBatchByIp(java.util.List<Long> articleIds, String ipAddress) {
        if (articleIds == null || articleIds.isEmpty()) {
            return java.util.Collections.emptyMap();
        }

        if (ipAddress == null || ipAddress.trim().isEmpty()) {
            // IP가 없으면 모두 false
            return articleIds.stream()
                .collect(java.util.stream.Collectors.toMap(id -> id, id -> false));
        }

        // 한 번의 쿼리로 좋아요한 article ID 목록 조회
        java.util.List<Long> likedArticleIds = likeLogRepository.findLikedArticleIdsByIpAddressAndArticleIds(
            ipAddress, articleIds
        );

        // Set으로 변환하여 빠른 조회
        java.util.Set<Long> likedSet = new java.util.HashSet<>(likedArticleIds);

        // Map 생성: articleId -> isLiked
        return articleIds.stream()
            .collect(java.util.stream.Collectors.toMap(id -> id, likedSet::contains));
    }

    /**
     * 사용자가 좋아요한 아티클 목록 조회 (페이지네이션)
     */
    @Transactional(readOnly = true)
    public Page<ArticleListResponseDto> getLikedArticles(String userEmail, int page, int size) {
        // email, oauthUsername, oauthProviderId 중 하나로 조회
        User user = userRepository.findByUsernameAndDeletedAtIsNull(userEmail)
            .orElseThrow(() -> new UserNotFoundException(userEmail));

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Article> articles = likeLogRepository.findLikedArticlesByUserId(user.getId(), pageable);

        return articles.map(ArticleListResponseDto::new);
    }

    /**
     * localStorage에서 좋아요 목록 마이그레이션 (로그인 시)
     */
    public com.newcodes7.small_town.article.dto.MigrationResultDto migrateLikesFromLocalStorage(String userEmail, java.util.List<Long> articleIds) {
        java.util.List<com.newcodes7.small_town.article.dto.MigrationResultDto.MigratedItemDto> migratedItems = new java.util.ArrayList<>();

        if (articleIds == null || articleIds.isEmpty()) {
            return new com.newcodes7.small_town.article.dto.MigrationResultDto(0, migratedItems);
        }

        // email, oauthUsername, oauthProviderId 중 하나로 조회
        User user = userRepository.findByUsernameAndDeletedAtIsNull(userEmail)
            .orElseThrow(() -> new UserNotFoundException(userEmail));

        for (Long articleId : articleIds) {
            try {
                // 이미 좋아요가 있는지 확인
                boolean alreadyLiked = likeLogRepository.existsByUserIdAndArticleIdAndDeletedAtIsNull(
                    user.getId(), articleId
                );

                if (!alreadyLiked) {
                    // 아티클이 존재하는지 확인
                    Optional<Article> articleOpt = articleRepository.findById(articleId);
                    if (articleOpt.isPresent()) {
                        Article article = articleOpt.get();

                        // 좋아요 생성
                        LikeLog likeLog = new LikeLog(user, article);
                        likeLogRepository.save(likeLog);

                        // 마이그레이션된 아티클 정보 추가
                        String title = article.getTranslatedTitle() != null && !article.getTranslatedTitle().isEmpty()
                            ? article.getTranslatedTitle()
                            : article.getTitle();
                        migratedItems.add(new com.newcodes7.small_town.article.dto.MigrationResultDto.MigratedItemDto(
                            article.getId(),
                            title,
                            "article"
                        ));

                        // 좋아요 수 업데이트
                        long likeCount = likeLogRepository.countByArticleIdAndDeletedAtIsNull(articleId);
                        articleRepository.updateLikeCount(articleId, (int) likeCount);
                    }
                }
            } catch (Exception e) {
                // 개별 아티클 마이그레이션 실패 시 로그만 남기고 계속 진행
                System.err.println("Failed to migrate like for article " + articleId + ": " + e.getMessage());
            }
        }

        return new com.newcodes7.small_town.article.dto.MigrationResultDto(migratedItems.size(), migratedItems);
    }
}