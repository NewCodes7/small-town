package com.newcodes7.small_town.search.service;

import com.newcodes7.small_town.embedding.service.ArticleEmbeddingService;
import com.newcodes7.small_town.global.service.MorphemeAnalyzer;
import com.newcodes7.small_town.term.repository.TermRepository;
import com.newcodes7.small_town.term.service.TermSynonymService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

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

    /**
     * 확장 결과 캐시 이름
     * 유의어 변경 시 TermSynonymService / ArticleTermService에서 전체 무효화된다.
     */
    private static final String EXPANSION_CACHE_NAME = "searchTermExpansion";

    private final TermRepository termRepository;
    private final ArticleEmbeddingService embeddingService;
    private final TermSynonymService termSynonymService;
    private final MorphemeAnalyzer morphemeAnalyzer;
    private final CacheManager cacheManager;

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
        Map<String, Double> cached = getCachedExpansion(keyword);
        if (cached != null) {
            log.debug("검색어 '{}' 확장 캐시 히트 ({}개 Term)", keyword, cached.size());
            return cached;
        }

        // LinkedHashMap으로 순서 유지 (직접 매칭 → 유의어)
        Map<String, Double> expandedTerms = new LinkedHashMap<>();

        // 1. 원본 키워드에서 Term 추출
        Map<String, MorphemeAnalyzer.TermInfo> termMap =
            morphemeAnalyzer.extractTermsFromMultipleTexts(List.of(keyword));

        if (termMap.isEmpty()) {
            log.warn("검색어 '{}' 에서 Term을 추출하지 못했습니다.", keyword);
            // 키워드 전체를 그대로 사용
            expandedTerms.put(keyword, 1.0);
            return cacheExpansion(keyword, expandedTerms);
        }

        // 2. 직접 매칭 Term (가중치 1.0) - 최우선
        for (MorphemeAnalyzer.TermInfo termInfo : termMap.values()) {
            expandedTerms.put(termInfo.getTerm(), 1.0);
            log.debug("직접 매칭 Term: '{}' (가중치: 1.0)", termInfo.getTerm());
        }

        // 3. TermSynonym에서 유의어 조회 (가중치 0.8) - 조인 쿼리 1회
        List<String> directTerms = List.copyOf(expandedTerms.keySet());
        try {
            Map<String, List<String>> synonymsByTerm = termSynonymService.getSynonymsByTerms(directTerms);

            // 조회 결과가 아닌 directTerms 순서로 순회해 확장 순서를 결정적으로 유지
            for (String term : directTerms) {
                for (String synonym : synonymsByTerm.getOrDefault(term, List.of())) {
                    if (expandedTerms.putIfAbsent(synonym, 0.8) == null) {
                        log.debug("유의어 Term: '{}' (가중치: 0.8)", synonym);
                    }
                }
            }
        } catch (Exception e) {
            // 유의어 조회 실패 시 직접 매칭만으로 degrade — 이 결과는 캐시하지 않는다
            log.warn("검색어 '{}' 유의어 조회 중 오류: {}", keyword, e.getMessage());
            return expandedTerms;
        }

        log.info("검색어 '{}' 확장 완료 - 총 {}개 Term (직접: {}, 유의어: {})",
                keyword, expandedTerms.size(), termMap.size(), expandedTerms.size() - termMap.size());

        return cacheExpansion(keyword, expandedTerms);
    }

    /**
     * 확장 결과 캐시 조회
     *
     * @return 캐시된 결과, 없거나 캐시가 구성되지 않았으면 null
     */
    @SuppressWarnings("unchecked")
    private Map<String, Double> getCachedExpansion(String keyword) {
        Cache cache = cacheManager.getCache(EXPANSION_CACHE_NAME);
        if (cache == null) {
            return null;
        }

        Cache.ValueWrapper wrapper = cache.get(keyword);
        return wrapper == null ? null : (Map<String, Double>) wrapper.get();
    }

    /**
     * 확장 결과를 캐시에 저장하고 불변 맵으로 반환
     * 캐시 인스턴스가 여러 요청에 공유되므로 불변으로 감싼다.
     */
    private Map<String, Double> cacheExpansion(String keyword, Map<String, Double> expandedTerms) {
        Map<String, Double> immutable = Collections.unmodifiableMap(expandedTerms);

        Cache cache = cacheManager.getCache(EXPANSION_CACHE_NAME);
        if (cache != null) {
            cache.put(keyword, immutable);
        }

        return immutable;
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
