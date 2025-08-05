package com.newcodes7.small_town.article.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.dto.ArticleResponseDto;
import com.newcodes7.small_town.article.dto.CorporationDetailDto;
import com.newcodes7.small_town.article.dto.FeedbackCreateDto;
import com.newcodes7.small_town.article.dto.FeedbackResponseDto;
import com.newcodes7.small_town.article.service.ArticleService;
import com.newcodes7.small_town.article.service.FeedbackService;
import com.newcodes7.small_town.article.service.LikeService;
import com.newcodes7.small_town.article.service.UserLikeService;
import com.newcodes7.small_town.article.service.ViewService;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.corporation.service.CorporationService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.newcodes7.small_town.article.dto.CorporationDto;
import com.newcodes7.small_town.article.dto.GroupedArticlesDto;
import com.newcodes7.small_town.article.entity.Article;
import com.newcodes7.small_town.article.entity.Corporation;

import java.util.stream.Collectors;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ArticleController {
    
    private final ArticleService articleService;
    private final LikeService likeService;
    private final UserLikeService userLikeService;
    private final ViewService viewService;
    private final CorporationService corporationService;
    private final FeedbackService feedbackService;
    
    @GetMapping("/api/articles")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getArticlesWithFilters(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "latest") String sort,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) List<String> regions,
            @RequestParam(defaultValue = "list") String view) {
        
        Page<ArticleResponseDto> articles = articleService.getArticlesWithFilters(
            keyword != null && !keyword.trim().isEmpty() ? keyword.trim() : null,
            regions,
            page,
            size,
            sort,
            view
        );
        
        Map<String, Object> response = new HashMap<>();
        response.put("content", articles.getContent());
        response.put("currentPage", page);
        response.put("totalPages", articles.getTotalPages());
        response.put("totalElements", articles.getTotalElements());
        response.put("hasNext", articles.hasNext());
        response.put("hasPrevious", articles.hasPrevious());
        response.put("currentSort", sort);
        response.put("keyword", keyword);
        response.put("selectedRegions", regions != null ? regions : new ArrayList<>());
        response.put("view", view);
        
        return ResponseEntity.ok(response);
    }

    // @GetMapping("/api/articles/grouped")
    // @ResponseBody
    // public ResponseEntity<List<GroupedArticlesDto>> getArticlesGroupedByCorporation() {
    //     Map<Corporation, List<Article>> groupedArticles = articleService.getArticlesGroupedByCorporation();

    //     List<GroupedArticlesDto> result = groupedArticles.entrySet().stream()
    //             .map(entry -> new GroupedArticlesDto(
    //                     new CorporationDto(entry.getKey()),
    //                     entry.getValue().stream()
    //                             .map(ArticleListResponseDto::new)
    //                             .collect(Collectors.toList())
    //             ))
    //             .collect(Collectors.toList());

    //     return ResponseEntity.ok(result);
    // }
    
    @GetMapping({"", "/"})
    public String home(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "latest") String sort,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) List<String> regions,
            @RequestParam(defaultValue = "list") String view,
            Model model) {
        
        Page<ArticleResponseDto> articles = articleService.getArticlesWithFilters(
            keyword != null && !keyword.trim().isEmpty() ? keyword.trim() : null,
            regions,
            page,
            size,
            sort,
            view
        );
        
        log.info("필터 조건: keyword='{}', regions={}, {}개의 글 조회", keyword, regions, articles.getTotalElements());

        // 회사 목록 가져오기 (모든 회사 포함)
        Page<CorporationResponseDto> corporations = corporationService.getAllCorporations(PageRequest.of(0, 50));
        
        List<CorporationResponseDto> corporationsWithLogos = corporations.getContent().stream()
            .filter(corp -> corp.getLogoUrl() != null && !corp.getLogoUrl().trim().isEmpty())
            .limit(20) 
            .toList();
        
        // 인기글 5개 가져오기 (배너용)
        Page<ArticleListResponseDto> popularArticles = articleService.getArticleList(0, 5, "popular");
        
        log.info("로고가 있는 회사 수: {}", corporationsWithLogos.size());
        log.info("인기글 수: {}", popularArticles.getContent().size());

        model.addAttribute("articles", articles);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", articles.getTotalPages());
        model.addAttribute("totalElements", articles.getTotalElements());
        model.addAttribute("currentSort", sort);
        model.addAttribute("keyword", keyword);
        model.addAttribute("selectedRegions", regions != null ? regions : new ArrayList<>());
        model.addAttribute("hasNext", articles.hasNext());
        model.addAttribute("hasPrevious", articles.hasPrevious());
        model.addAttribute("corporations", corporationsWithLogos);
        model.addAttribute("popularArticles", popularArticles.getContent());
        
        return "home";
    }
    
    // @PostMapping("/api/articles/{articleId}/like")
    // @ResponseBody
    // public ResponseEntity<Map<String, Object>> toggleLike(@PathVariable Long articleId,
    //                                                      @AuthenticationPrincipal UserDetails userDetails) {
    //     if (userDetails == null) {
    //         return ResponseEntity.status(401).build();
    //     }
        
    //     boolean isLiked = userLikeService.toggleLike(articleId, userDetails.getUsername());
    //     long likeCount = userLikeService.getLikeCount(articleId);
        
    //     Map<String, Object> response = new HashMap<>();
    //     response.put("isLiked", isLiked);
    //     response.put("likeCount", likeCount);
        
    //     return ResponseEntity.ok(response);
    // }
    
    // @GetMapping("/api/articles/{articleId}/like-status")
    // @ResponseBody
    // public ResponseEntity<Map<String, Object>> getLikeStatus(@PathVariable Long articleId,
    //                                                        @AuthenticationPrincipal UserDetails userDetails) {
    //     boolean hasLiked = false;
    //     if (userDetails != null) {
    //         hasLiked = userLikeService.hasLiked(articleId, userDetails.getUsername());
    //     }
        
    //     long likeCount = userLikeService.getLikeCount(articleId);
        
    //     Map<String, Object> response = new HashMap<>();
    //     response.put("hasLiked", hasLiked);
    //     response.put("likeCount", likeCount);
    //     response.put("authenticated", userDetails != null);
        
    //     return ResponseEntity.ok(response);
    // }
    
    // @PostMapping("/api/articles/{articleId}/view")
    // @ResponseBody
    // public ResponseEntity<Map<String, Object>> incrementViewCount(@PathVariable Long articleId,
    //                                                             @AuthenticationPrincipal UserDetails userDetails,
    //                                                             HttpServletRequest request) {
    //     String ipAddress = getClientIpAddress(request);
    //     boolean incremented;
        
    //     if (userDetails != null) {
    //         // 인증된 사용자
    //         incremented = viewService.incrementViewCount(articleId, userDetails.getUsername(), ipAddress);
    //     } else {
    //         // 익명 사용자 (IP 기반)
    //         incremented = viewService.incrementViewCountByIp(articleId, ipAddress);
    //     }
        
    //     long viewCount = viewService.getViewCount(articleId);
        
    //     Map<String, Object> response = new HashMap<>();
    //     response.put("incremented", incremented);
    //     response.put("viewCount", viewCount);
    //     response.put("authenticated", userDetails != null);
        
    //     return ResponseEntity.ok(response);
    // }
    
    // @GetMapping("/api/articles/{articleId}/view-status")
    // @ResponseBody
    // public ResponseEntity<Map<String, Object>> getViewStatus(@PathVariable Long articleId,
    //                                                        @AuthenticationPrincipal UserDetails userDetails,
    //                                                        HttpServletRequest request) {
    //     String ipAddress = getClientIpAddress(request);
    //     long viewCount = viewService.getViewCount(articleId);
        
    //     Map<String, Object> response = new HashMap<>();
    //     response.put("viewCount", viewCount);
    //     response.put("authenticated", userDetails != null);
        
    //     // 쿨다운 정보 추가
    //     if (userDetails != null) {
    //         viewService.getLastViewTime(articleId, userDetails.getUsername())
    //             .ifPresent(lastViewTime -> {
    //                 response.put("lastViewTime", lastViewTime);
    //                 response.put("canView", lastViewTime.plusMinutes(30).isBefore(java.time.LocalDateTime.now()));
    //             });
    //     } else {
    //         viewService.getLastViewTimeByIp(articleId, ipAddress)
    //             .ifPresent(lastViewTime -> {
    //                 response.put("lastViewTime", lastViewTime);
    //                 response.put("canView", lastViewTime.plusMinutes(30).isBefore(java.time.LocalDateTime.now()));
    //             });
    //     }
        
    //     return ResponseEntity.ok(response);
    // }
    
    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        
        return request.getRemoteAddr();
    }
    
    @GetMapping("/corporations/{corporationId}")
    public String corporationDetail(@PathVariable Long corporationId,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "10") int size,
                                  Model model) {
        CorporationDetailDto corporation = articleService.getCorporationDetail(corporationId);
        Page<ArticleListResponseDto> articles = articleService.getArticlesByCorporation(corporationId, page, size);
        
        model.addAttribute("corporation", corporation);
        model.addAttribute("articles", articles);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", articles.getTotalPages());
        model.addAttribute("totalElements", articles.getTotalElements());
        model.addAttribute("hasNext", articles.hasNext());
        model.addAttribute("hasPrevious", articles.hasPrevious());
        
        return "corporation-detail";
    }
    
    @GetMapping("/about")
    public String about(Model model) {
        // Add statistics for the about page
        long totalArticles = articleService.getTotalArticleCount();
        long totalCorporations = corporationService.getTotalCorporationCount();
        
        model.addAttribute("totalArticles", totalArticles);
        model.addAttribute("totalCorporations", totalCorporations);
        
        return "about";
    }
    
    @PostMapping("/api/feedback")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> submitFeedback(
            @RequestBody FeedbackCreateDto feedbackDto,
            HttpServletRequest request) {
        
        try {
            // IP 주소와 User-Agent 추출
            String ipAddress = getClientIpAddress(request);
            String userAgent = request.getHeader("User-Agent");
            
            // 피드백 저장
            FeedbackResponseDto savedFeedback = feedbackService.createFeedback(feedbackDto, ipAddress, userAgent);
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "피드백이 성공적으로 접수되었습니다.");
            response.put("feedbackId", savedFeedback.getId());
            
            return ResponseEntity.ok(response);
            
        } catch (RuntimeException e) {
            log.error("피드백 접수 중 오류 발생: {}", e.getMessage(), e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", e.getMessage());
            
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("피드백 접수 중 예상치 못한 오류 발생", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", "서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
            
            return ResponseEntity.internalServerError().body(response);
        }
    }
    
    @GetMapping("/corporations")
    public String corporations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) String search,
            Model model) {
        
        Page<CorporationResponseDto> corporations;
        
        if (search != null && !search.trim().isEmpty()) {
            corporations = corporationService.searchCorporations(search.trim(), PageRequest.of(page, size));
        } else {
            corporations = corporationService.getAllCorporations(PageRequest.of(page, size));
        }
        
        // Statistics
        long totalCorporations = corporationService.getTotalCorporationCount();
        long totalArticles = articleService.getTotalArticleCount();
        
        // For now, assume all corporations are domestic since the corporation module doesn't have isDomestic field
        // In a real implementation, you would add this field to the corporation entity and update the statistics accordingly
        long domesticCount = totalCorporations;
        long overseasCount = 0;
        
        model.addAttribute("corporations", corporations);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", corporations.getTotalPages());
        model.addAttribute("totalElements", corporations.getTotalElements());
        model.addAttribute("hasNext", corporations.hasNext());
        model.addAttribute("hasPrevious", corporations.hasPrevious());
        model.addAttribute("currentSearch", search);
        
        // Statistics
        model.addAttribute("totalCorporations", totalCorporations);
        model.addAttribute("domesticCount", domesticCount);
        model.addAttribute("overseasCount", overseasCount);
        model.addAttribute("totalArticles", totalArticles);
        
        return "corporations";
    }
    
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
    
    @GetMapping("/api/user-info")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getUserInfo(
            @AuthenticationPrincipal UserDetails userDetails) {
        
        Map<String, Object> response = new HashMap<>();
        
        if (userDetails == null) {
            response.put("authenticated", false);
            response.put("isAdmin", false);
        } else {
            response.put("authenticated", true);
            response.put("isAdmin", isAdmin(userDetails));
            response.put("username", userDetails.getUsername());
        }
        
        return ResponseEntity.ok(response);
    }
    
    private boolean isAdmin(UserDetails userDetails) {
        return userDetails.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }
}