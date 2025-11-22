package com.newcodes7.small_town.corporation.repository;

import java.util.List;
import java.util.Optional;

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
}