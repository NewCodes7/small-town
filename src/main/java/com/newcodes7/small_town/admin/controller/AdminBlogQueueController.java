package com.newcodes7.small_town.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcodes7.small_town.corporation.dto.BlogQueueResponseDto;
import com.newcodes7.small_town.corporation.dto.CorporationCreateDto;
import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.crawler.entity.CorporationBlogQueue;
import com.newcodes7.small_town.crawler.service.BlogAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import com.newcodes7.small_town.corporation.dto.BlogAnalysisResultDto;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.Map;

@Controller
@RequestMapping("/admin/blog-queue")
@RequiredArgsConstructor
@Slf4j
public class AdminBlogQueueController {

    private final BlogAnalysisService blogAnalysisService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public String queuePage(Model model) {
        model.addAttribute("queueItems", blogAnalysisService.getAllQueueItems());
        return "admin/blog-queue/index";
    }

    @PostMapping
    @ResponseBody
    public ResponseEntity<Map<String, Object>> addToQueue(@RequestBody Map<String, String> req) {
        Map<String, Object> response = new HashMap<>();
        String blogUrl = req.get("blogUrl");
        if (blogUrl == null || blogUrl.isBlank()) {
            response.put("success", false);
            response.put("message", "블로그 URL을 입력해주세요.");
            return ResponseEntity.badRequest().body(response);
        }
        if (!blogUrl.startsWith("http://") && !blogUrl.startsWith("https://")) {
            response.put("success", false);
            response.put("message", "유효한 URL을 입력해주세요 (http:// 또는 https://).");
            return ResponseEntity.badRequest().body(response);
        }
        try {
            String companyName = req.get("companyName");
            String homeLink = req.get("homeLink");
            String logoUrl = req.get("logoUrl");
            String crewLink = req.get("crewLink");
            String alternateName = req.get("alternateName");
            Integer isDomestic = null;
            try { isDomestic = Integer.parseInt(req.get("isDomestic")); } catch (Exception ignored) {}
            String blogType = req.get("blogType");
            CorporationBlogQueue item = blogAnalysisService.addToQueue(blogUrl, companyName,
                    homeLink, logoUrl, crewLink, alternateName, isDomestic, blogType);
            response.put("success", true);
            response.put("id", item.getId());
            response.put("status", item.getStatus());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("대기열 추가 실패: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "대기열 추가 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/{id}/analyze")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> analyzeItem(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        try {
            BlogQueueResponseDto result = blogAnalysisService.analyzeQueueItem(id);
            response.put("success", true);
            response.put("status", result.getStatus());
            response.put("analysisResult", result.getAnalysisResult());
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(409).body(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("AI 분석 실패 id={}: {}", id, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "분석 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PutMapping("/{id}/analysis")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateAnalysis(
            @PathVariable Long id,
            @RequestBody BlogAnalysisResultDto dto) {
        Map<String, Object> response = new HashMap<>();
        try {
            blogAnalysisService.updateAnalysisResult(id, dto);
            response.put("success", true);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("분석 결과 수정 실패 id={}: {}", id, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "수정 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/{id}/register")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> registerCorporation(
            @PathVariable Long id,
            @RequestBody CorporationCreateDto dto) {
        Map<String, Object> response = new HashMap<>();
        try {
            CorporationResponseDto corporation = blogAnalysisService.registerFromQueue(id, dto);
            response.put("success", true);
            response.put("corporationId", corporation.getId());
            response.put("redirectUrl", "/admin/corporations");
            return ResponseEntity.ok(response);
        } catch (IllegalStateException | IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("기업 등록 실패 id={}: {}", id, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "기업 등록 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @DeleteMapping("/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteItem(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        try {
            blogAnalysisService.deleteQueueItem(id);
            response.put("success", true);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("대기열 삭제 실패 id={}: {}", id, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "삭제 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
