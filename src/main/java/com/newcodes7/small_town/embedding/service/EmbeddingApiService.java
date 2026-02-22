package com.newcodes7.small_town.embedding.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcodes7.small_town.embedding.dto.ModelEmbeddingResult;
import com.newcodes7.small_town.embedding.util.TokenCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Naver Clova Embedding v2 서비스
 * Model: clova-embedding-v2
 * Dimension: 1024 (추정치 - 실제 값 확인 필요)
 * 가격: 약 $0.05 / 1M tokens (추정치 - 실제 값 확인 필요)
 *
 * NOTE: API 스펙은 Naver Cloud Platform 공식 문서를 참고하여 업데이트 필요
 * https://api.ncloud-docs.com/docs/clovastudio-embeddingv2
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingApiService implements EmbeddingService {

    @Value("${embedding.api-key}")
    private String apiKey;

    private static final String EMBEDDING_API_URL = "https://clovastudio.stream.ntruss.com/v1/api-tools/embedding/v2";
    private static final String SEGMENTATION_API_URL = "https://clovastudio.stream.ntruss.com/v1/api-tools/segmentation";
    private static final String REQUEST_ID = "cc14b4c4fa4c4506ad8bb98372cb991a";
    private static final String REQUEST_ID_CHUNK = "654fe46f2fa54a45bab0c43fb0fb6b7b";
    
    private static final String MODEL_NAME = "clova-embedding-v2";
    private static final int DIMENSION = 1024;
    private static final double COST_PER_MILLION_TOKENS = 0.013; 

    private final RestTemplate restTemplate;
    private final TokenCounter tokenCounter;
    private final ObjectMapper objectMapper;

    @Override
    public ModelEmbeddingResult generateEmbedding(String text, String wholeDocument) {
        long startTime = System.currentTimeMillis();

        try {
            // 토큰 수 계산
            int tokenUsage = tokenCounter.countTokens(text);

            // API 요청 구성
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + apiKey);
            headers.set("Content-Type", "application/json");
            headers.set("X-NCP-CLOVASTUDIO-REQUEST-ID", REQUEST_ID);

            Map<String, Object> requestBody = Map.of(
                "text", text
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            // API 호출
            ResponseEntity<String> response = restTemplate.exchange(
                    EMBEDDING_API_URL,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            long responseTime = System.currentTimeMillis() - startTime;

            // 응답 파싱
            JsonNode rootNode = objectMapper.readTree(response.getBody());

            JsonNode result = rootNode.get("result");
            if (result == null) {
                result = rootNode;  // fallback: root에 바로 embedding이 있을 수 있음
            }

            JsonNode embeddingNode = result.get("embedding");

            if (embeddingNode == null) {
                throw new RuntimeException("Clova API 응답에 embedding이 없습니다: " + response.getBody());
            }

            float[] embeddingVector = parseEmbeddingVector(embeddingNode);

            // 비용 계산
            double cost = calculateCost(tokenUsage);

            log.info("임베딩 생성 완료: {} tokens, {}ms, ${}",
                     tokenUsage, responseTime, String.format("%.6f", cost));

            return ModelEmbeddingResult.builder()
                    .modelName(getModelName())
                    .dimension(embeddingVector.length)  // 실제 차원 수 사용
                    .responseTimeMs(responseTime)
                    .tokenUsage(tokenUsage)
                    .estimatedCost(cost)
                    .promptCachingUsed(false)
                    .success(true)
                    .embedding(embeddingVector)
                    .build();

        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.error("임베딩 생성 실패: {}", e.getMessage(), e);

            return ModelEmbeddingResult.builder()
                    .modelName(getModelName())
                    .dimension(DIMENSION)
                    .responseTimeMs(responseTime)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    @Override
    public String getModelName() {
        return "clova";
    }

    @Override
    public int getDimension() {
        return DIMENSION;
    }

    @Override
    public double calculateCost(int tokenCount) {
        return (tokenCount / 1_000_000.0) * COST_PER_MILLION_TOKENS;
    }

    /**
     * JsonNode를 float 배열로 변환
     */
    private float[] parseEmbeddingVector(JsonNode node) {
        List<Float> list = new ArrayList<>();

        // node가 배열인 경우
        if (node.isArray()) {
            node.forEach(element -> list.add((float) element.asDouble()));
        } else {
            // node가 객체인 경우 (예: {"vector": [...]})
            JsonNode vectorNode = node.get("vector");
            if (vectorNode != null && vectorNode.isArray()) {
                vectorNode.forEach(element -> list.add((float) element.asDouble()));
            } else {
                throw new RuntimeException("Embedding vector 파싱 실패: 예상치 못한 구조");
            }
        }

        float[] array = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    /**
     * Clova Segmentation API를 사용하여 문단 나누기
     *
     * @param text 문단 나누기를 할 문서 (1~120,000자)
     * @return 나눠진 문단 리스트 (topicSeg를 평탄화한 결과)
     */
    public SegmentationResult segmentDocument(String text) {
        return segmentDocument(text, -100.0, -1, true, 1000, 400);
    }

    /**
     * Clova Segmentation API를 사용하여 문단 나누기 (파라미터 지정)
     *
     * @param text 문단 나누기를 할 문서 (1~120,000자)
     * @param alpha Thresholds 값 (-100 또는 -1.5~1.5, 기본값: 0.0, -100: 모델이 최적값으로 자동 수행)
     * @param segCnt 문단 나누기 수 (-1: 모델이 최적값으로 자동 수행, 1 이상: 지정된 수)
     * @param postProcess 후처리 수행 여부
     * @param postProcessMaxSize 한 문단의 최대 글자 수 (기본값: 1000)
     * @param postProcessMinSize 한 문단의 최소 글자 수 (기본값: 300, -1: 최소 단위로 자동 설정)
     * @return 나눠진 문단 리스트 및 메타데이터
     */
    public SegmentationResult segmentDocument(
            String text,
            double alpha,
            int segCnt,
            boolean postProcess,
            int postProcessMaxSize,
            int postProcessMinSize) {

        long startTime = System.currentTimeMillis();

        try {
            // API 요청 구성
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + apiKey);
            headers.set("Content-Type", "application/json");
            headers.set("X-NCP-CLOVASTUDIO-REQUEST-ID", REQUEST_ID_CHUNK);

            Map<String, Object> requestBody = Map.of(
                "text", text,
                "alpha", alpha,
                "segCnt", segCnt,
                "postProcess", postProcess,
                "postProcessMaxSize", postProcessMaxSize,
                "postProcessMinSize", postProcessMinSize
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            // API 호출
            ResponseEntity<String> response = restTemplate.exchange(
                    SEGMENTATION_API_URL,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            long responseTime = System.currentTimeMillis() - startTime;

            // 응답 파싱
            JsonNode rootNode = objectMapper.readTree(response.getBody());
            JsonNode resultNode = rootNode.get("result");

            if (resultNode == null) {
                throw new RuntimeException("Segmentation API 응답에 result가 없습니다: " + response.getBody());
            }

            JsonNode topicSegNode = resultNode.get("topicSeg");
            JsonNode inputTokensNode = resultNode.get("inputTokens");

            if (topicSegNode == null || !topicSegNode.isArray()) {
                throw new RuntimeException("Segmentation API 응답에 topicSeg가 없거나 배열이 아닙니다");
            }

            // topicSeg를 평탄화: Array<Array<String>> -> List<String>
            List<String> segments = new ArrayList<>();
            for (JsonNode segmentGroup : topicSegNode) {
                if (segmentGroup.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode sentence : segmentGroup) {
                        sb.append(sentence.asText() + " ");
                    }
                    if (sb.toString().length() >= 100) {
                        segments.add(sb.toString());
                    }
                }
            }


            int inputTokens = inputTokensNode != null ? inputTokensNode.asInt() : 0;

            log.info("Segmentation 완료: {}개 문단, {} tokens, {}ms",
                     segments.size(), inputTokens, responseTime);

            return new SegmentationResult(true, segments, inputTokens, responseTime, null);

        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.error("Segmentation 실패: {}", e.getMessage(), e);

            return new SegmentationResult(false, List.of(), 0, responseTime, e.getMessage());
        }
    }

    /**
     * Segmentation 결과를 담는 DTO
     */
    public static class SegmentationResult {
        private final boolean success;
        private final List<String> segments;
        private final int inputTokens;
        private final long responseTimeMs;
        private final String errorMessage;

        public SegmentationResult(boolean success, List<String> segments, int inputTokens,
                                  long responseTimeMs, String errorMessage) {
            this.success = success;
            this.segments = segments;
            this.inputTokens = inputTokens;
            this.responseTimeMs = responseTimeMs;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() {
            return success;
        }

        public List<String> getSegments() {
            return segments;
        }

        public int getInputTokens() {
            return inputTokens;
        }

        public long getResponseTimeMs() {
            return responseTimeMs;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
