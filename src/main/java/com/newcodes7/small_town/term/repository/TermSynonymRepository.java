package com.newcodes7.small_town.term.repository;

import com.newcodes7.small_town.global.entity.TermSynonym;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * 여러 term 문자열의 유의어 쌍을 한 번에 조회 (양방향)
     * 검색어 확장의 term별 반복 조회(N+1)를 대체
     *
     * @param terms 조회할 term 문자열 목록
     * @return List<Object[]> - [term(String), 상대 term(String)] 쌍
     */
    @Query("SELECT t1.term, t2.term FROM TermSynonym ts " +
           "JOIN ts.term t1 " +
           "JOIN ts.synonymTerm t2 " +
           "WHERE t1.term IN :terms OR t2.term IN :terms")
    List<Object[]> findSynonymPairsByTerms(@Param("terms") Collection<String> terms);

    /**
     * 여러 term ID의 유의어 ID를 한 번에 조회 (양방향)
     * 원본 ID는 포함하지 않으므로 호출부에서 합집합을 만들어야 한다.
     * (양쪽 ID가 모두 termIds에 있으면 CASE가 한쪽만 반환하지만, 나머지 한쪽은 이미 원본에 있음)
     */
    @Query("SELECT CASE WHEN ts.term.id IN :termIds THEN ts.synonymTerm.id ELSE ts.term.id END " +
           "FROM TermSynonym ts " +
           "WHERE ts.term.id IN :termIds OR ts.synonymTerm.id IN :termIds")
    List<Long> findSynonymTermIdsByTermIds(@Param("termIds") Collection<Long> termIds);

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
     * Term 삭제 전에 외래 키 제약 조건을 만족시키기 위해 먼저 실행
     */
    @Modifying
    @Query("DELETE FROM TermSynonym ts " +
           "WHERE ts.term.id = :termId OR ts.synonymTerm.id = :termId")
    int deleteAllByTermId(@Param("termId") Long termId);

    /**
     * 모든 유의어 관계 조회 (관리 UI용)
     */
    @Query("SELECT ts FROM TermSynonym ts " +
           "JOIN FETCH ts.term " +
           "JOIN FETCH ts.synonymTerm " +
           "ORDER BY ts.createdAt DESC")
    List<TermSynonym> findAllWithTerms();
}
