package com.newcodes7.small_town.search.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.newcodes7.small_town.article.repository.TermRepository;
import com.newcodes7.small_town.article.service.ArticleEmbeddingService;
import com.newcodes7.small_town.article.service.TermSynonymService;
import com.newcodes7.small_town.global.entity.Term;
import com.newcodes7.small_town.global.service.MorphemeAnalyzer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 의미적 검색어 확장 서비스
 * TermSynonym 기반 유의어 확장 (임베딩 기반 검색은 비활성화됨)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticTermExpansionService {

    /**
     * 쿼리 복잡도 분류
     * 사용자가 입력한 원본 키워드의 공백 기준 단어 수로 분류
     */
    public enum QueryComplexity {
        SIMPLE,   // 단어 1개 (예: "mysql", "kubernetes")
        MODERATE, // 단어 2개 (예: "mysql 최적화", "spring boot")
        COMPLEX   // 단어 3개 이상 (예: "mysql 인덱스 설계", "검색 성능 개선")
    }

    private final TermRepository termRepository;
    private final ArticleEmbeddingService embeddingService;
    private final TermSynonymService termSynonymService;
    private final MorphemeAnalyzer morphemeAnalyzer;

    /**
     * 검색어를 의미적으로 확장
     *
     * 가중치 규칙:
     * - 직접 매칭: 1.0 (원본 키워드)
     * - TermSynonym: 0.8 (등록된 유의어)
     *
     * NOTE: 임베딩 유사 Term 검색은 현재 비활성화되어 있습니다.
     *
     * @param keyword 검색 키워드
     * @return Map<Term, Weight> - Term과 가중치
     */
    public Map<String, Double> expandSearchTerms(String keyword) {
        // LinkedHashMap으로 순서 유지 (직접 매칭 → 유의어 → 임베딩 유사어)
        Map<String, Double> expandedTerms = new LinkedHashMap<>();

        // 1. 원본 키워드에서 Term 추출
        Map<String, MorphemeAnalyzer.TermInfo> termMap =
            morphemeAnalyzer.extractTermsFromMultipleTexts(List.of(keyword));

        if (termMap.isEmpty()) {
            log.warn("검색어 '{}' 에서 Term을 추출하지 못했습니다.", keyword);
            // 키워드 전체를 그대로 사용
            expandedTerms.put(keyword, 1.0);
            return expandedTerms;
        }

        for (MorphemeAnalyzer.TermInfo termInfo : termMap.values()) {
            String term = termInfo.getTerm();

            // 2. 직접 매칭 Term (가중치 1.0) - 최우선
            expandedTerms.put(term, 1.0);
            log.debug("직접 매칭 Term: '{}' (가중치: 1.0)", term);

            // 3. TermSynonym에서 유의어 조회 (가중치 0.8)
            try {
                // Term 문자열로 Term 엔티티 조회
                List<Term> terms = termRepository.findByTerm(term);
                for (Term foundTerm : terms) {
                    // 유의어 ID 목록 조회 (복잡한 쿼리 회피)
                    List<Long> synonymTermIds = termSynonymService.getSynonymTermIds(foundTerm.getId());

                    // ID로 Term 엔티티 조회
                    for (Long synonymId : synonymTermIds) {
                        if (synonymId.equals(foundTerm.getId())) {
                            continue; // 자기 자신 제외
                        }

                        termRepository.findById(synonymId).ifPresent(synonymTerm -> {
                            String synonymStr = synonymTerm.getTerm();
                            if (!expandedTerms.containsKey(synonymStr)) {
                                expandedTerms.put(synonymStr, 0.8);
                                log.debug("유의어 Term: '{}' (가중치: 0.8)", synonymStr);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                log.warn("Term '{}' 유의어 조회 중 오류: {}", term, e.getMessage());
            }
        }

        log.info("검색어 '{}' 확장 완료 - 총 {}개 Term (직접: {}, 유의어: {})",
                keyword, expandedTerms.size(), termMap.size(), expandedTerms.size() - termMap.size());

        return expandedTerms;
    }

    /**
     * 사용자가 입력한 원본 키워드 기준으로 쿼리 복잡도 분류
     * 공백으로 구분된 단어 수로 결정 (형태소 분석 결과 아님)
     *
     * @param keyword 사용자 입력 키워드
     * @return QueryComplexity (SIMPLE / MODERATE / COMPLEX)
     */
    public QueryComplexity classifyQueryComplexity(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return QueryComplexity.SIMPLE;
        int wordCount = keyword.trim().split("\\s+").length;

        if (wordCount <= 1) return QueryComplexity.SIMPLE;
        if (wordCount <= 2) return QueryComplexity.MODERATE;
        return QueryComplexity.COMPLEX;
    }

    /**
     * 임베딩 유사도로 유사 Term 찾기
     *
     * @param term 검색할 Term
     * @param topK 상위 K개 결과
     * @param threshold 최소 유사도 (0.0 ~ 1.0)
     * @return 유사 Term 리스트
     */
    private List<SimilarTermInfo> findSimilarTermsByEmbedding(String term, int topK, double threshold) {
        try {
            // Term 임베딩 생성
            float[] termEmbedding = embeddingService.generateEmbedding(term);

            if (termEmbedding == null) {
                log.warn("Term '{}' 임베딩 생성 실패", term);
                return new ArrayList<>();
            }

            // PostgreSQL vector 포맷으로 변환
            String vectorString = formatVectorForPostgres(termEmbedding);

            // pgvector로 유사 Term 검색
            List<Object[]> results = termRepository.findSimilarTermsByEmbedding(vectorString, topK + 1);

            // 결과 변환 (자기 자신 제외, threshold 필터링)
            return results.stream()
                    .filter(row -> {
                        String foundTerm = (String) row[0];
                        double distance = ((Number) row[1]).doubleValue();
                        double similarity = 1.0 - distance; // 코사인 거리 → 유사도

                        // 자기 자신이 아니고, threshold 이상인 경우만
                        return !foundTerm.equals(term) && similarity >= threshold;
                    })
                    .map(row -> {
                        String foundTerm = (String) row[0];
                        double distance = ((Number) row[1]).doubleValue();
                        double similarity = 1.0 - distance;
                        return new SimilarTermInfo(foundTerm, similarity);
                    })
                    .limit(topK)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("임베딩 기반 유사 Term 검색 중 오류", e);
            return new ArrayList<>();
        }
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

    /**
     * 유사 Term 정보
     */
    public static class SimilarTermInfo {
        private final String term;
        private final double similarity;

        public SimilarTermInfo(String term, double similarity) {
            this.term = term;
            this.similarity = similarity;
        }

        public String getTerm() {
            return term;
        }

        public double getSimilarity() {
            return similarity;
        }
    }
}
