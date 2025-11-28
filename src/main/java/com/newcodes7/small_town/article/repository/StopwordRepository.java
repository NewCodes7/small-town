package com.newcodes7.small_town.article.repository;

import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.newcodes7.small_town.global.entity.Stopword;

/**
 * Stopword Repository
 * 불용어를 관리
 */
public interface StopwordRepository extends JpaRepository<Stopword, Long> {

    /**
     * term과 termType으로 불용어 존재 여부 확인
     */
    boolean existsByTermAndTermType(String term, String termType);

    /**
     * 모든 불용어 term 조회 (캐싱용)
     * Set으로 반환하여 contains 연산을 O(1)로 만듦
     */
    @Query("SELECT s.term FROM Stopword s")
    Set<String> findAllTerms();
}
