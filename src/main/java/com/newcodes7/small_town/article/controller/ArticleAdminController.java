package com.newcodes7.small_town.article.controller;

import com.newcodes7.small_town.article.dto.ArticleResponseDto;
import com.newcodes7.small_town.article.service.ArticleService;
import com.newcodes7.small_town.global.util.Client;
import com.newcodes7.small_town.search.service.ArticleSearchService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 아티클 관리자 API 컨트롤러.
 * (게시글 삭제, 발행일 수정, 어드민 검색)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ArticleAdminController {

    private final ArticleService articleService;
    private final ArticleSearchService articleSearchService;

    @DeleteMapping("/api/admin/articles/{articleId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteArticle(
            @PathVariable Long articleId,
            @AuthenticationPrincipal UserDetails userDetails) {

        Map<String, Object> response = new HashMap<>();

        try {
            // 관리자 권한 확인
            if (userDetails == null || !isAdmin(userDetails)) {
                response.put("status", "error");
                response.put("message", "관리자 권한이 필요합니다.");
                return ResponseEntity.status(403).body(response);
            }

            // 게시글 삭제
            articleService.deleteArticle(articleId);

            response.put("status", "success");
            response.put("message", "게시글이 성공적으로 삭제되었습니다.");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("게시글 삭제 중 오류 발생: {}", e.getMessage(), e);

            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 관리자용 글 발행일 수정 API
     */
    @PutMapping("/api/admin/articles/{articleId}/publish-date")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateArticlePublishDate(
            @PathVariable Long articleId,
            @RequestBody Map<String, String> request,
            @AuthenticationPrincipal UserDetails userDetails) {

        Map<String, Object> response = new HashMap<>();

        // 관리자 권한 확인
        if (userDetails == null || !isAdmin(userDetails)) {
            response.put("status", "error");
            response.put("message", "관리자 권한이 필요합니다.");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        }

        try {
            String publishedAtStr = request.get("publishedAt");
            if (publishedAtStr == null || publishedAtStr.trim().isEmpty()) {
                response.put("status", "error");
                response.put("message", "발행일이 제공되지 않았습니다.");
                return ResponseEntity.badRequest().body(response);
            }

            // LocalDateTime으로 파싱 (datetime-local 형식: yyyy-MM-ddTHH:mm)
            LocalDateTime publishedAt = LocalDateTime.parse(publishedAtStr);

            // 글 발행일 업데이트
            boolean updated = articleService.updateArticlePublishDate(articleId, publishedAt);

            if (updated) {
                response.put("status", "success");
                response.put("message", "발행일이 성공적으로 수정되었습니다.");
                return ResponseEntity.ok(response);
            } else {
                response.put("status", "error");
                response.put("message", "글을 찾을 수 없습니다.");
                return ResponseEntity.notFound().build();
            }

        } catch (DateTimeParseException e) {
            response.put("status", "error");
            response.put("message", "올바르지 않은 날짜 형식입니다.");
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("글 발행일 수정 중 오류 발생: {}", e.getMessage(), e);

            response.put("status", "error");
            response.put("message", "발행일 수정 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 어드민용 검색 API (기존 home() 로직 사용)
     */
    @GetMapping("/api/admin/articles/search")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> searchArticlesForAdmin(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "sort", defaultValue = "relevance") String sort,
            @AuthenticationPrincipal UserDetails userDetails,
            HttpServletRequest request) {

        // 기존 home()과 동일한 검색 로직 사용
        Page<? extends ArticleResponseDto> articles;
        String username = userDetails != null ? userDetails.getUsername() : null;

        if (keyword != null && !keyword.trim().isEmpty()) {
            // Hybrid 검색 사용 (BM25 + ILIKE + Binary Boost)
            String clientIp = Client.getClientIpAddress(request);
            articles = articleSearchService.searchArticlesHybrid(
                keyword.trim().toLowerCase(),
                null,  // regions
                null,  // category
                page,
                size,
                sort,
                clientIp,
                username
            );
        } else {
            // 키워드 없으면 최신순 조회
            articles = articleService.getArticlesWithFilters(
                null,
                null,
                page,
                size,
                "latest",
                "list",
                null
            );
        }

        Map<String, Object> response = new HashMap<>();
        response.put("content", articles.getContent());
        response.put("totalElements", articles.getTotalElements());
        response.put("totalPages", articles.getTotalPages());
        response.put("currentPage", page);
        response.put("hasNext", articles.hasNext());

        return ResponseEntity.ok(response);
    }

    private boolean isAdmin(UserDetails userDetails) {
        return userDetails.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }
}
