package com.newcodes7.small_town.embedding.repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 벡터 검색 정확도 측정용 Repository
 *
 * HNSW 인덱스를 우회(SET LOCAL enable_indexscan=off)하여 Exact Search를 수행합니다.
 * Ground Truth 계산 및 Stage1 Binary HNSW 후보 추출에 사용됩니다.
 *
 * JPA @Query로는 SET LOCAL 실행이 불가하여 JdbcTemplate 방식을 사용합니다.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class ArticleChunkAccuracyRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Exact Search (Ground Truth) - HNSW 인덱스 우회
     *
     * embedding_normalized 전체 스캔으로 정확한 코사인 유사도를 계산합니다.
     * threshold 없이 전체 아티클을 반환합니다.
     *
     * @param vectorString PostgreSQL halfvec 포맷 벡터 문자열 ("[0.1,0.2,...]")
     * @param topK 평균 계산에 사용할 상위 청크 수
     * @param limit 반환할 최대 아티클 수
     * @return Article ID -> 코사인 유사도 맵 (내림차순 정렬)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<Long, Double> exactSearch(String vectorString, int topK, int limit) {
        // SET LOCAL enable_indexscan=off: HNSW 인덱스를 비활성화하여 full scan 수행
        jdbcTemplate.execute("SET LOCAL enable_indexscan = off");

        String sql = """
                WITH query_vec AS (
                    SELECT l2_normalize(CAST(? AS halfvec(1024))) AS vec
                ),
                chunk_similarities AS (
                    SELECT
                        cac.article_id,
                        -(ccv.embedding_normalized <#> (SELECT vec FROM query_vec)) AS similarity,
                        ROW_NUMBER() OVER (
                            PARTITION BY cac.article_id
                            ORDER BY ccv.embedding_normalized <#> (SELECT vec FROM query_vec)
                        ) AS rn
                    FROM clova_article_chunk cac
                    JOIN article a ON cac.article_id = a.id
                    JOIN clova_chunk_vectors ccv ON ccv.id = cac.id
                    WHERE a.deleted_at IS NULL
                      AND cac.embedding_binary IS NOT NULL
                )
                SELECT article_id, AVG(similarity) AS avg_similarity
                FROM chunk_similarities
                WHERE rn <= ?
                GROUP BY article_id
                ORDER BY avg_similarity DESC
                LIMIT ?
                """;

        List<Map.Entry<Long, Double>> rows = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> Map.entry(rs.getLong("article_id"), rs.getDouble("avg_similarity")),
                vectorString, topK, limit
        );

        // 같은 트랜잭션의 이후 쿼리(Stage1/Stage2)가 인덱스를 정상 사용하도록 복원
        jdbcTemplate.execute("SET LOCAL enable_indexscan = on");

        Map<Long, Double> result = new LinkedHashMap<>();
        for (Map.Entry<Long, Double> entry : rows) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /**
     * Stage1 Binary HNSW 후보 추출 (reranking 전)
     *
     * embedding_binary <~> (Hamming 거리) 기반으로 candidateLimit개 후보 article_id를 반환합니다.
     * threshold 미적용 (Stage1 recall 측정용).
     *
     * @param binaryString binary quantization 결과 bit 문자열
     * @param candidateLimit 후보 수 (기본 200)
     * @return Stage1 후보 Article ID 목록
     */
    public List<Long> stage1CandidateIds(String binaryString, int candidateLimit) {
        // 프로덕션과 동일하게 청크 기준 상위 candidateLimit개를 먼저 추린 뒤 distinct article_id 추출
        // (DISTINCT + ORDER BY non-selected column은 PostgreSQL에서 에러 발생)
        String sql = """
                SELECT DISTINCT article_id
                FROM (
                    SELECT cac.article_id
                    FROM clova_article_chunk cac
                    JOIN article a ON cac.article_id = a.id
                    WHERE a.deleted_at IS NULL
                      AND cac.embedding_binary IS NOT NULL
                    ORDER BY cac.embedding_binary <~> CAST(? AS bit(1024))
                    LIMIT ?
                ) top_chunks
                """;

        return jdbcTemplate.queryForList(sql, Long.class, binaryString, candidateLimit);
    }

    /**
     * Exact Search에서 threshold 이상인 아티클 수 조회
     *
     * @param vectorString PostgreSQL halfvec 포맷 벡터 문자열
     * @param topK 평균 계산에 사용할 상위 청크 수
     * @param threshold 유사도 임계값
     * @return threshold 이상인 아티클 수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int countExactSearchAboveThreshold(String vectorString, int topK, double threshold) {
        jdbcTemplate.execute("SET LOCAL enable_indexscan = off");

        String sql = """
                WITH query_vec AS (
                    SELECT l2_normalize(CAST(? AS halfvec(1024))) AS vec
                ),
                chunk_similarities AS (
                    SELECT
                        cac.article_id,
                        -(ccv.embedding_normalized <#> (SELECT vec FROM query_vec)) AS similarity,
                        ROW_NUMBER() OVER (
                            PARTITION BY cac.article_id
                            ORDER BY ccv.embedding_normalized <#> (SELECT vec FROM query_vec)
                        ) AS rn
                    FROM clova_article_chunk cac
                    JOIN article a ON cac.article_id = a.id
                    JOIN clova_chunk_vectors ccv ON ccv.id = cac.id
                    WHERE a.deleted_at IS NULL
                      AND cac.embedding_binary IS NOT NULL
                )
                SELECT COUNT(DISTINCT article_id)
                FROM (
                    SELECT article_id, AVG(similarity) AS avg_similarity
                    FROM chunk_similarities
                    WHERE rn <= ?
                    GROUP BY article_id
                    HAVING AVG(similarity) >= ?
                ) sub
                """;

        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, vectorString, topK, threshold);
        jdbcTemplate.execute("SET LOCAL enable_indexscan = on");
        return count != null ? count : 0;
    }
}
