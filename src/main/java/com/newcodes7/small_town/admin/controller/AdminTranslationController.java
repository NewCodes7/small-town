package com.newcodes7.small_town.admin.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.newcodes7.small_town.crawler.integration.translation.TitleTranslationService;
import com.newcodes7.small_town.crawler.persistence.ArticlePersistenceService;
import com.newcodes7.small_town.video.service.VideoService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Article/Video 번역 관리 Controller
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminTranslationController {

    private final TitleTranslationService titleTranslationService;
    private final ArticlePersistenceService articlePersistenceService;
    private final VideoService videoService;

    // ========== Article 번역 ==========

    /**
     * 해외 기업 글 제목 번역 실행 API
     */
    @GetMapping("/articles/translate-titles")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> translateOverseasArticleTitles() {
        Map<String, Object> response = new HashMap<>();

        try {
            // 비동기로 실행하여 오래 걸리는 작업이 UI를 블로킹하지 않도록 함
            new Thread(() -> {
                try {
                    titleTranslationService.translateAllOverseasArticleTitles();
                } catch (Exception e) {
                    log.error("제목 번역 배치 작업 중 오류 발생", e);
                }
            }).start();

            response.put("success", true);
            response.put("message", "해외 기업 글 제목 번역 작업이 시작되었습니다. 로그를 확인해주세요.");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "번역 작업 시작 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 특정 기업의 글 제목 번역 API
     */
    @GetMapping("/corporations/{corporationId}/translate-titles")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> translateCorporationTitles(@PathVariable Long corporationId) {
        Map<String, Object> response = new HashMap<>();

        try {
            // 비동기로 실행
            new Thread(() -> {
                try {
                    titleTranslationService.translateCorporationArticleTitles(corporationId);
                } catch (Exception e) {
                    log.error("기업 {} 제목 번역 중 오류 발생", corporationId, e);
                }
            }).start();

            response.put("success", true);
            response.put("message", "기업의 글 제목 번역 작업이 시작되었습니다.");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "번역 작업 시작 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 글 번역된 제목 수정 API
     */
    @PutMapping("/articles/{articleId}/translated-title")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateArticleTranslatedTitle(
            @PathVariable Long articleId,
            @RequestBody Map<String, String> request) {

        Map<String, Object> response = new HashMap<>();

        try {
            String translatedTitle = request.get("translatedTitle");

            if (translatedTitle == null) {
                response.put("success", false);
                response.put("message", "번역된 제목이 필요합니다.");
                return ResponseEntity.badRequest().body(response);
            }

            // ArticlePersistenceService를 통해 캐시 무효화와 함께 수정
            articlePersistenceService.updateArticleTranslatedTitle(articleId, translatedTitle);

            response.put("success", true);
            response.put("message", "번역된 제목이 성공적으로 수정되었습니다.");
            response.put("articleId", articleId);
            response.put("translatedTitle", translatedTitle.trim().isEmpty() ? null : translatedTitle.trim());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("번역된 제목 수정 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "번역된 제목 수정 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ========== Video 번역 ==========

    /**
     * 해외 기업 비디오 제목 번역 실행 API
     */
    @GetMapping("/videos/translate-titles")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> translateOverseasVideoTitles() {
        Map<String, Object> response = new HashMap<>();

        try {
            // 비동기로 실행하여 오래 걸리는 작업이 UI를 블로킹하지 않도록 함
            new Thread(() -> {
                try {
                    titleTranslationService.translateAllOverseasVideoTitles();
                } catch (Exception e) {
                    log.error("비디오 제목 번역 배치 작업 중 오류 발생", e);
                }
            }).start();

            response.put("success", true);
            response.put("message", "해외 기업 비디오 제목 번역 작업이 시작되었습니다. 로그를 확인해주세요.");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "번역 작업 시작 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 특정 기업의 비디오 제목 번역 API
     */
    @GetMapping("/corporations/{corporationId}/translate-video-titles")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> translateCorporationVideoTitles(@PathVariable Long corporationId) {
        Map<String, Object> response = new HashMap<>();

        try {
            // 비동기로 실행
            new Thread(() -> {
                try {
                    titleTranslationService.translateCorporationVideoTitles(corporationId);
                } catch (Exception e) {
                    log.error("기업 {} 비디오 제목 번역 중 오류 발생", corporationId, e);
                }
            }).start();

            response.put("success", true);
            response.put("message", "기업의 비디오 제목 번역 작업이 시작되었습니다.");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "번역 작업 시작 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 영상 번역된 제목 수정 API
     */
    @PutMapping("/videos/{videoId}/translated-title")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateVideoTranslatedTitle(
            @PathVariable Long videoId,
            @RequestBody Map<String, String> request) {

        Map<String, Object> response = new HashMap<>();

        try {
            String translatedTitle = request.get("translatedTitle");

            if (translatedTitle == null) {
                response.put("success", false);
                response.put("message", "번역된 제목이 필요합니다.");
                return ResponseEntity.badRequest().body(response);
            }

            // VideoService를 통해 캐시 무효화와 함께 수정
            videoService.updateVideoTranslatedTitle(videoId, translatedTitle);

            response.put("success", true);
            response.put("message", "번역된 제목이 성공적으로 수정되었습니다.");
            response.put("videoId", videoId);
            response.put("translatedTitle", translatedTitle.trim().isEmpty() ? null : translatedTitle.trim());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("번역된 제목 수정 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "번역된 제목 수정 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
