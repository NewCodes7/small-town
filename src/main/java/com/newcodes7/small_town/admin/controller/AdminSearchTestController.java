package com.newcodes7.small_town.admin.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.search.service.VectorSearchService;
import com.newcodes7.small_town.global.entity.Article;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 하이브리드 검색 테스트 Controller
 * 검색어에 대한 상세한 매칭 정보와 유사도 점수를 제공합니다.
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminSearchTestController {

    private final ArticleRepository articleRepository;
    private final VectorSearchService vectorSearchService;

    /**
     * 하이브리드 검색 테스트 - 상세 매칭 정보 제공
     *
     * @param keyword 검색 키워드
     * @param limit 최대 결과 수 (기본: 20)
     * @return 검색 결과와 상세 매칭 정보
     */
    @GetMapping("/search/test")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> testHybridSearch(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "20") int limit) {

        Map<String, Object> response = new HashMap<>();

        try {
            log.info("하이브리드 검색 테스트 - 키워드: '{}', limit: {}", keyword, limit);

            // 1. 키워드 검색 수행 (LIKE 검색)
            Set<Long> keywordArticleIds = new HashSet<>();
            List<Map<String, Object>> keywordResults = new ArrayList<>();

            String searchPattern = "%" + keyword + "%";
            List<Article> keywordArticles = articleRepository.findArticlesWithFiltersWithoutTerms(
                searchPattern, null, null);

            for (Article article : keywordArticles) {
                keywordArticleIds.add(article.getId());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("articleId", article.getId());
                result.put("title", article.getTitle() != null ? article.getTitle() : "");
                result.put("translatedTitle", article.getTranslatedTitle() != null ? article.getTranslatedTitle() : "");
                result.put("corporation", article.getCorporation().getName());
                result.put("link", article.getLink() != null ? article.getLink() : "");
                result.put("matchType", "KEYWORD");
                keywordResults.add(result);
            }

            // 2. 벡터 검색 수행
            List<Map<String, Object>> vectorResults = new ArrayList<>();
            Set<Long> vectorArticleIds = new HashSet<>();

            Map<Long, Double> similarityMap = vectorSearchService.searchByKeyword(keyword, 0.5, 50);
            if (!similarityMap.isEmpty()) {
                similarityMap.entrySet().stream()
                        .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                        .forEach(entry -> {
                            Long articleId = entry.getKey();
                            Double similarity = entry.getValue();

                            if (!vectorArticleIds.contains(articleId)) {
                                vectorArticleIds.add(articleId);

                                Article article = articleRepository.findById(articleId).orElse(null);
                                if (article != null) {
                                    Map<String, Object> result = new LinkedHashMap<>();
                                    result.put("articleId", article.getId());
                                    result.put("title", article.getTitle() != null ? article.getTitle() : "");
                                    result.put("translatedTitle", article.getTranslatedTitle() != null ? article.getTranslatedTitle() : "");
                                    result.put("corporation", article.getCorporation().getName());
                                    result.put("link", article.getLink() != null ? article.getLink() : "");
                                    result.put("similarity", String.format("%.4f", similarity));
                                    result.put("matchType", keywordArticleIds.contains(articleId) ? "BOTH" : "VECTOR_ONLY");
                                    vectorResults.add(result);
                                }
                            }
                        });
            }

            // 3. 통합 결과 생성
            Set<Long> allArticleIds = new HashSet<>();
            allArticleIds.addAll(keywordArticleIds);
            allArticleIds.addAll(vectorArticleIds);

            // 통계
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalMatches", allArticleIds.size());
            stats.put("keywordOnly", keywordArticleIds.size() - vectorArticleIds.stream().filter(keywordArticleIds::contains).count());
            stats.put("vectorOnly", vectorArticleIds.size() - keywordArticleIds.stream().filter(vectorArticleIds::contains).count());
            stats.put("both", keywordArticleIds.stream().filter(vectorArticleIds::contains).count());

            // 응답 구성
            response.put("success", true);
            response.put("query", keyword);
            response.put("stats", stats);
            response.put("keywordResults", keywordResults.stream().limit(limit).collect(Collectors.toList()));
            response.put("vectorResults", vectorResults.stream().limit(limit).collect(Collectors.toList()));

            log.info("검색 테스트 완료 - 키워드: {}, 전체: {}, 키워드만: {}, 벡터만: {}, 둘다: {}",
                keyword, allArticleIds.size(), stats.get("keywordOnly"), stats.get("vectorOnly"), stats.get("both"));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("검색 테스트 중 오류 발생 - 키워드: {}", keyword, e);
            response.put("success", false);
            response.put("message", "검색 테스트 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

}
