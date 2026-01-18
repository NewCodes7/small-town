package com.newcodes7.small_town.article.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.newcodes7.small_town.global.entity.Term;

/**
 * Term Repository
 * 형태소 분석으로 추출된 용어를 관리
 */
public interface TermRepository extends JpaRepository<Term, Long> {

    /**
     * term과 termType으로 Term 조회
     * term은 unique constraint가 있으므로 하나만 반환됨
     */
    Optional<Term> findByTermAndTermType(String term, String termType);

    /**
     * term 문자열로 모든 termType의 Term 조회
     * 유의어 검색 시 termType과 관계없이 모든 매칭되는 term을 찾기 위해 사용
     */
    List<Term> findByTerm(String term);

    /**
     * term과 termType으로 Term이 존재하는지 확인
     */
    boolean existsByTermAndTermType(String term, String termType);

    /**
     * term 문자열로 LIKE 검색 (대소문자 무시)
     * 유의어 관리 UI에서 Term 검색 시 사용
     * findAll() 후 필터링 대신 DB에서 직접 검색하여 성능 최적화
     *
     * @param query 검색어
     * @param limit 최대 결과 수
     * @return 매칭되는 Term 목록
     */
    @Query("SELECT t FROM Term t WHERE LOWER(t.term) LIKE LOWER(CONCAT('%', :query, '%')) ORDER BY t.term LIMIT :limit")
    List<Term> searchByTermContaining(@Param("query") String query, @Param("limit") int limit);

    // ===== 벡터 유사도 검색 =====

    /**
     * 벡터 유사도로 유사 Term 검색 (코사인 거리 기준)
     * pgvector의 <=> 연산자 사용 (코사인 거리, 0에 가까울수록 유사)
     *
     * @param queryEmbedding 검색 벡터 (PostgreSQL vector 포맷: "[0.1,0.2,...]")
     * @param topK 상위 K개 결과
     * @return List<Object[]> - [term(String), distance(Double)]
     */
    @Query(value = "SELECT t.term, t.embedding <=> CAST(:queryEmbedding AS vector) as distance " +
                   "FROM term t " +
                   "WHERE t.embedding IS NOT NULL " +
                   "ORDER BY distance " +
                   "LIMIT :topK",
           nativeQuery = true)
    List<Object[]> findSimilarTermsByEmbedding(
        @Param("queryEmbedding") String queryEmbedding,
        @Param("topK") int topK
    );

    /**
     * 임베딩이 없는 Term 조회 (배치 생성용)
     * @param limit 최대 조회 개수
     */
    @Query("SELECT t FROM Term t WHERE t.embedding IS NULL ORDER BY t.createdAt DESC")
    List<Term> findTermsWithoutEmbedding(@Param("limit") int limit);

    /**
     * 임베딩이 있는 Term 개수 조회
     */
    @Query("SELECT COUNT(t) FROM Term t WHERE t.embedding IS NOT NULL")
    long countTermsWithEmbedding();

    /**
     * 전체 Term 개수 조회
     */
    @Query("SELECT COUNT(t) FROM Term t")
    long countAllTerms();

    // ===== 비정규화 컬럼 갱신 =====

    /**
     * 모든 Term의 total_frequency와 article_count를 재계산하여 업데이트
     * 크롤링 후, Term 추출 후 일괄 실행
     *
     * 성능: 11만 개 term 기준 약 1-2초 소요
     */
    @Modifying
    @Query(value = """
        UPDATE term t
        SET total_frequency = COALESCE(stats.total_freq, 0),
            article_count = COALESCE(stats.article_cnt, 0)
        FROM (
            SELECT
                at.term_id,
                SUM(at.frequency) as total_freq,
                COUNT(DISTINCT at.article_id) as article_cnt
            FROM article_term at
            GROUP BY at.term_id
        ) stats
        WHERE t.id = stats.term_id
        """, nativeQuery = true)
    int updateAllTermStatistics();

    /**
     * total_frequency가 0이거나 NULL인 Term들의 통계를 0으로 초기화
     * ArticleTerm이 모두 삭제된 Term의 통계를 정리
     */
    @Modifying
    @Query(value = """
        UPDATE term t
        SET total_frequency = 0,
            article_count = 0
        WHERE NOT EXISTS (
            SELECT 1 FROM article_term at WHERE at.term_id = t.id
        )
        AND (t.total_frequency IS NULL OR t.total_frequency > 0
             OR t.article_count IS NULL OR t.article_count > 0)
        """, nativeQuery = true)
    int resetOrphanedTermStatistics();

    /**
     * 특정 Term의 total_frequency와 article_count를 재계산하여 업데이트
     * 단일 ArticleTerm 추가/삭제 시 사용
     *
     * @param termId 대상 Term ID
     * @return 업데이트된 행 수 (0 또는 1)
     */
    @Modifying
    @Query(value = """
        UPDATE term t
        SET total_frequency = COALESCE((
                SELECT SUM(at.frequency)
                FROM article_term at
                WHERE at.term_id = :termId
            ), 0),
            article_count = COALESCE((
                SELECT COUNT(DISTINCT at.article_id)
                FROM article_term at
                WHERE at.term_id = :termId
            ), 0)
        WHERE t.id = :termId
        """, nativeQuery = true)
    int updateTermStatistics(@Param("termId") Long termId);
}
