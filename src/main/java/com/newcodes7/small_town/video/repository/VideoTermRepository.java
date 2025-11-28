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
     * Term 통계 조회 (많이 사용된 순)
     */
    @Query("SELECT vt.term.id as termId, " +
           "vt.term.term as term, " +
           "vt.term.termType as termType, " +
           "SUM(vt.frequency) as totalFrequency, " +
           "COUNT(DISTINCT vt.video.id) as videoCount " +
           "FROM VideoTerm vt " +
           "GROUP BY vt.term.id, vt.term.term, vt.term.termType " +
           "ORDER BY SUM(vt.frequency) DESC")
    List<TermStatistics> findTermStatistics(Pageable pageable);

    /**
     * Term 통계 조회 (검색 기능 포함)
     */
    @Query("SELECT vt.term.id as termId, " +
           "vt.term.term as term, " +
           "vt.term.termType as termType, " +
           "SUM(vt.frequency) as totalFrequency, " +
           "COUNT(DISTINCT vt.video.id) as videoCount " +
           "FROM VideoTerm vt " +
           "WHERE LOWER(vt.term.term) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "GROUP BY vt.term.id, vt.term.term, vt.term.termType " +
           "ORDER BY SUM(vt.frequency) DESC")
    List<TermStatistics> findTermStatisticsBySearch(@Param("search") String search, Pageable pageable);

    /**
     * Term 통계 인터페이스 (Projection)
     */
    interface TermStatistics {
        Long getTermId();
        String getTerm();
        String getTermType();
        Long getTotalFrequency();
        Long getVideoCount();
    }
}
