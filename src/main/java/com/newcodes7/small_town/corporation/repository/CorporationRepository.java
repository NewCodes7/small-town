package com.newcodes7.small_town.corporation.repository;

import com.newcodes7.small_town.global.entity.Corporation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CorporationRepository extends JpaRepository<Corporation, Long>, JpaSpecificationExecutor<Corporation> {

    // 소프트 삭제되지 않은 기업 조회 (크롤링 등에서 사용)
    @EntityGraph(attributePaths = {"corporationIndustries", "corporationIndustries.industry"})
    @Query("SELECT c FROM Corporation c WHERE c.deletedAt IS NULL")
    List<Corporation> findAllActive();

    // 필터 드롭다운 등 이름순 목록이 필요한 곳에서 사용 (연관관계 미조회)
    List<Corporation> findAllByDeletedAtIsNullOrderByNameAsc();

    @EntityGraph(attributePaths = {"corporationIndustries", "corporationIndustries.industry"})
    @Query("SELECT c FROM Corporation c WHERE c.id = :id AND c.deletedAt IS NULL")
    Optional<Corporation> findActiveById(@Param("id") Long id);

    boolean existsByNameAndDeletedAtIsNull(String name);

    long countByDeletedAtIsNull();

    // 조회수 증가 (원자적 연산으로 동시성 안전)
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Corporation c SET c.viewCount = c.viewCount + 1 WHERE c.id = :corporationId")
    int incrementViewCount(@Param("corporationId") Long corporationId);

    /**
     * 블로그가 있는 기업 중에서 이름 또는 대체 이름으로 검색 (자동완성용, 접두사 일치)
     * 검색 대상: name, alternateName, decomposedName, decomposedAlternateName, chosungName, chosungAlternateName
     */
    @Query("SELECT c FROM Corporation c " +
           "JOIN Article a ON a.corporation.id = c.id " +
           "WHERE c.deletedAt IS NULL " +
           "AND a.deletedAt IS NULL " +
           "AND (LOWER(c.name) LIKE LOWER(CONCAT(COALESCE(:query, ''), '%')) " +
           "OR LOWER(c.alternateName) LIKE LOWER(CONCAT(COALESCE(:query, ''), '%')) " +
           "OR LOWER(c.decomposedName) LIKE LOWER(CONCAT(COALESCE(:query, ''), '%')) " +
           "OR LOWER(c.decomposedAlternateName) LIKE LOWER(CONCAT(COALESCE(:query, ''), '%')) " +
           "OR LOWER(c.chosungName) LIKE LOWER(CONCAT(COALESCE(:query, ''), '%')) " +
           "OR LOWER(c.chosungAlternateName) LIKE LOWER(CONCAT(COALESCE(:query, ''), '%'))) " +
           "ORDER BY c.name ASC")
    List<Corporation> findCorporationsWithArticlesByNameContaining(@Param("query") String query, Pageable pageable);

    /**
     * 블로그가 있는 기업 검색 (최적화 버전 - EXISTS 사용)
     * 성능: 601ms → 1.6ms (375배 개선)
     * JOIN 대신 EXISTS를 사용하여 Planning Time과 Execution Time을 대폭 감소
     */
    @Query("SELECT DISTINCT c FROM Corporation c " +
           "WHERE c.deletedAt IS NULL " +
           "AND EXISTS (SELECT 1 FROM Article a WHERE a.corporation.id = c.id AND a.deletedAt IS NULL) " +
           "AND (LOWER(c.name) LIKE LOWER(CONCAT(:query, '%')) " +
           "OR LOWER(c.alternateName) LIKE LOWER(CONCAT(:query, '%')) " +
           "OR LOWER(c.decomposedName) LIKE LOWER(CONCAT(:query, '%')) " +
           "OR LOWER(c.decomposedAlternateName) LIKE LOWER(CONCAT(:query, '%')) " +
           "OR LOWER(c.chosungName) LIKE LOWER(CONCAT(:query, '%')) " +
           "OR LOWER(c.chosungAlternateName) LIKE LOWER(CONCAT(:query, '%'))) " +
           "ORDER BY c.name ASC")
    List<Corporation> findCorporationsWithArticlesByNameOptimized(@Param("query") String query, Pageable pageable);

    /**
     * RAG 질의 전처리용: 추출된 기업명 목록을 name/alternateName과 정확 일치로 매칭
     * 부분/포함 매칭은 하지 않음 (오매칭 방지) — 호출부에서 lower/trim 후 전달
     */
    @Query("SELECT c FROM Corporation c WHERE c.deletedAt IS NULL " +
           "AND (LOWER(c.name) IN (:names) OR LOWER(c.alternateName) IN (:names))")
    List<Corporation> findActiveByLowerNames(@Param("names") List<String> names);

    /**
     * 비디오가 있는 기업 중에서 이름 또는 대체 이름으로 검색 (자동완성용, 접두사 일치)
     * 검색 대상: name, alternateName, decomposedName, decomposedAlternateName, chosungName, chosungAlternateName
     */
    @Query("SELECT c FROM Corporation c " +
           "JOIN Video v ON v.corporation.id = c.id " +
           "WHERE c.deletedAt IS NULL " +
           "AND v.deletedAt IS NULL " +
           "AND (LOWER(c.name) LIKE LOWER(CONCAT(COALESCE(:query, ''), '%')) " +
           "OR LOWER(c.alternateName) LIKE LOWER(CONCAT(COALESCE(:query, ''), '%')) " +
           "OR LOWER(c.decomposedName) LIKE LOWER(CONCAT(COALESCE(:query, ''), '%')) " +
           "OR LOWER(c.decomposedAlternateName) LIKE LOWER(CONCAT(COALESCE(:query, ''), '%')) " +
           "OR LOWER(c.chosungName) LIKE LOWER(CONCAT(COALESCE(:query, ''), '%')) " +
           "OR LOWER(c.chosungAlternateName) LIKE LOWER(CONCAT(COALESCE(:query, ''), '%'))) " +
           "ORDER BY c.name ASC")
    List<Corporation> findCorporationsWithVideosByNameContaining(@Param("query") String query, Pageable pageable);
}
