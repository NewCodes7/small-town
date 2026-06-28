package com.newcodes7.small_town.article.controller;

import com.newcodes7.small_town.activity.dto.ArticleReferralRequestDto;
import com.newcodes7.small_town.activity.service.ArticleClickLogService;
import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.dto.RelatedArticleDto;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.embedding.service.RelatedArticleService;
import com.newcodes7.small_town.global.util.Client;
import com.newcodes7.small_town.like.service.UserLikeService;
import com.newcodes7.small_town.search.service.ArticleSearchService;
import com.newcodes7.small_town.view.service.ViewService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 아티클 참여(engagement) API 컨트롤러.
 * (좋아요, 조회수, 유입경로, 관련 글 추천, 좋아요 목록)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ArticleEngagementController {

    private final UserLikeService userLikeService;
    private final ViewService viewService;
    private final ArticleClickLogService articleClickLogService;
    private final RelatedArticleService relatedArticleService;
    private final ArticleSearchService articleSearchService;
    private final com.newcodes7.small_town.auth.repository.UserRepository userRepository;
    private final com.newcodes7.small_town.like.repository.LikeLogRepository likeLogRepository;
    private final com.newcodes7.small_town.video.repository.VideoLikeLogRepository videoLikeLogRepository;

    @PostMapping("/api/articles/{articleId}/like")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> toggleLike(@PathVariable Long articleId,
                                                         @AuthenticationPrincipal UserDetails userDetails,
                                                         HttpServletRequest request) {
        String ipAddress = Client.getClientIpAddress(request);
        boolean isLiked;

        if (userDetails != null) {
            // 인증된 사용자
            isLiked = userLikeService.toggleLike(articleId, userDetails.getUsername());
        } else {
            // 익명 사용자 (IP 기반)
            isLiked = userLikeService.toggleLikeByIp(articleId, ipAddress);
        }

        long likeCount = userLikeService.getLikeCount(articleId);

        Map<String, Object> response = new HashMap<>();
        response.put("isLiked", isLiked);
        response.put("likeCount", likeCount);
        response.put("authenticated", userDetails != null);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/articles/{articleId}/like-status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getLikeStatus(@PathVariable Long articleId,
                                                           @AuthenticationPrincipal UserDetails userDetails,
                                                           HttpServletRequest request) {
        String ipAddress = Client.getClientIpAddress(request);
        boolean hasLiked = false;

        if (userDetails != null) {
            // 인증된 사용자
            hasLiked = userLikeService.hasLiked(articleId, userDetails.getUsername());
        } else {
            // 익명 사용자 (IP 기반)
            hasLiked = userLikeService.hasLikedByIp(articleId, ipAddress);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("hasLiked", hasLiked);
        response.put("authenticated", userDetails != null);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/articles/{articleId}/view")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> incrementViewCount(@PathVariable Long articleId,
                                                                @AuthenticationPrincipal UserDetails userDetails,
                                                                HttpServletRequest request) {
        String ipAddress = Client.getClientIpAddress(request);
        boolean incremented;

        if (userDetails != null) {
            // 인증된 사용자
            incremented = viewService.incrementViewCount(articleId, userDetails.getUsername(), ipAddress);
        } else {
            // 익명 사용자 (IP 기반)
            incremented = viewService.incrementViewCountByIp(articleId, ipAddress);
        }

        long viewCount = viewService.getViewCount(articleId);

        Map<String, Object> response = new HashMap<>();
        response.put("incremented", incremented);
        response.put("authenticated", userDetails != null);

        return ResponseEntity.ok(response);
    }

    /**
     * 아티클 유입경로(referral) 추적 API.
     * 조회수 쿨다운과 무관하게 클릭마다 1건씩 source/context를 비동기로 기록한다.
     */
    @PostMapping("/api/articles/{articleId}/referral")
    @ResponseBody
    public ResponseEntity<Void> recordReferral(
            @PathVariable Long articleId,
            @RequestBody ArticleReferralRequestDto dto,
            @AuthenticationPrincipal UserDetails userDetails,
            HttpServletRequest request) {

        String username = userDetails != null ? userDetails.getUsername() : null;
        String ipAddress = Client.getClientIpAddress(request);
        String userAgent = request.getHeader("User-Agent");

        articleClickLogService.logReferral(articleId, dto, username, ipAddress, userAgent);
        return ResponseEntity.noContent().build();
    }

    /**
     * 관련 글 추천 API
     * 대표 Chunk의 embedding 유사도를 기반으로 관련 글 추천
     *
     * @param articleId Article ID
     * @param limit 결과 수 (기본 3)
     * @return 관련 글 목록
     */
    @GetMapping("/api/articles/{articleId}/related")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getRelatedArticles(
            @PathVariable Long articleId,
            @RequestParam(defaultValue = "3") Integer limit) {

        List<RelatedArticleDto> relatedArticles = relatedArticleService.getRelatedArticles(articleId, limit);

        Map<String, Object> response = new HashMap<>();
        response.put("relatedArticles", relatedArticles);
        response.put("count", relatedArticles.size());

        return ResponseEntity.ok(response);
    }

    // 로그인 사용자 좋아요 목록 API (아티클 + 비디오 통합)
    @GetMapping("/api/liked-items")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getLikedItems(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal UserDetails userDetails) {

        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // email, oauthUsername, oauthProviderId 중 하나로 조회
        User user = userRepository.findByUsernameAndDeletedAtIsNull(userDetails.getUsername())
            .orElse(null);

        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // 아티클 좋아요 조회
        java.util.List<com.newcodes7.small_town.like.entity.LikeLog> articleLikes =
            likeLogRepository.findLikedArticlesWithTimestampByUserId(user.getId());

        // 비디오 좋아요 조회
        java.util.List<com.newcodes7.small_town.video.entity.VideoLikeLog> videoLikes =
            videoLikeLogRepository.findLikedVideosWithTimestampByUserId(user.getId());

        // LikedItemDto로 변환
        java.util.List<com.newcodes7.small_town.like.dto.LikedItemDto> allItems = new java.util.ArrayList<>();

        for (com.newcodes7.small_town.like.entity.LikeLog like : articleLikes) {
            allItems.add(new com.newcodes7.small_town.like.dto.LikedItemDto(
                like.getArticle(), like.getCreatedAt()));
        }

        for (com.newcodes7.small_town.video.entity.VideoLikeLog like : videoLikes) {
            allItems.add(new com.newcodes7.small_town.like.dto.LikedItemDto(
                like.getVideo(), like.getCreatedAt()));
        }

        // 좋아요 시간순으로 정렬 (최신순)
        allItems.sort((a, b) -> b.getLikedAt().compareTo(a.getLikedAt()));

        // 페이지네이션 적용
        int start = page * size;
        int end = Math.min(start + size, allItems.size());
        java.util.List<com.newcodes7.small_town.like.dto.LikedItemDto> pagedItems =
            start < allItems.size() ? allItems.subList(start, end) : java.util.Collections.emptyList();

        Map<String, Object> response = new HashMap<>();
        response.put("content", pagedItems);
        response.put("currentPage", page);
        response.put("totalPages", (int) Math.ceil((double) allItems.size() / size));
        response.put("totalElements", allItems.size());
        response.put("hasNext", end < allItems.size());
        response.put("hasPrevious", page > 0);

        return ResponseEntity.ok(response);
    }

    // 로그인 사용자 좋아요 목록 API (아티클만 - 하위 호환성)
    @GetMapping("/api/articles/liked")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getLikedArticles(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal UserDetails userDetails) {

        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Page<ArticleListResponseDto> articles = userLikeService.getLikedArticles(
            userDetails.getUsername(), page, size);

        Map<String, Object> response = new HashMap<>();
        response.put("content", articles.getContent());
        response.put("currentPage", page);
        response.put("totalPages", articles.getTotalPages());
        response.put("totalElements", articles.getTotalElements());
        response.put("hasNext", articles.hasNext());
        response.put("hasPrevious", articles.hasPrevious());

        return ResponseEntity.ok(response);
    }

    /**
     * 배치 좋아요 상태 조회 API (Article)
     */
    @PostMapping("/api/articles/like-status/batch")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getBatchArticleLikeStatus(
            @RequestBody Map<String, List<Long>> request,
            @AuthenticationPrincipal UserDetails userDetails,
            HttpServletRequest httpRequest) {

        List<Long> articleIds = request.get("articleIds");
        if (articleIds == null || articleIds.isEmpty()) {
            return ResponseEntity.ok(Map.of("likeStatus", Map.of()));
        }

        String clientIp = Client.getClientIpAddress(httpRequest);
        String username = userDetails != null ? userDetails.getUsername() : null;

        Map<Long, Boolean> likeStatusMap = articleSearchService.getLikeStatusMap(articleIds, username, clientIp);

        return ResponseEntity.ok(Map.of("likeStatus", likeStatusMap));
    }
}
