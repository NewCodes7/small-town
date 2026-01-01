package com.newcodes7.small_town.theme.controller;

import com.newcodes7.small_town.theme.dto.*;
import com.newcodes7.small_town.theme.entity.Theme;
import com.newcodes7.small_town.theme.entity.ThemeArticle;
import com.newcodes7.small_town.theme.entity.ThemeVideo;
import com.newcodes7.small_town.theme.service.ThemeService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 어드민 테마 관리 컨트롤러
 */
@RestController
@RequestMapping("/api/admin/themes")
@RequiredArgsConstructor
public class AdminThemeController {

    private final ThemeService themeService;

    /**
     * 전체 테마 목록 조회 (비활성화 포함)
     */
    @GetMapping
    public ResponseEntity<List<ThemeResponseDto>> getAllThemes(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (!isAdmin(userDetails)) {
            return ResponseEntity.status(403).build();
        }

        List<ThemeResponseDto> themes = themeService.getAllThemes();
        return ResponseEntity.ok(themes);
    }

    /**
     * 특정 테마 상세 조회
     */
    @GetMapping("/{themeId}")
    public ResponseEntity<ThemeResponseDto> getTheme(
            @PathVariable Long themeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (!isAdmin(userDetails)) {
            return ResponseEntity.status(403).build();
        }

        ThemeResponseDto theme = themeService.getThemeById(themeId);
        return ResponseEntity.ok(theme);
    }

    /**
     * 테마 생성
     */
    @PostMapping
    public ResponseEntity<ThemeSimpleResponseDto> createTheme(
            @RequestBody @Valid ThemeCreateRequestDto dto,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (!isAdmin(userDetails)) {
            return ResponseEntity.status(403).build();
        }

        Theme theme = themeService.createTheme(dto);
        ThemeSimpleResponseDto response = new ThemeSimpleResponseDto(theme);
        return ResponseEntity.ok(response);
    }

    /**
     * 테마 수정
     */
    @PutMapping("/{themeId}")
    public ResponseEntity<ThemeSimpleResponseDto> updateTheme(
            @PathVariable Long themeId,
            @RequestBody @Valid ThemeUpdateRequestDto dto,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (!isAdmin(userDetails)) {
            return ResponseEntity.status(403).build();
        }

        Theme theme = themeService.updateTheme(themeId, dto);
        ThemeSimpleResponseDto response = new ThemeSimpleResponseDto(theme);
        return ResponseEntity.ok(response);
    }

    /**
     * 테마 삭제 (Soft Delete)
     */
    @DeleteMapping("/{themeId}")
    public ResponseEntity<Void> deleteTheme(
            @PathVariable Long themeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (!isAdmin(userDetails)) {
            return ResponseEntity.status(403).build();
        }

        themeService.deleteTheme(themeId);
        return ResponseEntity.ok().build();
    }

    /**
     * 테마에 아티클 추가
     */
    @PostMapping("/{themeId}/articles")
    public ResponseEntity<ThemeArticleResponseDto> addArticleToTheme(
            @PathVariable Long themeId,
            @RequestBody @Valid ThemeArticleAddRequestDto dto,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (!isAdmin(userDetails)) {
            return ResponseEntity.status(403).build();
        }

        ThemeArticle themeArticle = themeService.addArticleToTheme(themeId, dto);
        ThemeArticleResponseDto response = new ThemeArticleResponseDto(themeArticle);
        return ResponseEntity.ok(response);
    }

    /**
     * 테마에서 아티클 제거
     */
    @DeleteMapping("/{themeId}/articles/{articleId}")
    public ResponseEntity<Void> removeArticleFromTheme(
            @PathVariable Long themeId,
            @PathVariable Long articleId,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (!isAdmin(userDetails)) {
            return ResponseEntity.status(403).build();
        }

        themeService.removeArticleFromTheme(themeId, articleId);
        return ResponseEntity.ok().build();
    }

    /**
     * 테마에 비디오 추가
     */
    @PostMapping("/{themeId}/videos")
    public ResponseEntity<ThemeVideoResponseDto> addVideoToTheme(
            @PathVariable Long themeId,
            @RequestBody @Valid ThemeVideoAddRequestDto dto,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (!isAdmin(userDetails)) {
            return ResponseEntity.status(403).build();
        }

        ThemeVideo themeVideo = themeService.addVideoToTheme(themeId, dto);
        ThemeVideoResponseDto response = new ThemeVideoResponseDto(themeVideo);
        return ResponseEntity.ok(response);
    }

    /**
     * 테마에서 비디오 제거
     */
    @DeleteMapping("/{themeId}/videos/{videoId}")
    public ResponseEntity<Void> removeVideoFromTheme(
            @PathVariable Long themeId,
            @PathVariable Long videoId,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (!isAdmin(userDetails)) {
            return ResponseEntity.status(403).build();
        }

        themeService.removeVideoFromTheme(themeId, videoId);
        return ResponseEntity.ok().build();
    }

    /**
     * 테마 내 컨텐츠 순서 변경
     * Request body: ["article:123", "video:456", "article:789", ...]
     */
    @PutMapping("/{themeId}/contents/reorder")
    public ResponseEntity<Void> reorderContents(
            @PathVariable Long themeId,
            @RequestBody List<String> contentIds,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (!isAdmin(userDetails)) {
            return ResponseEntity.status(403).build();
        }

        themeService.reorderContentsInTheme(themeId, contentIds);
        return ResponseEntity.ok().build();
    }

    /**
     * 어드민 권한 확인
     */
    private boolean isAdmin(UserDetails userDetails) {
        return userDetails != null && userDetails.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }
}
