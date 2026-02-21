package com.newcodes7.small_town.embedding.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.newcodes7.small_town.embedding.dto.ModelEmbeddingResult;
import com.newcodes7.small_town.embedding.repository.ClovaArticleChunkRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Clova Embedding 기반 벡터 검색 서비스
 *
 * 2단계 검색 지원:
 * - Stage 1: Binary HNSW (빠른 후보 필터링)
 * - Stage 2: halfvec Reranking (정밀 유사도 계산)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClovaSearchService {

    private final NaverClovaEmbeddingService clovaEmbeddingService;
    private final ClovaArticleChunkRepository clovaChunkRepository;

    // 기본 유사도 임계값
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.52;

    // 최대 결과 수
    private static final int DEFAULT_MAX_RESULTS = 100;

    // 평균 계산에 사용할 상위 청크 수
    private static final int DEFAULT_TOP_K = 3;

    // Binary HNSW 후보 수 (Stage 1)
    private static final int DEFAULT_CANDIDATE_LIMIT = 500;

    // 2단계 검색 사용 여부 (Binary HNSW 인덱스 생성 후 true로 변경)
    private static final boolean USE_TWO_STAGE_SEARCH = true;

    /**
     * 키워드를 임베딩하여 유사한 Article 검색
     *
     * USE_TWO_STAGE_SEARCH가 true이면 2단계 검색 사용:
     * - Stage 1: Binary HNSW로 빠른 후보 필터링
     * - Stage 2: halfvec으로 정밀 Reranking
     *
     * @param keyword 검색 키워드
     * @param threshold 유사도 임계값 (0.0 ~ 1.0)
     * @param topK 평균 계산에 사용할 상위 청크 수
     * @param maxResults 최대 결과 수
     * @return Article ID -> 유사도 스코어 맵
     */
    public Map<Long, Double> searchByKeyword(String keyword, double threshold, int topK, int maxResults) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return Map.of();
        }

        try {
            // 1. 키워드 임베딩 생성
            ModelEmbeddingResult embResult = clovaEmbeddingService.generateEmbedding(keyword, null);

            if (!embResult.isSuccess() || embResult.getEmbedding() == null) {
                log.warn("Clova 키워드 임베딩 생성 실패: {}", embResult.getErrorMessage());
                return Map.of();
            }

            float[] queryEmbedding = embResult.getEmbedding();
            log.debug("Clova 키워드 임베딩 생성 완료 - 차원: {}, 토큰: {}",
                    queryEmbedding.length, embResult.getTokenUsage());

            // 2단계 검색 또는 기존 검색 선택
            if (USE_TWO_STAGE_SEARCH) {
                return searchTwoStage(queryEmbedding, threshold, topK, maxResults);
            } else {
                return searchDirectHalfvec(queryEmbedding, threshold, topK, maxResults);
            }

        } catch (Exception e) {
            log.error("Clova 벡터 검색 실패: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    /**
     * 기존 방식: halfvec 직접 검색
     */
    private Map<Long, Double> searchDirectHalfvec(float[] queryEmbedding, double threshold, int topK, int maxResults) {
        String vectorString = formatVectorForPostgres(queryEmbedding);

        List<Object[]> results = clovaChunkRepository.findArticleIdsByTopKAvgSimilarity(
                vectorString, threshold, topK, maxResults);

        Map<Long, Double> scoreMap = new HashMap<>();
        for (Object[] row : results) {
            Long articleId = ((Number) row[0]).longValue();
            Double similarity = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
            if (similarity != null) {
                scoreMap.put(articleId, similarity);
            }
        }

        log.info("Clova 벡터 검색 완료 (halfvec 직접) - 결과 수: {}", scoreMap.size());
        return scoreMap;
    }

    /**
     * 2단계 검색: Binary HNSW → halfvec Reranking (단일 CTE 쿼리)
     */
    private Map<Long, Double> searchTwoStage(float[] queryEmbedding, double threshold, int topK, int maxResults) {
        long startTime = System.currentTimeMillis();

        String vectorString = formatVectorForPostgres(queryEmbedding);
        String binaryString = toBinaryString(queryEmbedding);

        List<Object[]> results = clovaChunkRepository.findArticlesByTwoStageSearch(
                vectorString, binaryString, DEFAULT_CANDIDATE_LIMIT, topK, threshold, maxResults);

        long totalTime = System.currentTimeMillis() - startTime;

        Map<Long, Double> scoreMap = new HashMap<>();
        for (Object[] row : results) {
            Long articleId = ((Number) row[0]).longValue();
            Double similarity = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
            if (similarity != null) {
                scoreMap.put(articleId, similarity);
            }
        }

        log.info("Clova 2단계 검색 완료 - 총: {}ms, 결과: {}개", totalTime, scoreMap.size());

        return scoreMap;
    }

    /**
     * 키워드를 임베딩하여 유사한 Article 검색 (기본 topK 사용)
     */
    public Map<Long, Double> searchByKeyword(String keyword, double threshold, int maxResults) {
        return searchByKeyword(keyword, threshold, DEFAULT_TOP_K, maxResults);
    }

    /**
     * 기본 설정으로 키워드 검색
     */
    public Map<Long, Double> searchByKeyword(String keyword) {
        return searchByKeyword(keyword, DEFAULT_SIMILARITY_THRESHOLD, DEFAULT_MAX_RESULTS);
    }

    /**
     * 키워드 검색 결과와 생성된 임베딩을 함께 반환
     * 임베딩을 재사용하여 중복 API 호출을 방지
     *
     * @param keyword 검색 키워드
     * @return 검색 결과 (스코어 맵 + 쿼리 임베딩)
     */
    public VectorSearchResult searchByKeywordWithEmbedding(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return new VectorSearchResult(Map.of(), null);
        }

        try {
            ModelEmbeddingResult embResult = clovaEmbeddingService.generateEmbedding(keyword, null);

            if (!embResult.isSuccess() || embResult.getEmbedding() == null) {
                log.warn("Clova 키워드 임베딩 생성 실패: {}", embResult.getErrorMessage());
                return new VectorSearchResult(Map.of(), null);
            }

            float[] queryEmbedding = embResult.getEmbedding();
            log.debug("Clova 키워드 임베딩 생성 완료 - 차원: {}, 토큰: {}",
                    queryEmbedding.length, embResult.getTokenUsage());

            Map<Long, Double> scores;
            if (USE_TWO_STAGE_SEARCH) {
                scores = searchTwoStage(queryEmbedding, DEFAULT_SIMILARITY_THRESHOLD, DEFAULT_TOP_K, DEFAULT_MAX_RESULTS);
            } else {
                scores = searchDirectHalfvec(queryEmbedding, DEFAULT_SIMILARITY_THRESHOLD, DEFAULT_TOP_K, DEFAULT_MAX_RESULTS);
            }

            return new VectorSearchResult(scores, queryEmbedding);

        } catch (Exception e) {
            log.error("Clova 벡터 검색 실패: {}", e.getMessage(), e);
            return new VectorSearchResult(Map.of(), null);
        }
    }

    /**
     * 벡터 검색 결과 + 쿼리 임베딩 래퍼
     */
    public static class VectorSearchResult {
        private final Map<Long, Double> scores;
        private final float[] queryEmbedding;

        public VectorSearchResult(Map<Long, Double> scores, float[] queryEmbedding) {
            this.scores = scores;
            this.queryEmbedding = queryEmbedding;
        }

        public Map<Long, Double> getScores() {
            return scores;
        }

        public float[] getQueryEmbedding() {
            return queryEmbedding;
        }
    }

    /**
     * 특정 Article ID들에 대한 vector similarity 계산
     * BM25로만 검색된 article들의 vector score를 채우기 위해 사용
     *
     * @param keyword 검색 키워드
     * @param articleIds vector similarity를 계산할 Article ID 목록
     * @return Article ID -> 유사도 스코어 맵
     */
    public Map<Long, Double> computeSimilarityForArticles(String keyword, List<Long> articleIds) {
        if (keyword == null || keyword.trim().isEmpty() || articleIds == null || articleIds.isEmpty()) {
            return Map.of();
        }

        try {
            // 1. 키워드 임베딩 생성
            ModelEmbeddingResult embResult = clovaEmbeddingService.generateEmbedding(keyword, null);

            if (!embResult.isSuccess() || embResult.getEmbedding() == null) {
                log.warn("Clova 키워드 임베딩 생성 실패: {}", embResult.getErrorMessage());
                return Map.of();
            }

            float[] queryEmbedding = embResult.getEmbedding();

            // 2. PostgreSQL halfvec 포맷으로 변환
            String vectorString = formatVectorForPostgres(queryEmbedding);

            // 3. 특정 Article들에 대한 유사도 계산 (halfvec)
            List<Object[]> results = clovaChunkRepository.computeSimilarityForArticleIds(
                    vectorString, articleIds, DEFAULT_TOP_K);

            // 4. Article ID와 유사도 스코어 맵으로 변환
            Map<Long, Double> scoreMap = new HashMap<>();
            for (Object[] row : results) {
                Long articleId = ((Number) row[0]).longValue();
                Double similarity = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
                if (similarity != null) {
                    scoreMap.put(articleId, similarity);
                }
            }

            log.debug("Clova 특정 Article 유사도 계산 완료 - 키워드: '{}', 요청: {}개, 계산됨: {}개",
                    keyword, articleIds.size(), scoreMap.size());
            return scoreMap;

        } catch (Exception e) {
            log.error("Clova 특정 Article 유사도 계산 실패: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    /**
     * 캐시된 쿼리 임베딩을 사용하여 특정 Article ID들에 대한 vector similarity 계산
     * searchArticlesHybrid에서 이미 생성된 임베딩을 재사용하여 API 호출 절감
     *
     * @param queryEmbedding 이미 생성된 쿼리 임베딩
     * @param articleIds vector similarity를 계산할 Article ID 목록
     * @return Article ID -> 유사도 스코어 맵
     */
    public Map<Long, Double> computeSimilarityForArticlesWithEmbedding(float[] queryEmbedding, List<Long> articleIds) {
        if (queryEmbedding == null || articleIds == null || articleIds.isEmpty()) {
            return Map.of();
        }

        try {
            // PostgreSQL halfvec 포맷으로 변환
            String vectorString = formatVectorForPostgres(queryEmbedding);

            // 특정 Article들에 대한 유사도 계산 (halfvec)
            List<Object[]> results = clovaChunkRepository.computeSimilarityForArticleIds(
                    vectorString, articleIds, DEFAULT_TOP_K);

            // Article ID와 유사도 스코어 맵으로 변환
            Map<Long, Double> scoreMap = new HashMap<>();
            for (Object[] row : results) {
                Long articleId = ((Number) row[0]).longValue();
                Double similarity = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
                if (similarity != null && similarity >= DEFAULT_SIMILARITY_THRESHOLD) {
                    scoreMap.put(articleId, similarity);
                }
            }

            log.debug("Clova 특정 Article 유사도 계산 완료 (캐시된 임베딩) - 요청: {}개, 계산됨: {}개",
                    articleIds.size(), scoreMap.size());
            return scoreMap;

        } catch (Exception e) {
            log.error("Clova 특정 Article 유사도 계산 실패: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    /**
     * float[] 임베딩을 PostgreSQL halfvec 포맷으로 변환
     * 형식: [0.1,0.2,0.3,...,0.9]
     */
    private String formatVectorForPostgres(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * float[] 임베딩을 Binary String으로 변환 (Binary Quantization)
     * 양수 → 1, 음수 → 0
     */
    private String toBinaryString(float[] embedding) {
        StringBuilder sb = new StringBuilder(embedding.length);
        for (float val : embedding) {
            sb.append(val >= 0 ? '1' : '0');
        }
        return sb.toString();
    }
}
