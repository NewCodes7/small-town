package com.newcodes7.small_town.corporation.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.corporation.service.CorporationService;
import com.newcodes7.small_town.corporation.service.CorporationViewService;
import com.newcodes7.small_town.global.util.Client;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/corporations")
@RequiredArgsConstructor
public class CorporationApiController {

    private final CorporationService corporationService;
    private final CorporationViewService corporationViewService;
    
    // 기업 목록 조회
    @GetMapping
    public ResponseEntity<Page<CorporationResponseDto>> getCorporations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search) {
        
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        // 통합 필터링 메서드 사용
        Page<CorporationResponseDto> corporations = corporationService.getCorporationsWithFilters(search, null, null, pageable);

        return ResponseEntity.ok(corporations);
    }
    
    // 기업 상세 조회
    @GetMapping("/{id}")
    public ResponseEntity<CorporationResponseDto> getCorporation(@PathVariable Long id) {
        CorporationResponseDto corporation = corporationService.getCorporationById(id);
        return ResponseEntity.ok(corporation);
    }

    /**
     * 기업 페이지 조회수 증가 API
     */
    @PostMapping("/{id}/view")
    public ResponseEntity<Map<String, Object>> incrementViewCount(@PathVariable Long id,
                                                                   @AuthenticationPrincipal UserDetails userDetails,
                                                                   HttpServletRequest request) {
        String ipAddress = Client.getClientIpAddress(request);
        boolean incremented;

        if (userDetails != null) {
            // 인증된 사용자
            incremented = corporationViewService.incrementViewCount(id, userDetails.getUsername(), ipAddress);
        } else {
            // 익명 사용자 (IP 기반)
            incremented = corporationViewService.incrementViewCountByIp(id, ipAddress);
        }

        long viewCount = corporationViewService.getViewCount(id);

        Map<String, Object> response = new HashMap<>();
        response.put("incremented", incremented);
        response.put("authenticated", userDetails != null);

        return ResponseEntity.ok(response);
    }
}