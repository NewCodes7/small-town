package com.newcodes7.small_town.video.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.newcodes7.small_town.global.entity.VideoTerm;

public interface VideoTermRepository extends JpaRepository<VideoTerm, Long> {

    /**
     * 특정 video의 모든 term 조회 (Term fetch join)
     */
    @Query("SELECT vt FROM VideoTerm vt JOIN FETCH vt.term WHERE vt.video.id = :videoId")
    List<VideoTerm> findByVideoId(@Param("videoId") Long videoId);

    /**
     * 특정 video의 모든 term 삭제
     */
    @Modifying
    @Query("DELETE FROM VideoTerm vt WHERE vt.video.id = :videoId")
    void deleteByVideoId(@Param("videoId") Long videoId);

    /**
     * 특정 video에 term이 있는지 확인 (건너뛰기 판단용)
     */
    boolean existsByVideoId(Long videoId);

    /**
     * 특정 term ID를 가진 모든 VideoTerm 삭제
     */
    @Modifying
    @Query("DELETE FROM VideoTerm vt WHERE vt.term.id = :termId")
    int deleteByTermId(@Param("termId") Long termId);

    /**
     * 특정 term ID를 가진 VideoTerm 개수 조회
     */
    @Query("SELECT COUNT(vt) FROM VideoTerm vt WHERE vt.term.id = :termId")
    int countByTermId(@Param("termId") Long termId);

    /**
     * 여러 term ID로 video 검색용 (OR 조건)
     * 유의어 검색에 사용
     */
    @Query("SELECT DISTINCT vt.video.id FROM VideoTerm vt WHERE vt.term.id IN :termIds")
    List<Long> findVideoIdsByTermIds(@Param("termIds") List<Long> termIds);

    /**
     * Term 통계 조회 (많이 사용된 순)
     * Pageable의 sort를 통해 정렬 가능 (frequency, createdAt)
     */
    @Query("SELECT vt.term.id as termId, " +
           "vt.term.term as term, " +
           "vt.term.termType as termType, " +
           "vt.term.decomposedTerm as decomposedTerm, " +
           "vt.term.createdAt as createdAt, " +
           "SUM(vt.frequency) as totalFrequency, " +
           "COUNT(DISTINCT vt.video.id) as videoCount " +
           "FROM VideoTerm vt " +
           "GROUP BY vt.term.id, vt.term.term, vt.term.termType, vt.term.decomposedTerm, vt.term.createdAt")
    List<TermStatistics> findTermStatistics(Pageable pageable);

    /**
     * Term 통계 조회 (검색 기능 포함)
     * Pageable의 sort를 통해 정렬 가능 (frequency, createdAt)
     */
    @Query("SELECT vt.term.id as termId, " +
           "vt.term.term as term, " +
           "vt.term.termType as termType, " +
           "vt.term.decomposedTerm as decomposedTerm, " +
           "vt.term.createdAt as createdAt, " +
           "SUM(vt.frequency) as totalFrequency, " +
           "COUNT(DISTINCT vt.video.id) as videoCount " +
           "FROM VideoTerm vt " +
           "WHERE LOWER(vt.term.term) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "GROUP BY vt.term.id, vt.term.term, vt.term.termType, vt.term.decomposedTerm, vt.term.createdAt")
    List<TermStatistics> findTermStatisticsBySearch(@Param("search") String search, Pageable pageable);

    /**
     * Term 통계 총 개수 조회
     */
    @Query("SELECT COUNT(DISTINCT vt.term.id) FROM VideoTerm vt")
    long countDistinctTerms();

    /**
     * Term 통계 총 개수 조회 (검색 포함)
     */
    @Query("SELECT COUNT(DISTINCT vt.term.id) FROM VideoTerm vt WHERE LOWER(vt.term.term) LIKE LOWER(CONCAT('%', :search, '%'))")
    long countDistinctTermsBySearch(@Param("search") String search);

    /**
     * 자동완성을 위한 Term 검색 (빈도수 순)
     * 사용자 입력값으로 시작하는 term, 자모 분리된 term, 또는 초성을 빈도수 기준으로 정렬하여 반환
     * 예: "ㅍ" 입력 시 "파이썬", "프로그래밍" 등이 나옴
     */
    @Query("SELECT vt.term.term as term, " +
           "SUM(vt.frequency) as totalFrequency " +
           "FROM VideoTerm vt " +
           "WHERE LOWER(vt.term.term) LIKE LOWER(CONCAT(:query, '%')) " +
           "OR LOWER(vt.term.decomposedTerm) LIKE LOWER(CONCAT(:query, '%')) " +
           "OR LOWER(vt.term.chosung) LIKE LOWER(CONCAT(:query, '%')) " +
           "GROUP BY vt.term.term " +
           "ORDER BY SUM(vt.frequency) DESC")
    List<AutocompleteSuggestion> findAutocompleteTerms(@Param("query") String query, Pageable pageable);

    /**
     * 자동완성을 위한 Term 검색 (여러 패턴 지원, 빈도수 순)
     * 여러 검색 패턴으로 term, 자모 분리된 term, 초성을 검색
     * 예: "프롲" 입력 시 ["프롲", "프로ㅈ"] 두 패턴으로 검색
     */
    @Query("SELECT vt.term.term as term, " +
           "SUM(vt.frequency) as totalFrequency " +
           "FROM VideoTerm vt " +
           "WHERE " +
           "(LOWER(vt.term.term) LIKE LOWER(CONCAT(:query1, '%')) " +
           "OR LOWER(vt.term.decomposedTerm) LIKE LOWER(CONCAT(:query1, '%')) " +
           "OR LOWER(vt.term.chosung) LIKE LOWER(CONCAT(:query1, '%'))) " +
           "OR " +
           "(LOWER(vt.term.term) LIKE LOWER(CONCAT(:query2, '%')) " +
           "OR LOWER(vt.term.decomposedTerm) LIKE LOWER(CONCAT(:query2, '%')) " +
           "OR LOWER(vt.term.chosung) LIKE LOWER(CONCAT(:query2, '%'))) " +
           "GROUP BY vt.term.term " +
           "ORDER BY SUM(vt.frequency) DESC")
    List<AutocompleteSuggestion> findAutocompleteTermsWithPatterns(
        @Param("query1") String query1,
        @Param("query2") String query2,
        Pageable pageable);

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
        Long getVideoCount();
    }

    /**
     * 자동완성 제안 인터페이스 (Projection)
     */
    interface AutocompleteSuggestion {
        String getTerm();
        Long getTotalFrequency();
    }
}
