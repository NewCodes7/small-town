package com.newcodes7.small_town.admin.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.newcodes7.small_town.search.service.SearchWeightConfigService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/admin/search/weights")
@RequiredArgsConstructor
@Slf4j
public class AdminSearchWeightController {

    private static final Set<String> VALID_COMPLEXITIES = Set.of("SIMPLE", "MODERATE", "COMPLEX");

    private final SearchWeightConfigService searchWeightConfigService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllWeights() {
        Map<String, Object> response = new HashMap<>();
        try {
            response.put("success", true);
            response.put("weights", searchWeightConfigService.getAllWeights());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[검색 가중치] 조회 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "조회 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PutMapping("/{complexity}")
    public ResponseEntity<Map<String, Object>> updateWeights(
            @PathVariable String complexity,
            @RequestBody UpdateWeightsRequest request) {

        Map<String, Object> response = new HashMap<>();

        if (!VALID_COMPLEXITIES.contains(complexity.toUpperCase())) {
            response.put("success", false);
            response.put("message", "유효하지 않은 complexity: " + complexity + ". 허용 값: SIMPLE, MODERATE, COMPLEX");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            searchWeightConfigService.updateWeights(
                complexity.toUpperCase(),
                request.titleMultiplier(),
                request.bm25NsfWeight(),
                request.vectorNsfWeight(),
                "admin"
            );
            response.put("success", true);
            response.put("complexity", complexity.toUpperCase());
            response.put("titleMultiplier", request.titleMultiplier());
            response.put("bm25NsfWeight", request.bm25NsfWeight());
            response.put("vectorNsfWeight", request.vectorNsfWeight());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("[검색 가중치] 업데이트 중 오류 발생 - complexity: {}", complexity, e);
            response.put("success", false);
            response.put("message", "업데이트 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    public record UpdateWeightsRequest(
        double titleMultiplier,
        double bm25NsfWeight,
        double vectorNsfWeight
    ) {}
}
