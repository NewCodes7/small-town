package com.newcodes7.small_town.admin.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcodes7.small_town.corporation.dto.BlogQueueResponseDto;
import com.newcodes7.small_town.corporation.dto.CorporationCreateDto;
import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.corporation.repository.IndustryRepository;
import com.newcodes7.small_town.corporation.service.FileUploadService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin/blog-queue")
@RequiredArgsConstructor
@Slf4j
public class AdminBlogQueueController {

    private final BlogAnalysisService blogAnalysisService;
    private final FileUploadService fileUploadService;
    private final IndustryRepository industryRepository;
    private final ObjectMapper objectMapper;

    @GetMapping
    public String queuePage(Model model) {
        model.addAttribute("queueItems", blogAnalysisService.getAllQueueItems());
        model.addAttribute("industries", industryRepository.findAll());
        return "admin/blog-queue/index";
    }

    @PostMapping
    @ResponseBody
    public ResponseEntity<Map<String, Object>> addToQueue(@RequestBody Map<String, Object> req) {
        Map<String, Object> response = new HashMap<>();
        String blogUrl = (String) req.get("blogUrl");
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
            String companyName = (String) req.get("companyName");
            String homeLink = (String) req.get("homeLink");
            String logoUrl = (String) req.get("logoUrl");
            String crewLink = (String) req.get("crewLink");
            String alternateName = (String) req.get("alternateName");
            Integer isDomestic = null;
            Object isDomesticRaw = req.get("isDomestic");
            if (isDomesticRaw instanceof Number n) isDomestic = n.intValue();
            else if (isDomesticRaw instanceof String s) try { isDomestic = Integer.parseInt(s); } catch (Exception ignored) {}
            String blogType = (String) req.get("blogType");
            List<Integer> industryIds = Collections.emptyList();
            Object idsRaw = req.get("industryIds");
            if (idsRaw instanceof List<?> list) {
                industryIds = list.stream()
                        .filter(o -> o instanceof Number)
                        .map(o -> ((Number) o).intValue())
                        .toList();
            }
            CorporationBlogQueue item = blogAnalysisService.addToQueue(blogUrl, companyName,
                    homeLink, logoUrl, crewLink, alternateName, isDomestic, blogType, industryIds);
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

    @PostMapping("/upload-logo")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> uploadLogo(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false, defaultValue = "unknown") String name) {
        Map<String, Object> response = new HashMap<>();
        try {
            String logoUrl = fileUploadService.saveLogoFileToS3(file, name);
            response.put("success", true);
            response.put("logoUrl", logoUrl);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("로고 업로드 실패: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "로고 업로드 실패: " + e.getMessage());
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
