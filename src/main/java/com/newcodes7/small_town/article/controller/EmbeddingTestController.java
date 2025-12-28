package com.newcodes7.small_town.article.controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.newcodes7.small_town.article.service.ArticleEmbeddingService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Embedding 유사도 테스트 Controller
 * 하드코딩된 블로그 제목들로 임베딩 성능을 테스트합니다.
 */
@RestController
@RequestMapping("/api/embedding-test")
@RequiredArgsConstructor
@Slf4j
public class EmbeddingTestController {

    private final ArticleEmbeddingService embeddingService;

    /**
     * 유사도 테스트 실행
     *
     * @return 테스트 결과 (제목 쌍별 유사도 점수)
     */
    @GetMapping("/similarity")
    public ResponseEntity<Map<String, Object>> testSimilarity() {
        log.info("임베딩 유사도 테스트 시작");

        // 테스트용 블로그 제목 그룹
        List<String> springTitles = Arrays.asList(
                "Spring Boot 성능 최적화 방법",
                "스프링 부트 애플리케이션 튜닝 가이드",
                "Spring Framework 성능 개선 팁"
        );

        List<String> aiTitles = Arrays.asList(
                "머신러닝 모델 학습 과정",
                "딥러닝 모델 트레이닝 방법",
                "AI 모델 최적화 기법"
        );

        List<String> frontendTitles = Arrays.asList(
                "React 컴포넌트 최적화",
                "Vue.js 성능 튜닝",
                "JavaScript 번들 사이즈 줄이기"
        );

        List<String> otherTitles = Arrays.asList(
                "회사 워크샵 후기",
                "신입 개발자 채용 공고",
                "사무실 이전 안내"
        );

        // 모든 제목 임베딩 생성
        Map<String, float[]> embeddings = new HashMap<>();
        List<String> allTitles = new ArrayList<>();
        allTitles.addAll(springTitles);
        allTitles.addAll(aiTitles);
        allTitles.addAll(frontendTitles);
        allTitles.addAll(otherTitles);

        log.info("총 {}개 제목 임베딩 생성 시작", allTitles.size());
        long startTime = System.currentTimeMillis();

        for (String title : allTitles) {
            float[] embedding = embeddingService.generateEmbedding(title);
            if (embedding != null) {
                embeddings.put(title, embedding);
            }

            // Rate limiting (500ms 딜레이)
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        long embeddingDuration = System.currentTimeMillis() - startTime;
        log.info("임베딩 생성 완료 - 소요시간: {}ms", embeddingDuration);

        // 유사도 테스트 케이스 생성
        List<Map<String, Object>> testCases = new ArrayList<>();

        // 1. 같은 그룹 내 유사도 (높아야 함)
        testCases.addAll(createTestCases(springTitles, embeddings, "Spring 그룹 내"));
        testCases.addAll(createTestCases(aiTitles, embeddings, "AI 그룹 내"));

        // 2. 다른 그룹 간 유사도 (낮아야 함)
        testCases.add(createTestCase(
                springTitles.get(0),
                aiTitles.get(0),
                embeddings,
                "Spring vs AI"
        ));
        testCases.add(createTestCase(
                springTitles.get(0),
                frontendTitles.get(0),
                embeddings,
                "Spring vs Frontend"
        ));
        testCases.add(createTestCase(
                aiTitles.get(0),
                frontendTitles.get(0),
                embeddings,
                "AI vs Frontend"
        ));

        // 3. 기술 vs 비기술 (매우 낮아야 함)
        testCases.add(createTestCase(
                springTitles.get(0),
                otherTitles.get(0),
                embeddings,
                "기술 vs 비기술"
        ));

        // 통계 계산
        double avgSameGroup = calculateAverageSimilarity(testCases, "그룹 내");
        double avgDifferentGroup = calculateAverageSimilarity(testCases, "vs");

        Map<String, Object> response = new HashMap<>();
        response.put("test_cases", testCases);
        response.put("summary", Map.of(
                "total_comparisons", testCases.size(),
                "avg_similarity_same_group", Math.round(avgSameGroup * 1000.0) / 1000.0,
                "avg_similarity_different_group", Math.round(avgDifferentGroup * 1000.0) / 1000.0,
                "total_embeddings", embeddings.size(),
                "embedding_duration_ms", embeddingDuration
        ));

        log.info("유사도 테스트 완료 - 케이스 수: {}", testCases.size());

        return ResponseEntity.ok(response);
    }

    /**
     * 같은 그룹 내 모든 쌍에 대한 테스트 케이스 생성
     */
    private List<Map<String, Object>> createTestCases(List<String> titles, Map<String, float[]> embeddings, String category) {
        List<Map<String, Object>> cases = new ArrayList<>();

        for (int i = 0; i < titles.size(); i++) {
            for (int j = i + 1; j < titles.size(); j++) {
                cases.add(createTestCase(titles.get(i), titles.get(j), embeddings, category));
            }
        }

        return cases;
    }

    /**
     * 단일 테스트 케이스 생성
     */
    private Map<String, Object> createTestCase(String title1, String title2, Map<String, float[]> embeddings, String category) {
        float[] vec1 = embeddings.get(title1);
        float[] vec2 = embeddings.get(title2);

        double similarity = 0.0;
        if (vec1 != null && vec2 != null) {
            similarity = embeddingService.computeCosineSimilarity(vec1, vec2);
        }

        Map<String, Object> testCase = new HashMap<>();
        testCase.put("category", category);
        testCase.put("title1", title1);
        testCase.put("title2", title2);
        testCase.put("similarity", Math.round(similarity * 1000.0) / 1000.0);  // 소수점 3자리
        testCase.put("interpretation", embeddingService.interpretSimilarity(similarity));

        return testCase;
    }

    /**
     * 한영 혼용 유사도 테스트
     * 동일한 기술 용어를 한국어와 영어로 표현했을 때의 유사도를 측정합니다.
     *
     * @return 한영 쌍별 유사도 점수
     */
    @GetMapping("/korean-english")
    public ResponseEntity<Map<String, Object>> testKoreanEnglishSimilarity() {
        log.info("한영 혼용 유사도 테스트 시작");

        // 한영 쌍 정의
        Map<String, String> koreanEnglishPairs = new HashMap<>();
        koreanEnglishPairs.put("스프링부트", "Spring Boot");
        koreanEnglishPairs.put("머신러닝", "Machine Learning");
        koreanEnglishPairs.put("딥러닝", "Deep Learning");
        koreanEnglishPairs.put("리액트", "React");
        koreanEnglishPairs.put("쿠버네티스", "Kubernetes");
        koreanEnglishPairs.put("데이터베이스", "Database");
        koreanEnglishPairs.put("인공지능", "Artificial Intelligence");
        koreanEnglishPairs.put("마이크로서비스", "Microservices");

        // 모든 단어 임베딩 생성
        Map<String, float[]> embeddings = new HashMap<>();
        List<String> allWords = new ArrayList<>();
        allWords.addAll(koreanEnglishPairs.keySet());
        allWords.addAll(koreanEnglishPairs.values());

        log.info("총 {}개 단어 임베딩 생성 시작", allWords.size());
        long startTime = System.currentTimeMillis();

        for (String word : allWords) {
            float[] embedding = embeddingService.generateEmbedding(word);
            if (embedding != null) {
                embeddings.put(word, embedding);
            }

            // Rate limiting (500ms 딜레이)
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        long embeddingDuration = System.currentTimeMillis() - startTime;
        log.info("임베딩 생성 완료 - 소요시간: {}ms", embeddingDuration);

        // 한영 쌍별 유사도 계산
        List<Map<String, Object>> testCases = new ArrayList<>();
        double totalSimilarity = 0.0;
        int validPairs = 0;

        for (Map.Entry<String, String> entry : koreanEnglishPairs.entrySet()) {
            String korean = entry.getKey();
            String english = entry.getValue();

            float[] koreanVec = embeddings.get(korean);
            float[] englishVec = embeddings.get(english);

            if (koreanVec != null && englishVec != null) {
                double similarity = embeddingService.computeCosineSimilarity(koreanVec, englishVec);
                totalSimilarity += similarity;
                validPairs++;

                Map<String, Object> testCase = new HashMap<>();
                testCase.put("korean", korean);
                testCase.put("english", english);
                testCase.put("similarity", Math.round(similarity * 1000.0) / 1000.0);
                testCase.put("interpretation", embeddingService.interpretSimilarity(similarity));

                testCases.add(testCase);
            }
        }

        double avgSimilarity = validPairs > 0 ? totalSimilarity / validPairs : 0.0;

        Map<String, Object> response = new HashMap<>();
        response.put("test_cases", testCases);
        response.put("summary", Map.of(
                "total_pairs", koreanEnglishPairs.size(),
                "successful_pairs", validPairs,
                "avg_similarity", Math.round(avgSimilarity * 1000.0) / 1000.0,
                "embedding_duration_ms", embeddingDuration
        ));

        log.info("한영 유사도 테스트 완료 - 평균 유사도: {}", avgSimilarity);

        return ResponseEntity.ok(response);
    }

    /**
     * 특정 카테고리의 평균 유사도 계산
     */
    private double calculateAverageSimilarity(List<Map<String, Object>> testCases, String categoryKeyword) {
        return testCases.stream()
                .filter(tc -> tc.get("category").toString().contains(categoryKeyword))
                .mapToDouble(tc -> (Double) tc.get("similarity"))
                .average()
                .orElse(0.0);
    }
}
