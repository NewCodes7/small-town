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
     * Term 통계 조회 (많이 사용된 순)
     * Term 엔티티를 참조하여 중복 없이 통계 집계
     */
    @Query("SELECT at.term.term as term, " +
           "at.term.termType as termType, " +
           "SUM(at.frequency) as totalFrequency, " +
           "COUNT(DISTINCT at.article.id) as articleCount " +
           "FROM ArticleTerm at " +
           "GROUP BY at.term.id, at.term.term, at.term.termType " +
           "ORDER BY SUM(at.frequency) DESC")
    List<TermStatistics> findTermStatistics(Pageable pageable);

    /**
     * Term 통계 인터페이스 (Projection)
     */
    interface TermStatistics {
        String getTerm();
        String getTermType();
        Long getTotalFrequency();
        Long getArticleCount();
    }
}
