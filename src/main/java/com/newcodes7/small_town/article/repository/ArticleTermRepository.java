package com.newcodes7.small_town.article.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.newcodes7.small_town.global.entity.ArticleTerm;

public interface ArticleTermRepository extends JpaRepository<ArticleTerm, Long> {

    /**
     * 특정 article의 모든 term 조회 (Term fetch join)
     */
    @Query("SELECT at FROM ArticleTerm at JOIN FETCH at.term WHERE at.article.id = :articleId")
    List<ArticleTerm> findByArticleId(@Param("articleId") Long articleId);

    /**
     * 특정 article의 모든 term 조회 (score 내림차순, Term fetch join)
     * 임베딩 생성 시 사용
     */
    @Query("SELECT at FROM ArticleTerm at JOIN FETCH at.term WHERE at.article.id = :articleId ORDER BY at.score DESC")
    List<ArticleTerm> findByArticleIdOrderByScoreDesc(@Param("articleId") Long articleId);

    /**
     * 여러 article의 모든 term을 한 번에 조회 (bulk 조회, Term fetch join)
     * @param articleIds Article ID 목록
     */
    @Query("SELECT at FROM ArticleTerm at JOIN FETCH at.term WHERE at.article.id IN :articleIds ORDER BY at.score DESC")
    List<ArticleTerm> findByArticleIdInOrderByScoreDesc(@Param("articleIds") List<Long> articleIds);

    /**
     * 특정 article의 모든 term 삭제
     */
    @Modifying
    @Query("DELETE FROM ArticleTerm at WHERE at.article.id = :articleId")
    void deleteByArticleId(@Param("articleId") Long articleId);

    /**
     * 특정 article에 특정 term이 이미 존재하는지 확인
     */
    boolean existsByArticleIdAndTermId(Long articleId, Long termId);

    /**
     * 특정 article에 term이 있는지 확인 (건너뛰기 판단용)
     */
    boolean existsByArticleId(Long articleId);

    /**
     * 모든 article_term 삭제 (재생성 시 사용)
     */
    @Modifying
    @Query("DELETE FROM ArticleTerm")
    void deleteAll();

    /**
     * 특정 term ID를 가진 모든 ArticleTerm 삭제
     */
    @Modifying
    @Query("DELETE FROM ArticleTerm at WHERE at.term.id = :termId")
    int deleteByTermId(@Param("termId") Long termId);

    /**
     * 특정 term ID를 가진 ArticleTerm 개수 조회
     */
    @Query("SELECT COUNT(at) FROM ArticleTerm at WHERE at.term.id = :termId")
    int countByTermId(@Param("termId") Long termId);

    /**
     * 특정 term(문자열)으로 article 검색용 (향후 검색 기능에 사용)
     */
    @Query("SELECT DISTINCT at.article.id FROM ArticleTerm at WHERE at.term.term = :term")
    List<Long> findArticleIdsByTerm(@Param("term") String term);

    /**
     * 여러 term(문자열)으로 article 검색용 (OR 조건)
     */
    @Query("SELECT DISTINCT at.article.id FROM ArticleTerm at WHERE at.term.term IN :terms")
    List<Long> findArticleIdsByTerms(@Param("terms") List<String> terms);

    /**
     * 여러 term ID로 article 검색용 (OR 조건)
     * 유의어 검색에 사용
     */
    @Query("SELECT DISTINCT at.article.id FROM ArticleTerm at WHERE at.term.id IN :termIds")
    List<Long> findArticleIdsByTermIds(@Param("termIds") List<Long> termIds);

    /**
     * Term 통계 조회 (많이 사용된 순) - 최적화 버전
     * term 테이블의 비정규화 컬럼(total_frequency, article_count)을 직접 사용
     * 성능: 192만 건 article_term 집계 → 11만 건 term 테이블 직접 조회
     *
     * Spring Data JPA의 Pageable은 자동으로 LIMIT/OFFSET을 처리합니다.
     */
    @Query(value = """
        SELECT t.id as termId,
               t.term as term,
               t.term_type as termType,
               t.decomposed_term as decomposedTerm,
               t.created_at as createdAt,
               t.total_frequency as totalFrequency,
               t.article_count as articleCount
        FROM term t
        WHERE t.total_frequency > 0
        ORDER BY t.total_frequency DESC, t.created_at DESC
        """,
        countQuery = "SELECT COUNT(*) FROM term WHERE total_frequency > 0",
        nativeQuery = true)
    List<TermStatistics> findTermStatistics(Pageable pageable);

    /**
     * Term 통계 조회 (검색 기능 포함) - 최적화 버전
     * term 테이블의 비정규화 컬럼(total_frequency, article_count)을 직접 사용하여 검색
     * 성능: 192만 건 article_term 집계 → 11만 건 term 테이블 직접 조회
     *
     * Spring Data JPA의 Pageable은 자동으로 LIMIT/OFFSET을 처리합니다.
     */
    @Query(value = """
        SELECT t.id as termId,
               t.term as term,
               t.term_type as termType,
               t.decomposed_term as decomposedTerm,
               t.created_at as createdAt,
               t.total_frequency as totalFrequency,
               t.article_count as articleCount
        FROM term t
        WHERE t.total_frequency > 0
          AND LOWER(t.term) LIKE LOWER(CONCAT('%', ?1, '%'))
        ORDER BY t.total_frequency DESC, t.created_at DESC
        """,
        countQuery = "SELECT COUNT(*) FROM term WHERE total_frequency > 0 AND LOWER(term) LIKE LOWER(CONCAT('%', ?1, '%'))",
        nativeQuery = true)
    List<TermStatistics> findTermStatisticsBySearch(String search, Pageable pageable);

    /**
     * Term 통계 총 개수 조회 - 최적화 버전
     * term 테이블을 직접 조회하여 카운트
     */
    @Query(value = "SELECT COUNT(*) FROM term WHERE total_frequency > 0", nativeQuery = true)
    long countDistinctTerms();

    /**
     * Term 통계 총 개수 조회 (검색 포함) - 최적화 버전
     * term 테이블을 직접 조회하여 카운트
     */
    @Query(value = "SELECT COUNT(*) FROM term WHERE total_frequency > 0 AND LOWER(term) LIKE LOWER(CONCAT('%', :search, '%'))", nativeQuery = true)
    long countDistinctTermsBySearch(@Param("search") String search);

    /**
     * Term 통계 인터페이스 (Projection)
     */
    interface TermStatistics {
        Long getTermId();
        String getTerm();
        String getTermType();
        String getDecomposedTerm();
        java.time.LocalDateTime getCreatedAt();
        Long getTotalFrequency();
        Long getArticleCount();
    }

    /**
     * 자동완성 제안 인터페이스 (Projection)
     */
    interface AutocompleteSuggestion {
        String getTerm();
        Long getTotalFrequency();
    }
}
