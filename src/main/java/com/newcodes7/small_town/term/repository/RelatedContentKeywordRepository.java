package com.newcodes7.small_town.term.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.newcodes7.small_town.global.entity.RelatedContentKeyword;

/**
 * RelatedContentKeyword Repository
 * 본문 추출 시 제거할 관련 글/추천 글 키워드 관리
 */
@Repository
public interface RelatedContentKeywordRepository extends JpaRepository<RelatedContentKeyword, Long> {

    /**
     * 모든 키워드 조회 (생성일 순)
     */
    List<RelatedContentKeyword> findAllByOrderByCreatedAtAsc();

    /**
     * 특정 키워드 존재 여부 확인
     */
    boolean existsByKeyword(String keyword);
}
