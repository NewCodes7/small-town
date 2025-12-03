package com.newcodes7.small_town.article.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.newcodes7.small_town.global.entity.TermSynonym;

/**
 * TermSynonym Repository
 * Term 간 유의어 관계를 관리
 */
public interface TermSynonymRepository extends JpaRepository<TermSynonym, Long> {

    /**
     * 특정 term의 모든 유의어 ID 조회 (양방향)
     * termId가 term_id 또는 synonym_term_id에 있는 경우 모두 찾기
     */
    @Query("SELECT CASE WHEN ts.term.id = :termId THEN ts.synonymTerm.id ELSE ts.term.id END " +
           "FROM TermSynonym ts " +
           "WHERE ts.term.id = :termId OR ts.synonymTerm.id = :termId")
    List<Long> findSynonymTermIdsByTermId(@Param("termId") Long termId);

    /**
     * 특정 term의 모든 유의어 조회 (양방향, Term 엔티티 fetch)
     */
    @Query("SELECT CASE WHEN ts.term.id = :termId THEN ts.synonymTerm ELSE ts.term END " +
           "FROM TermSynonym ts " +
           "WHERE ts.term.id = :termId OR ts.synonymTerm.id = :termId")
    List<com.newcodes7.small_town.global.entity.Term> findSynonymTermsByTermId(@Param("termId") Long termId);

    /**
     * 두 term 간의 유의어 관계가 존재하는지 확인
     */
    @Query("SELECT CASE WHEN COUNT(ts) > 0 THEN true ELSE false END " +
           "FROM TermSynonym ts " +
           "WHERE (ts.term.id = :termId1 AND ts.synonymTerm.id = :termId2) " +
           "OR (ts.term.id = :termId2 AND ts.synonymTerm.id = :termId1)")
    boolean existsByTermIds(@Param("termId1") Long termId1, @Param("termId2") Long termId2);

    /**
     * 두 term 간의 유의어 관계 조회
     */
    @Query("SELECT ts FROM TermSynonym ts " +
           "WHERE (ts.term.id = :termId1 AND ts.synonymTerm.id = :termId2) " +
           "OR (ts.term.id = :termId2 AND ts.synonymTerm.id = :termId1)")
    Optional<TermSynonym> findByTermIds(@Param("termId1") Long termId1, @Param("termId2") Long termId2);

    /**
     * 특정 term이 포함된 모든 유의어 관계 조회
     */
    @Query("SELECT ts FROM TermSynonym ts " +
           "WHERE ts.term.id = :termId OR ts.synonymTerm.id = :termId")
    List<TermSynonym> findAllByTermId(@Param("termId") Long termId);

    /**
     * 특정 term이 포함된 모든 유의어 관계 삭제
     */
    @Query("DELETE FROM TermSynonym ts " +
           "WHERE ts.term.id = :termId OR ts.synonymTerm.id = :termId")
    void deleteAllByTermId(@Param("termId") Long termId);

    /**
     * 모든 유의어 관계 조회 (관리 UI용)
     */
    @Query("SELECT ts FROM TermSynonym ts " +
           "JOIN FETCH ts.term " +
           "JOIN FETCH ts.synonymTerm " +
           "ORDER BY ts.createdAt DESC")
    List<TermSynonym> findAllWithTerms();
}
