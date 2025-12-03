package com.newcodes7.small_town.article.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
