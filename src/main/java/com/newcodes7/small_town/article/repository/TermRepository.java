package com.newcodes7.small_town.article.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
