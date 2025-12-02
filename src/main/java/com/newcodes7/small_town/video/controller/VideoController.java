package com.newcodes7.small_town.video.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.corporation.service.CorporationService;
import com.newcodes7.small_town.crawler.repository.CategoryRepository;
import com.newcodes7.small_town.global.entity.Category;
import com.newcodes7.small_town.video.dto.VideoResponseDto;
import com.newcodes7.small_town.video.repository.VideoTermRepository;
import com.newcodes7.small_town.video.service.VideoService;
import com.newcodes7.small_town.video.service.VideoViewService;
import com.newcodes7.small_town.global.util.Client;
import com.newcodes7.small_town.global.util.KoreanCharacterUtil;
import com.newcodes7.small_town.global.entity.SearchLog;
import com.newcodes7.small_town.global.service.SearchLogService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Controller
@RequestMapping("/video")
@RequiredArgsConstructor
@Slf4j
public class VideoController {

    private final VideoService videoService;
    private final VideoViewService videoViewService;
    private final CorporationService corporationService;
    private final CategoryRepository categoryRepository;
    private final VideoTermRepository videoTermRepository;
    private final SearchLogService searchLogService;

    @GetMapping({"", "/"})
    public String videoHome(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "sort", defaultValue = "latest") String sort,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "regions", required = false) List<String> regions,
            @RequestParam(name = "view", defaultValue = "grouped") String view,
            @RequestParam(name = "category", required = false) List<String> category,
            HttpServletRequest request,
            Model model) {

        // 검색 로그 저장
        if (keyword != null && !keyword.trim().isEmpty()) {
            searchLogService.logSearch(keyword.trim(), SearchLog.SearchType.VIDEO, request);
        }

        // 검색어가 있을 때는 list view로 강제 변경
        String effectiveView = view;
        if (keyword != null && !keyword.trim().isEmpty()) {
            effectiveView = "list";
        }

        Page<VideoResponseDto> videos = videoService.getVideosWithFilters(
            keyword != null && !keyword.trim().isEmpty() ? keyword.trim().toLowerCase() : null,
            regions == null ? null : regions.stream().sorted().toList(),
            page,
            size,
            sort,
            effectiveView,
            category == null ? null : category.stream().sorted().toList()
        );

        log.info("필터 조건: keyword='{}', regions={}, {}개의 영상 조회",
                 keyword, regions, videos.getTotalElements());

        // 회사 목록 가져오기 (YouTube 채널이 있는 회사만)
        Page<CorporationResponseDto> corporations = corporationService.getCorporationsWithFilters(null, null, null, PageRequest.of(0, 50));
        List<CorporationResponseDto> corporationsWithYouTube = corporations.getContent().stream()
            .filter(corp -> corp.getYoutubeUrl() != null && !corp.getYoutubeUrl().isEmpty())
            .limit(20)
            .toList();

        // 카테고리 목록 가져오기
        List<Category> categories = categoryRepository.findAll();

        log.info("YouTube 채널이 있는 회사 수: {}", corporationsWithYouTube.size());

        model.addAttribute("videos", videos);
        model.addAttribute("totalPages", videos.getTotalPages());
        model.addAttribute("totalElements", videos.getTotalElements());
        model.addAttribute("hasNext", videos.hasNext());
        model.addAttribute("hasPrevious", videos.hasPrevious());
        model.addAttribute("currentPage", page);
        model.addAttribute("currentSort", sort);
        model.addAttribute("keyword", keyword);
        model.addAttribute("selectedRegions", regions != null ? regions : new ArrayList<>());
        model.addAttribute("corporations", corporationsWithYouTube);
        model.addAttribute("isGrouped", effectiveView.equals("grouped"));
        model.addAttribute("categories", categories);

        return "video";
    }

    /**
     * 영상 조회수 증가 API
     */
    @PostMapping("/api/videos/{videoId}/view")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> incrementViewCount(@PathVariable Long videoId,
                                                                @AuthenticationPrincipal UserDetails userDetails,
                                                                HttpServletRequest request) {
        String ipAddress = Client.getClientIpAddress(request);
        boolean incremented;

        if (userDetails != null) {
            // 인증된 사용자
            incremented = videoViewService.incrementViewCount(videoId, userDetails.getUsername(), ipAddress);
        } else {
            // 익명 사용자 (IP 기반)
            incremented = videoViewService.incrementViewCountByIp(videoId, ipAddress);
        }

        long viewCount = videoViewService.getViewCount(videoId);

        Map<String, Object> response = new HashMap<>();
        response.put("incremented", incremented);
        response.put("viewCount", viewCount);
        response.put("authenticated", userDetails != null);

        return ResponseEntity.ok(response);
    }

    /**
     * 관리자용 영상 삭제 API
     */
    @DeleteMapping("/api/admin/videos/{videoId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteVideo(
            @PathVariable Long videoId,
            @AuthenticationPrincipal UserDetails userDetails) {

        Map<String, Object> response = new HashMap<>();

        try {
            // 관리자 권한 확인
            if (userDetails == null || !isAdmin(userDetails)) {
                response.put("status", "error");
                response.put("message", "관리자 권한이 필요합니다.");
                return ResponseEntity.status(403).body(response);
            }

            // 영상 삭제
            videoService.deleteVideo(videoId);

            response.put("status", "success");
            response.put("message", "영상이 성공적으로 삭제되었습니다.");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("영상 삭제 중 오류 발생: {}", e.getMessage(), e);

            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 자동완성 검색어 API
     * 사용자 입력값으로 시작하는 term과 corporation을 빈도수 순으로 반환
     *
     * GET /video/api/autocomplete?q=검색어
     */
    @GetMapping("/api/autocomplete")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getAutocompleteSuggestions(
            @RequestParam(name = "q", required = false) String query) {

        List<Map<String, Object>> suggestions = new ArrayList<>();

        if (query == null || query.trim().isEmpty() || query.trim().length() < 1) {
            return ResponseEntity.ok(suggestions);
        }

        String trimmedQuery = query.trim().toLowerCase();

        // 검색어를 자모 분해 (예: "프로제" → "ㅍㅡㄹㅗㅈㅔ", "톳" → "ㅌㅗㅅ")
        String decomposedQuery = KoreanCharacterUtil.decomposeHangul(trimmedQuery);

        // 기업 검색 (비디오가 있는 기업만, 최대 3개)
        // 원본 검색어와 자모 분해 검색어 둘 다 검색하고 중복 제거
        Set<Long> corporationIds = new LinkedHashSet<>();
        List<com.newcodes7.small_town.global.entity.Corporation> allCorporations = new ArrayList<>();

        // 1. 원본 검색어로 검색
        List<com.newcodes7.small_town.global.entity.Corporation> corporations1 =
                corporationService.searchCorporationsWithVideos(trimmedQuery, 3);
        for (com.newcodes7.small_town.global.entity.Corporation corp : corporations1) {
            if (corporationIds.add(corp.getId())) {
                allCorporations.add(corp);
            }
        }

        // 2. 자모 분해 검색어로 검색 (한글인 경우만)
        if (!trimmedQuery.equals(decomposedQuery)) {
            List<com.newcodes7.small_town.global.entity.Corporation> corporations2 =
                    corporationService.searchCorporationsWithVideos(decomposedQuery, 3);
            for (com.newcodes7.small_town.global.entity.Corporation corp : corporations2) {
                if (corporationIds.add(corp.getId()) && allCorporations.size() < 3) {
                    allCorporations.add(corp);
                }
            }
        }

        // 최대 3개만 사용
        List<com.newcodes7.small_town.global.entity.Corporation> corporations =
                allCorporations.stream().limit(3).collect(Collectors.toList());

        for (com.newcodes7.small_town.global.entity.Corporation corporation : corporations) {
            Map<String, Object> item = new HashMap<>();
            item.put("type", "corporation");

            // 검색어와 매칭된 이름을 표시 (대체명 우선)
            String displayName = corporation.getName();
            if (corporation.getAlternateName() != null &&
                !corporation.getAlternateName().isEmpty() &&
                corporation.getDecomposedAlternateName().toLowerCase().startsWith(decomposedQuery)) {
                displayName = corporation.getAlternateName();
            }

            item.put("name", displayName);
            item.put("id", corporation.getId());
            item.put("logoUrl", corporation.getEffectiveLogoUrl());
            suggestions.add(item);
        }

        // 한글 검색 패턴 생성 (예: "프롲" → ["프롲", "프로ㅈ"])
        List<String> searchPatterns = KoreanCharacterUtil.generateSearchPatterns(trimmedQuery);

        // Term 검색 (최대 6개)
        // 원본 검색어와 자모 분해된 검색어 둘 다 사용
        PageRequest termPageRequest = PageRequest.of(0, 6);
        List<VideoTermRepository.AutocompleteSuggestion> videoTermSuggestions;

        if (searchPatterns.size() == 2) {
            // 종성이 있는 경우: 원본, 종성분리, 자모분해 3가지 패턴
            // 예: "프롲" → ["프롲", "프로ㅈ", "ㅍㅡㄹㅗㅈ"]
            videoTermSuggestions = videoTermRepository.findAutocompleteTermsWithPatterns(
                searchPatterns.get(0), decomposedQuery, termPageRequest);
        } else {
            // 종성이 없는 경우: 원본, 자모분해 2가지 패턴
            // 예: "프로제" → ["프로제", "ㅍㅡㄹㅗㅈㅔ"]
            videoTermSuggestions = videoTermRepository.findAutocompleteTermsWithPatterns(
                trimmedQuery, decomposedQuery, termPageRequest);
        }

        for (VideoTermRepository.AutocompleteSuggestion termSuggestion : videoTermSuggestions) {
            Map<String, Object> item = new HashMap<>();
            item.put("type", "term");
            item.put("term", termSuggestion.getTerm());
            item.put("frequency", termSuggestion.getTotalFrequency());
            suggestions.add(item);
        }

        return ResponseEntity.ok(suggestions);
    }

    private boolean isAdmin(UserDetails userDetails) {
        return userDetails.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }
}
