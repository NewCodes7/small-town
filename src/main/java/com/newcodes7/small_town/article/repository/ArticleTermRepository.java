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
     * Term 통계 조회 (많이 사용된 순)
     * Term 엔티티를 참조하여 중복 없이 통계 집계
     * Pageable의 sort를 통해 정렬 가능 (frequency, createdAt)
     */
    @Query("SELECT at.term.id as termId, " +
           "at.term.term as term, " +
           "at.term.termType as termType, " +
           "at.term.decomposedTerm as decomposedTerm, " +
           "at.term.createdAt as createdAt, " +
           "SUM(at.frequency) as totalFrequency, " +
           "COUNT(DISTINCT at.article.id) as articleCount " +
           "FROM ArticleTerm at " +
           "GROUP BY at.term.id, at.term.term, at.term.termType, at.term.decomposedTerm, at.term.createdAt")
    List<TermStatistics> findTermStatistics(Pageable pageable);

    /**
     * Term 통계 조회 (검색 기능 포함)
     * Term 문자열로 검색
     * Pageable의 sort를 통해 정렬 가능 (frequency, createdAt)
     */
    @Query("SELECT at.term.id as termId, " +
           "at.term.term as term, " +
           "at.term.termType as termType, " +
           "at.term.decomposedTerm as decomposedTerm, " +
           "at.term.createdAt as createdAt, " +
           "SUM(at.frequency) as totalFrequency, " +
           "COUNT(DISTINCT at.article.id) as articleCount " +
           "FROM ArticleTerm at " +
           "WHERE LOWER(at.term.term) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "GROUP BY at.term.id, at.term.term, at.term.termType, at.term.decomposedTerm, at.term.createdAt")
    List<TermStatistics> findTermStatisticsBySearch(@Param("search") String search, Pageable pageable);

    /**
     * Term 통계 총 개수 조회
     */
    @Query("SELECT COUNT(DISTINCT at.term.id) FROM ArticleTerm at")
    long countDistinctTerms();

    /**
     * Term 통계 총 개수 조회 (검색 포함)
     */
    @Query("SELECT COUNT(DISTINCT at.term.id) FROM ArticleTerm at WHERE LOWER(at.term.term) LIKE LOWER(CONCAT('%', :search, '%'))")
    long countDistinctTermsBySearch(@Param("search") String search);

    /**
     * 자동완성을 위한 Term 검색 (빈도수 순)
     * 사용자 입력값으로 시작하는 term, 자모 분리된 term, 또는 초성을 빈도수 기준으로 정렬하여 반환
     * 예: "ㅍ" 입력 시 "파이썬", "프로그래밍" 등이 나옴
     */
    @Query("SELECT at.term.term as term, " +
           "SUM(at.frequency) as totalFrequency " +
           "FROM ArticleTerm at " +
           "WHERE LOWER(at.term.term) LIKE LOWER(CONCAT(:query, '%')) " +
           "OR LOWER(at.term.decomposedTerm) LIKE LOWER(CONCAT(:query, '%')) " +
           "OR LOWER(at.term.chosung) LIKE LOWER(CONCAT(:query, '%')) " +
           "GROUP BY at.term.term " +
           "ORDER BY SUM(at.frequency) DESC")
    List<AutocompleteSuggestion> findAutocompleteTerms(@Param("query") String query, Pageable pageable);

    /**
     * 자동완성을 위한 Term 검색 (여러 패턴 지원, 빈도수 순)
     * 여러 검색 패턴으로 term, 자모 분리된 term, 초성을 검색
     * 예: "프롲" 입력 시 ["프롲", "프로ㅈ"] 두 패턴으로 검색
     */
    @Query("SELECT at.term.term as term, " +
           "SUM(at.frequency) as totalFrequency " +
           "FROM ArticleTerm at " +
           "WHERE " +
           "(LOWER(at.term.term) LIKE LOWER(CONCAT(COALESCE(:query1, ''), '%')) " +
           "OR LOWER(at.term.decomposedTerm) LIKE LOWER(CONCAT(COALESCE(:query1, ''), '%')) " +
           "OR LOWER(at.term.chosung) LIKE LOWER(CONCAT(COALESCE(:query1, ''), '%'))) " +
           "OR " +
           "(LOWER(at.term.term) LIKE LOWER(CONCAT(COALESCE(:query2, ''), '%')) " +
           "OR LOWER(at.term.decomposedTerm) LIKE LOWER(CONCAT(COALESCE(:query2, ''), '%')) " +
           "OR LOWER(at.term.chosung) LIKE LOWER(CONCAT(COALESCE(:query2, ''), '%'))) " +
           "GROUP BY at.term.term " +
           "ORDER BY SUM(at.frequency) DESC")
    List<AutocompleteSuggestion> findAutocompleteTermsWithPatterns(
        @Param("query1") String query1,
        @Param("query2") String query2,
        Pageable pageable);

    /**
     * Term 자동완성 검색 (비정규화 컬럼 사용, 최적화 버전)
     * 성능: 1,384ms → 1-2ms (약 1000배 개선)
     * Interface Projection 대신 DTO 클래스 사용으로 매핑 오버헤드 제거
     */
    @Query(value = """
        SELECT term, total_frequency
        FROM term
        WHERE (LOWER(term) LIKE LOWER(CONCAT(:query1, '%'))
           OR LOWER(decomposed_term) LIKE LOWER(CONCAT(:query1, '%'))
           OR LOWER(chosung) LIKE LOWER(CONCAT(:query1, '%'))
           OR LOWER(term) LIKE LOWER(CONCAT(:query2, '%'))
           OR LOWER(decomposed_term) LIKE LOWER(CONCAT(:query2, '%'))
           OR LOWER(chosung) LIKE LOWER(CONCAT(:query2, '%')))
        AND total_frequency > 0
        ORDER BY total_frequency DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findAutocompleteTermsOptimizedRaw(
        @Param("query1") String query1,
        @Param("query2") String query2,
        @Param("limit") int limit);

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
