package com.newcodes7.small_town.corporation.repository;

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

import com.newcodes7.small_town.global.entity.Corporation;

@Repository
public interface CorporationRepository extends JpaRepository<Corporation, Long>, JpaSpecificationExecutor<Corporation> {

    // 소프트 삭제되지 않은 기업 조회 (크롤링 등에서 사용)
    @EntityGraph(attributePaths = {"corporationIndustries", "corporationIndustries.industry"})
    @Query("SELECT c FROM Corporation c WHERE c.deletedAt IS NULL")
    List<Corporation> findAllActive();

    @EntityGraph(attributePaths = {"corporationIndustries", "corporationIndustries.industry"})
    @Query("SELECT c FROM Corporation c WHERE c.id = :id AND c.deletedAt IS NULL")
    Optional<Corporation> findActiveById(@Param("id") Long id);

    boolean existsByNameAndDeletedAtIsNull(String name);

    long countByDeletedAtIsNull();

    @Modifying
    @Query("UPDATE Corporation c SET c.viewCount = :viewCount WHERE c.id = :corporationId")
    void updateViewCount(@Param("corporationId") Long corporationId, @Param("viewCount") int viewCount);

    /**
     * 블로그가 있는 기업 중에서 이름 또는 대체 이름으로 검색 (자동완성용, 접두사 일치)
     * 검색 대상: name, alternateName, decomposedName, decomposedAlternateName, chosungName, chosungAlternateName
     */
    @Query("SELECT DISTINCT c FROM Corporation c " +
           "JOIN Article a ON a.corporation.id = c.id " +
           "WHERE c.deletedAt IS NULL " +
           "AND a.deletedAt IS NULL " +
           "AND (LOWER(c.name) LIKE LOWER(CONCAT(:query, '%')) " +
           "OR LOWER(c.alternateName) LIKE LOWER(CONCAT(:query, '%')) " +
           "OR LOWER(c.decomposedName) LIKE LOWER(CONCAT(:query, '%')) " +
           "OR LOWER(c.decomposedAlternateName) LIKE LOWER(CONCAT(:query, '%')) " +
           "OR LOWER(c.chosungName) LIKE LOWER(CONCAT(:query, '%')) " +
           "OR LOWER(c.chosungAlternateName) LIKE LOWER(CONCAT(:query, '%'))) " +
           "ORDER BY c.name ASC")
    List<Corporation> findCorporationsWithArticlesByNameContaining(@Param("query") String query, Pageable pageable);

    /**
     * 비디오가 있는 기업 중에서 이름 또는 대체 이름으로 검색 (자동완성용, 접두사 일치)
     * 검색 대상: name, alternateName, decomposedName, decomposedAlternateName, chosungName, chosungAlternateName
     */
    @Query("SELECT DISTINCT c FROM Corporation c " +
           "JOIN Video v ON v.corporation.id = c.id " +
           "WHERE c.deletedAt IS NULL " +
           "AND v.deletedAt IS NULL " +
           "AND (LOWER(c.name) LIKE LOWER(CONCAT(:query, '%')) " +
           "OR LOWER(c.alternateName) LIKE LOWER(CONCAT(:query, '%')) " +
           "OR LOWER(c.decomposedName) LIKE LOWER(CONCAT(:query, '%')) " +
           "OR LOWER(c.decomposedAlternateName) LIKE LOWER(CONCAT(:query, '%')) " +
           "OR LOWER(c.chosungName) LIKE LOWER(CONCAT(:query, '%')) " +
           "OR LOWER(c.chosungAlternateName) LIKE LOWER(CONCAT(:query, '%'))) " +
           "ORDER BY c.name ASC")
    List<Corporation> findCorporationsWithVideosByNameContaining(@Param("query") String query, Pageable pageable);
}