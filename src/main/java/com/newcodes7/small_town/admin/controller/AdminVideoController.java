package com.newcodes7.small_town.admin.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.newcodes7.small_town.global.entity.Video;
import com.newcodes7.small_town.video.repository.VideoRepository;
import com.newcodes7.small_town.video.service.VideoService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Video 기본 관리 Controller
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminVideoController {

    private final VideoRepository videoRepository;
    private final VideoService videoService;

    /**
     * 영상 상세 정보 조회 API
     */
    @GetMapping("/videos/{videoId}/detail")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getVideoDetail(@PathVariable Long videoId) {
        Map<String, Object> response = new HashMap<>();

        try {
            Optional<Video> videoOpt = videoRepository.findById(videoId);
            if (videoOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "영상을 찾을 수 없습니다.");
                return ResponseEntity.notFound().build();
            }

            Video video = videoOpt.get();

            // DTO로 변환하여 직렬화 문제 해결
            Map<String, Object> videoData = new HashMap<>();
            videoData.put("id", video.getId());
            videoData.put("title", video.getTitle());
            videoData.put("translatedTitle", video.getTranslatedTitle());
            videoData.put("link", video.getLink());
            videoData.put("thumbnailUrl", video.getThumbnailUrl());
            videoData.put("description", video.getDescription());
            videoData.put("viewCount", video.getViewCount());
            videoData.put("likeCount", video.getLikeCount());
            videoData.put("publishedAt", video.getPublishedAt());
            videoData.put("videoId", video.getVideoId());

            if (video.getCategory() != null) {
                Map<String, Object> categoryData = new HashMap<>();
                categoryData.put("id", video.getCategory().getId());
                categoryData.put("name", video.getCategory().getName());
                videoData.put("category", categoryData);
            }

            response.put("success", true);
            response.put("video", videoData);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "영상 정보 조회 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 영상 기본 정보 수정 API
     */
    @PutMapping("/videos/{videoId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateVideo(
            @PathVariable Long videoId,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> response = new HashMap<>();

        try {
            String title = (String) request.get("title");
            String translatedTitle = (String) request.get("translatedTitle");
            String link = (String) request.get("link");
            String thumbnailUrl = (String) request.get("thumbnailUrl");
            String categoryName = (String) request.get("categoryName");

            // VideoService를 통해 캐시 무효화와 함께 수정
            videoService.updateVideoBasicInfo(
                videoId, title, translatedTitle, link, thumbnailUrl, categoryName);

            response.put("success", true);
            response.put("message", "영상 정보가 성공적으로 수정되었습니다.");
            response.put("videoId", videoId);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "영상 정보 수정 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 영상 카테고리 수정 API
     */
    @PutMapping("/videos/{videoId}/category")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateVideoCategory(
            @PathVariable Long videoId,
            @RequestBody Map<String, String> request) {

        Map<String, Object> response = new HashMap<>();

        try {
            String categoryName = request.get("categoryName");

            if (categoryName == null || categoryName.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "카테고리 이름이 필요합니다.");
                return ResponseEntity.badRequest().body(response);
            }

            videoService.updateVideoCategory(videoId, categoryName.trim());

            response.put("success", true);
            response.put("message", "카테고리가 성공적으로 수정되었습니다.");
            response.put("videoId", videoId);
            response.put("categoryName", categoryName.trim());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "카테고리 수정 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
