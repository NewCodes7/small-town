package com.newcodes7.small_town.global.controller;

import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.global.service.SearchLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.newcodes7.small_town.auth.repository.UserRepository;

import java.util.Map;

/**
 * 검색 추적 API 컨트롤러
 */
@Controller
@RequestMapping("/api/search")
@RequiredArgsConstructor
@Slf4j
public class SearchTrackingController {

    private final SearchLogService searchLogService;
    private final UserRepository userRepository;

    /**
     * 검색 결과 클릭 추적
     */
    @PostMapping("/click")
    @ResponseBody
    public ResponseEntity<Map<String, String>> trackClick(
            @RequestBody SearchClickRequest clickRequest,
            @AuthenticationPrincipal UserDetails userDetails,
            HttpServletRequest request) {
        
        try {
            User user = null;
            if (userDetails != null) {
                user = userRepository.findByUsernameAndDeletedAtIsNull(userDetails.getUsername()).orElse(null);
            }
            
            String ipAddress = getClientIp(request);
            
            searchLogService.logSearchClick(
                clickRequest.getSearchLogId(),
                clickRequest.getArticleId(),
                clickRequest.getVideoId(),
                clickRequest.getPosition(),
                clickRequest.getKeyword(),
                user,
                ipAddress
            );
            
            return ResponseEntity.ok(Map.of("status", "success"));
        } catch (Exception e) {
            log.error("검색 클릭 추적 실패", e);
            return ResponseEntity.ok(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * 클라이언트 IP 주소 가져오기
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    /**
     * 검색 클릭 요청 DTO
     */
    @Getter
    @Setter
    public static class SearchClickRequest {
        private Long searchLogId;
        private Long articleId;
        private Long videoId;
        private Integer position;
        private String keyword;
    }
}
