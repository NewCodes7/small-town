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
    private static final int DEFAULT_MAX_RESULTS = 50;

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

            // 2. PostgreSQL vector 포맷으로 변환
            String vectorString = formatVectorForPostgres(queryEmbedding);

            // 3. 상위 K개 청크 평균 유사도 검색
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

            log.info("Clova 벡터 검색 완료 - 키워드: '{}', topK: {}, 결과 수: {}", keyword, topK, scoreMap.size());
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
     * float[] 임베딩을 PostgreSQL vector 포맷으로 변환
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
