package com.newcodes7.small_town.theme.controller;

import com.newcodes7.small_town.theme.dto.ThemeResponseDto;
import com.newcodes7.small_town.theme.service.ThemeService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

/**
 * 공개 테마 컨트롤러
 */
@Controller
@RequiredArgsConstructor
public class ThemeController {

    private final ThemeService themeService;

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
    public String themeDetail(@PathVariable Long themeId, Model model) {
        ThemeResponseDto theme = themeService.getThemeById(themeId);
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
