package com.newcodes7.small_town.admin.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.newcodes7.small_town.admin.service.AdminCategoryService;
import com.newcodes7.small_town.crawler.repository.CategoryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Category 관리 Controller
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminCategoryController {

    private final CategoryRepository categoryRepository;
    private final AdminCategoryService adminCategoryService;

    /**
     * 사용 가능한 카테고리 목록 조회 API
     */
    @GetMapping("/categories")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getCategories() {
        Map<String, Object> response = new HashMap<>();

        try {
            response.put("success", true);
            response.put("categories", categoryRepository.findAll());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "카테고리 목록 조회 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 카테고리 삭제 API
     * 카테고리를 삭제하면 해당 카테고리를 가진 모든 Article과 Video의 category가 null로 설정됩니다.
     */
    @DeleteMapping("/categories/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteCategory(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        try {
            // Service 계층에서 비즈니스 로직 처리
            AdminCategoryService.CategoryDeleteResult result =
                    adminCategoryService.deleteCategory(id);

            response.put("success", true);
            response.put("message", String.format("카테고리 '%s'가 성공적으로 삭제되었습니다. (Article %d개, Video %d개의 카테고리가 제거됨)",
                    result.getCategoryName(),
                    result.getAffectedArticleCount(),
                    result.getAffectedVideoCount()));
            response.put("deletedCategoryName", result.getCategoryName());
            response.put("affectedArticleCount", result.getAffectedArticleCount());
            response.put("affectedVideoCount", result.getAffectedVideoCount());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("카테고리 삭제 실패 - ID: {}, 사유: {}", id, e.getMessage());
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);

        } catch (Exception e) {
            log.error("카테고리 삭제 중 오류 발생 - ID: {}, 오류: {}", id, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "카테고리 삭제 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
