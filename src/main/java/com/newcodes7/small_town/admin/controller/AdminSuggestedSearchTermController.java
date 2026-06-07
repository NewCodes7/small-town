package com.newcodes7.small_town.admin.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.newcodes7.small_town.search.service.SuggestedSearchTermService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/admin/suggested-search-terms")
@RequiredArgsConstructor
@Slf4j
public class AdminSuggestedSearchTermController {

    private final SuggestedSearchTermService suggestedSearchTermService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAll() {
        Map<String, Object> response = new HashMap<>();
        try {
            response.put("success", true);
            response.put("keywords", suggestedSearchTermService.getActiveKeywords());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[추천 검색어] 조회 오류", e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> update(@RequestBody UpdateRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            suggestedSearchTermService.updateKeywords(request.keywords());
            response.put("success", true);
            response.put("keywords", suggestedSearchTermService.getActiveKeywords());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[추천 검색어] 업데이트 오류", e);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    public record UpdateRequest(List<String> keywords) {}
}
