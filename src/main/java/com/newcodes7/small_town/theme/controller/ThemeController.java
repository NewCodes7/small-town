package com.newcodes7.small_town.theme.controller;

import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.global.entity.SearchLog;
import com.newcodes7.small_town.global.service.SearchLogService;
import com.newcodes7.small_town.theme.dto.ThemeResponseDto;
import com.newcodes7.small_town.theme.service.ThemeService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

/**
 * 공개 테마 컨트롤러
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ThemeController {

    private final ThemeService themeService;
    private final SearchLogService searchLogService;
    private final com.newcodes7.small_town.auth.repository.UserRepository userRepository;

    /**
     * 테마 목록 페이지
     */
    @GetMapping("/themes")
    public String themes(Model model) {
        List<ThemeResponseDto> themes = themeService.getActiveThemes();
        model.addAttribute("themes", themes);
        return "themes";
    }

    /**
     * 특정 테마 상세 페이지
     */
    @GetMapping("/themes/{themeId}")
    public String themeDetail(@PathVariable Long themeId,
                              @RequestParam(required = false) String keyword,
                              @AuthenticationPrincipal UserDetails userDetails,
                              HttpServletRequest request,
                              Model model) {
        ThemeResponseDto theme = themeService.getThemeById(themeId);

        // 검색 로그 저장 (keyword 파라미터가 있고 로그인한 경우)
        if (keyword != null && !keyword.trim().isEmpty() && userDetails != null) {
            User user = userRepository.findByUsernameAndDeletedAtIsNull(userDetails.getUsername()).orElse(null);
            if (user != null) {
                log.info("검색 로그 저장: userId={}, keyword={}, type=THEME, targetId={}",
                    user.getId(), keyword.trim(), themeId);
                searchLogService.logSearch(keyword.trim(), SearchLog.SearchType.THEME, themeId, user, request);
            }
        }

        model.addAttribute("theme", theme);
        return "theme-detail";
    }

    /**
     * API: 활성화된 테마 목록 조회
     */
    @GetMapping("/api/themes")
    @ResponseBody
    public ResponseEntity<List<ThemeResponseDto>> getThemes() {
        List<ThemeResponseDto> themes = themeService.getActiveThemes();
        return ResponseEntity.ok(themes);
    }

    /**
     * API: 특정 테마 상세 조회
     */
    @GetMapping("/api/themes/{themeId}")
    @ResponseBody
    public ResponseEntity<ThemeResponseDto> getTheme(@PathVariable Long themeId) {
        ThemeResponseDto theme = themeService.getThemeById(themeId);
        return ResponseEntity.ok(theme);
    }

    /**
     * 어드민: 테마 목록 페이지
     */
    @GetMapping("/admin/themes")
    public String adminThemes() {
        return "admin-themes";
    }

    /**
     * 어드민: 테마 상세 관리 페이지
     */
    @GetMapping("/admin/themes/{themeId}")
    public String adminThemeDetail(@PathVariable Long themeId, Model model) {
        model.addAttribute("themeId", themeId);
        return "admin-theme-detail";
    }
}
