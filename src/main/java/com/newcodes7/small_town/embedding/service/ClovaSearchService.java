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
 * halfvec (16비트 반정밀도) 사용으로 저장 공간 50% 절감
 * pgvector 0.7.0+ 필요
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClovaSearchService {

    private final NaverClovaEmbeddingService clovaEmbeddingService;
    private final ClovaArticleChunkRepository clovaChunkRepository;

    // 기본 유사도 임계값
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.5;

    // 최대 결과 수
    private static final int DEFAULT_MAX_RESULTS = 100;

    // 평균 계산에 사용할 상위 청크 수
    private static final int DEFAULT_TOP_K = 3;

    /**
     * 키워드를 임베딩하여 유사한 Article 검색 (상위 K개 청크 평균 방식)
     * 단일 청크만 유사한 경우 낮은 점수를 받아, 주제 전체가 관련있는 글이 상위에 노출됨
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

            // 2. PostgreSQL halfvec 포맷으로 변환
            String vectorString = formatVectorForPostgres(queryEmbedding);

            // 3. 상위 K개 청크 평균 유사도 검색 (halfvec)
            List<Object[]> results = clovaChunkRepository.findArticleIdsByTopKAvgSimilarity(
                    vectorString, threshold, topK, maxResults);

            // 4. Article ID와 유사도 스코어 맵으로 변환
            Map<Long, Double> scoreMap = new HashMap<>();
            for (Object[] row : results) {
                Long articleId = ((Number) row[0]).longValue();
                Double similarity = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
                if (similarity != null) {
                    scoreMap.put(articleId, similarity);
                }
            }

            log.info("Clova 벡터 검색 완료 (halfvec) - 키워드: '{}', topK: {}, 결과 수: {}", keyword, topK, scoreMap.size());
            return scoreMap;

        } catch (Exception e) {
            log.error("Clova 벡터 검색 실패: {}", e.getMessage(), e);
            return Map.of();
        }
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
                if (similarity != null) {
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
}
