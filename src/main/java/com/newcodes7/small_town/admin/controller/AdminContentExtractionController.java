package com.newcodes7.small_town.admin.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.newcodes7.small_town.crawler.service.ArticleContentExtractionService;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.global.entity.Article;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Article 본문 추출 관리 Controller
 * Article의 link URL에서 깨끗한 본문을 추출하여 저장하는 Admin 기능을 제공합니다.
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminContentExtractionController {

    private final ArticleRepository articleRepository;
    private final ArticleContentExtractionService contentExtractionService;

    /**
     * 단일 Article의 본문 추출
     *
     * @param id Article ID
     * @return 추출 결과
     */
    @GetMapping("/articles/{id}/extract-content")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> extractArticleContent(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Article {} 본문 추출 요청", id);

            // Article 존재 확인
            Optional<Article> articleOpt = articleRepository.findById(id);
            if (articleOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Article을 찾을 수 없습니다: " + id);
                return ResponseEntity.badRequest().body(response);
            }

            // 본문 추출 실행
            Map<String, Object> result = contentExtractionService.extractContentForSingleArticle(id);

            // 결과 반환
            response.putAll(result);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Article {} 본문 추출 중 오류 발생", id, e);
            response.put("success", false);
            response.put("message", "본문 추출 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 여러 Article의 본문을 배치로 추출 (최신순)
     *
     * Request Body:
     * {
     *   "corporationId": 123 (사용 안 함 - 하위 호환성 위해 유지),
     *   "withoutContent": true (optional, default: true),
     *   "limit": 50 (optional, default: 50)
     * }
     *
     * NOTE: corporationId는 무시되고 항상 전체 Article에서 최신순으로 추출합니다.
     *
     * @param request 배치 추출 옵션
     * @return 비동기 처리 시작 응답
     */
    @PostMapping("/articles/extract-content-batch")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> extractContentBatch(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();

        try {
            // 파라미터 파싱
            Long corporationId = request.containsKey("corporationId")
                ? ((Number) request.get("corporationId")).longValue()
                : null;
            Boolean withoutContent = request.containsKey("withoutContent")
                ? (Boolean) request.get("withoutContent")
                : true;
            Integer limit = request.containsKey("limit")
                ? ((Number) request.get("limit")).intValue()
                : 50;

            log.info("배치 본문 추출 요청 - corporationId: {}, withoutContent: {}, limit: {}",
                    corporationId, withoutContent, limit);

            // 비동기 실행 (백그라운드 스레드에서 실행)
            final Long finalCorporationId = corporationId;
            final Boolean finalWithoutContent = withoutContent;
            final Integer finalLimit = limit;

            new Thread(() -> {
                try {
                    contentExtractionService.extractContentBatch(
                        finalCorporationId,
                        finalWithoutContent,
                        finalLimit
                    );
                } catch (Exception e) {
                    log.error("배치 본문 추출 백그라운드 실행 중 오류", e);
                }
            }).start();

            // 즉시 응답 반환
            response.put("success", true);
            response.put("message", "배치 본문 추출이 백그라운드에서 시작되었습니다. 로그에서 진행 상황을 확인하세요.");
            response.put("filters", Map.of(
                "corporationId", corporationId != null ? corporationId : "전체",
                "withoutContent", withoutContent,
                "limit", limit
            ));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("배치 본문 추출 요청 처리 중 오류", e);
            response.put("success", false);
            response.put("message", "배치 본문 추출 요청 처리 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Medium 타입 기업의 모든 Article 본문을 배치로 추출
     * 이미 content가 있는 Article도 다시 추출합니다.
     *
     * Request Body:
     * {
     *   "batchSize": 10 (optional, default: 10, 배치 단위 트랜잭션 크기)
     * }
     *
     * @param request 배치 추출 옵션
     * @return 비동기 처리 시작 응답
     */
    @PostMapping("/articles/extract-content-batch/medium")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> extractContentBatchForMedium(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();

        try {
            Integer batchSize = request.containsKey("batchSize")
                ? ((Number) request.get("batchSize")).intValue()
                : 10;

            // 전체 Medium Article 개수 조회
            long totalMediumArticles = articleRepository.countAllMediumArticles();

            log.info("Medium 전체 본문 추출 요청 - 총 {}개, batchSize: {}", totalMediumArticles, batchSize);

            // 비동기 실행 (백그라운드 스레드에서 실행)
            final Integer finalBatchSize = batchSize;

            new Thread(() -> {
                try {
                    contentExtractionService.extractContentBatchForMedium(finalBatchSize);
                } catch (Exception e) {
                    log.error("Medium 배치 본문 추출 백그라운드 실행 중 오류", e);
                }
            }).start();

            // 즉시 응답 반환
            response.put("success", true);
            response.put("message", "Medium 전체 본문 추출이 백그라운드에서 시작되었습니다. 로그에서 진행 상황을 확인하세요.");
            response.put("filters", Map.of(
                "blogType", "MEDIUM",
                "includeExisting", true,
                "totalArticles", totalMediumArticles,
                "batchSize", batchSize
            ));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Medium 배치 본문 추출 요청 처리 중 오류", e);
            response.put("success", false);
            response.put("message", "Medium 배치 본문 추출 요청 처리 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 본문 추출 통계 조회
     *
     * @return 전체/본문 있는/본문 없는 Article 수 및 커버리지
     */
    @GetMapping("/articles/content-stats")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getContentStats() {
        Map<String, Object> response = new HashMap<>();

        try {
            // Article 통계 조회
            long totalArticles = articleRepository.countByDeletedAtIsNull();
            long articlesWithContent = articleRepository.countArticlesWithContent();
            long articlesWithoutContent = articleRepository.countArticlesWithoutContent();

            // Medium 통계 조회
            long mediumArticlesWithoutContent = articleRepository.countMediumArticlesWithoutContent();

            // 커버리지 계산
            double contentCoverage = totalArticles > 0
                ? (double) articlesWithContent / totalArticles * 100
                : 0.0;

            response.put("success", true);
            response.put("totalArticles", totalArticles);
            response.put("articlesWithContent", articlesWithContent);
            response.put("articlesWithoutContent", articlesWithoutContent);
            response.put("contentCoverage", String.format("%.2f%%", contentCoverage));
            response.put("mediumArticlesWithoutContent", mediumArticlesWithoutContent);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("본문 통계 조회 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "통계 조회 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
