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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.dto.CorporationDetailDto;
import com.newcodes7.small_town.article.service.ArticleService;
import com.newcodes7.small_town.article.service.LikeService;
import com.newcodes7.small_town.article.service.UserLikeService;
import com.newcodes7.small_town.article.service.ViewService;
import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.corporation.service.CorporationService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ArticleController {
    
    private final ArticleService articleService;
    private final LikeService likeService;
    private final UserLikeService userLikeService;
    private final ViewService viewService;
    private final CorporationService corporationService;
    
    @GetMapping("/api/articles")
    @ResponseBody
    public ResponseEntity<Page<ArticleListResponseDto>> getArticleList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "latest") String sort) {
        
        Page<ArticleListResponseDto> articles = articleService.getArticleList(page, size, sort);
        return ResponseEntity.ok(articles);
    }
    
    @GetMapping({"", "/"})
    public String home(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "latest") String sort,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) List<String> regions,
            Model model) {
        
        Page<ArticleListResponseDto> articles = articleService.getArticlesWithFilters(
            keyword != null && !keyword.trim().isEmpty() ? keyword.trim() : null,
            regions,
            page,
            size,
            sort
        );
        
        log.info("필터 조건: keyword='{}', regions={}, {}개의 글 조회", keyword, regions, articles.getTotalElements());

        // 회사 목록 가져오기 (모든 회사 포함)
        Page<CorporationResponseDto> corporations = corporationService.getAllCorporations(PageRequest.of(0, 50));
        
        List<CorporationResponseDto> corporationsWithLogos = corporations.getContent().stream()
            .filter(corp -> corp.getLogoUrl() != null && !corp.getLogoUrl().trim().isEmpty())
            .limit(20) 
            .toList();
        
        log.info("로고가 있는 회사 수: {}", corporationsWithLogos.size());

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
        
        return "home";
    }
    
    @PostMapping("/api/articles/{articleId}/like")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> toggleLike(@PathVariable Long articleId,
                                                         @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(401).build();
        }
        
        boolean isLiked = userLikeService.toggleLike(articleId, userDetails.getUsername());
        long likeCount = userLikeService.getLikeCount(articleId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("isLiked", isLiked);
        response.put("likeCount", likeCount);
        
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/api/articles/{articleId}/like-status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getLikeStatus(@PathVariable Long articleId,
                                                           @AuthenticationPrincipal UserDetails userDetails) {
        boolean hasLiked = false;
        if (userDetails != null) {
            hasLiked = userLikeService.hasLiked(articleId, userDetails.getUsername());
        }
        
        long likeCount = userLikeService.getLikeCount(articleId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("hasLiked", hasLiked);
        response.put("likeCount", likeCount);
        response.put("authenticated", userDetails != null);
        
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/api/articles/{articleId}/view")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> incrementViewCount(@PathVariable Long articleId,
                                                                @AuthenticationPrincipal UserDetails userDetails,
                                                                HttpServletRequest request) {
        String ipAddress = getClientIpAddress(request);
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
        response.put("viewCount", viewCount);
        response.put("authenticated", userDetails != null);
        
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/api/articles/{articleId}/view-status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getViewStatus(@PathVariable Long articleId,
                                                           @AuthenticationPrincipal UserDetails userDetails,
                                                           HttpServletRequest request) {
        String ipAddress = getClientIpAddress(request);
        long viewCount = viewService.getViewCount(articleId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("viewCount", viewCount);
        response.put("authenticated", userDetails != null);
        
        // 쿨다운 정보 추가
        if (userDetails != null) {
            viewService.getLastViewTime(articleId, userDetails.getUsername())
                .ifPresent(lastViewTime -> {
                    response.put("lastViewTime", lastViewTime);
                    response.put("canView", lastViewTime.plusMinutes(30).isBefore(java.time.LocalDateTime.now()));
                });
        } else {
            viewService.getLastViewTimeByIp(articleId, ipAddress)
                .ifPresent(lastViewTime -> {
                    response.put("lastViewTime", lastViewTime);
                    response.put("canView", lastViewTime.plusMinutes(30).isBefore(java.time.LocalDateTime.now()));
                });
        }
        
        return ResponseEntity.ok(response);
    }
    
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
        try {
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
        } catch (IllegalArgumentException e) {
            return "redirect:/";
        }
    }
}